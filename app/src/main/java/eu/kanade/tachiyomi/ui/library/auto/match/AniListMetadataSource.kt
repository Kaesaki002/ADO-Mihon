package eu.kanade.tachiyomi.ui.library.auto.match

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * AniList manga metadata kaynağı arayüzü.
 */
interface AniListMetadataSource {
    suspend fun searchByTitle(title: String): List<AniListMediaCandidate>
    suspend fun getByIds(ids: List<Long>): Map<Long, AniListMediaCandidate>
}

/**
 * OkHttp tabanlı gerçek AniList GraphQL istemcisi.
 */
class AniListHttpMetadataSource(
    private val client: OkHttpClient = OkHttpClient(),
    private val rateLimiter: AniListRateLimiter = AniListRateLimiter.DEFAULT,
    private val endpoint: String = "https://graphql.anilist.co",
) : AniListMetadataSource {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val SEARCH_QUERY = """
query (${'$'}search: String) {
  Page(page: 1, perPage: 10) {
    media(search: ${'$'}search, type: MANGA) {
      id
      title { romaji english native }
      synonyms
      countryOfOrigin
      genres
      tags {
        name
        rank
        isGeneralSpoiler
        isMediaSpoiler
      }
      format
      staff {
        edges {
          role
          node { name { full native } }
        }
      }
    }
  }
}"""

        const val GET_BY_IDS_QUERY = """
query (${'$'}ids: [Int], ${'$'}perPage: Int) {
  Page(page: 1, perPage: ${'$'}perPage) {
    media(id_in: ${'$'}ids, type: MANGA) {
      id
      title { romaji english native }
      synonyms
      countryOfOrigin
      genres
      tags {
        name
        rank
        isGeneralSpoiler
        isMediaSpoiler
      }
      format
      staff {
        edges {
          role
          node { name { full native } }
        }
      }
    }
  }
}"""
    }

    override suspend fun searchByTitle(title: String): List<AniListMediaCandidate> = withContext(Dispatchers.IO) {
        val variables = buildJsonObject {
            put("search", title.trim())
        }

        executeGraphQL(SEARCH_QUERY, variables)
    }

    override suspend fun getByIds(ids: List<Long>): Map<Long, AniListMediaCandidate> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()

        val results = mutableMapOf<Long, AniListMediaCandidate>()

        // Sayfa başına en fazla 50 ID gruplaması
        for (chunk in ids.distinct().chunked(50)) {
            val variables = buildJsonObject {
                putJsonArray("ids") {
                    for (id in chunk) add(kotlinx.serialization.json.JsonPrimitive(id.toInt()))
                }
                put("perPage", chunk.size)
            }

            val candidates = executeGraphQL(GET_BY_IDS_QUERY, variables)
            for (cand in candidates) {
                results[cand.id] = cand
            }
        }

        results
    }

    private suspend fun executeGraphQL(query: String, variables: JsonObject): List<AniListMediaCandidate> {
        val payload = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        val requestBody = payload.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "ADOmination-MangaApp/1.0")
            .build()

        return rateLimiter.executeWithRetry {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull() ?: 10L
                        return@executeWithRetry RateLimitResponse.RateLimited(retryAfterSeconds)
                    }

                    if (!response.isSuccessful) {
                        return@executeWithRetry RateLimitResponse.Error(
                            IOException("AniList API HTTP hatası: ${response.code}"),
                        )
                    }

                    val bodyString = response.body.string()
                    val pageResponse = AniListJsonParser.decodeFromString<GraphQLPageWrapper>(bodyString)

                    if (!pageResponse.errors.isNullOrEmpty()) {
                        val errorMessages = pageResponse.errors.mapNotNull { it.message }.joinToString("; ")
                        val errorText = if (errorMessages.isNotBlank()) errorMessages else "Bilinmeyen GraphQL hatası"
                        return@executeWithRetry RateLimitResponse.Error(
                            IOException("AniList GraphQL hatası: $errorText"),
                        )
                    }

                    val page = pageResponse.data?.Page
                        ?: return@executeWithRetry RateLimitResponse.Error(
                            IOException("AniList API yanıtında 'data' veya 'Page' alanı eksik."),
                        )

                    val candidates = page.media.map { it.toCandidate() }
                    RateLimitResponse.Success(candidates)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RateLimitResponse.Error(e)
            }
        }
    }
}

@Serializable
private data class GraphQLPageWrapper(
    val data: GraphQLDataWrapper? = null,
    val errors: List<GraphQLErrorDto>? = null,
)

@Serializable
private data class GraphQLErrorDto(
    val message: String? = null,
    val status: Int? = null,
)

@Serializable
private data class GraphQLDataWrapper(
    val Page: GraphQLPageContainer? = null,
)

@Serializable
private data class GraphQLPageContainer(
    val media: List<AniListMediaDto> = emptyList(),
)

