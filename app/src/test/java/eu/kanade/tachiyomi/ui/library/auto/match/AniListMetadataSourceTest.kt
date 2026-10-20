package eu.kanade.tachiyomi.ui.library.auto.match

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

class AniListMetadataSourceTest {

    @Test
    fun `searchByTitle sorgu metni tags blogunda isGeneralSpoiler ve isMediaSpoiler icerir`() {
        val query = AniListHttpMetadataSource.SEARCH_QUERY

        query shouldContain "tags {"
        val tagsBlock = query.substringAfter("tags {").substringBefore("}")
        tagsBlock shouldContain "name"
        tagsBlock shouldContain "rank"
        tagsBlock shouldContain "isGeneralSpoiler"
        tagsBlock shouldContain "isMediaSpoiler"
    }

    @Test
    fun `getByIds sorgu metni tags blogunda isGeneralSpoiler ve isMediaSpoiler icerir`() {
        val query = AniListHttpMetadataSource.GET_BY_IDS_QUERY

        query shouldContain "tags {"
        val tagsBlock = query.substringAfter("tags {").substringBefore("}")
        tagsBlock shouldContain "name"
        tagsBlock shouldContain "rank"
        tagsBlock shouldContain "isGeneralSpoiler"
        tagsBlock shouldContain "isMediaSpoiler"
    }

    @Test
    fun `AniListHttpMetadataSource searchByTitle ve getByIds yollarinda rate limiter kullanir`() = runTest {
        var clockCalls = 0
        val testClock: () -> Long = {
            clockCalls++
            System.currentTimeMillis()
        }
        val customLimiter = AniListRateLimiter(
            permits = 25,
            periodMs = 60_000L,
            clock = testClock,
        )

        val emptyGraphqlResponseJson = """
            {
                "data": {
                    "Page": {
                        "media": []
                    }
                }
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(emptyGraphqlResponseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val source = AniListHttpMetadataSource(
            client = mockClient,
            rateLimiter = customLimiter,
            endpoint = "http://localhost:12345/graphql",
        )

        val clockCallsBefore = clockCalls

        // 1. searchByTitle çağrısı
        source.searchByTitle("Test Manga")
        val clockCallsAfterSearch = clockCalls
        (clockCallsAfterSearch > clockCallsBefore) shouldBe true

        // 2. getByIds çağrısı
        source.getByIds(listOf(12345L))
        val clockCallsAfterGetByIds = clockCalls
        (clockCallsAfterGetByIds > clockCallsAfterSearch) shouldBe true
    }
}

