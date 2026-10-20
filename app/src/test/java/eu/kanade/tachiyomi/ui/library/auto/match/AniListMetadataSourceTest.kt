package eu.kanade.tachiyomi.ui.library.auto.match

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException

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

    @Test
    fun `searchByTitle HTTP 500 aldiginda IOException firlatir`() = runTest {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("Sunucu Hatasi".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val source = AniListHttpMetadataSource(
            client = mockClient,
            endpoint = "http://localhost:12345/graphql",
        )

        val ex = shouldThrow<IOException> {
            source.searchByTitle("Test")
        }
        ex.message shouldContain "500"
    }

    @Test
    fun `searchByTitle HTTP 200 olsa bile errors dolu veya data null iken IOException firlatir`() = runTest {
        val errorJson = """
            {
                "errors": [
                    { "message": "Validation failed on field 'search'", "status": 400 }
                ],
                "data": null
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(errorJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val source = AniListHttpMetadataSource(
            client = mockClient,
            endpoint = "http://localhost:12345/graphql",
        )

        val ex = shouldThrow<IOException> {
            source.searchByTitle("Test")
        }
        ex.message shouldContain "Validation failed on field 'search'"
    }

    @Test
    fun `searchByTitle bozuk JSON aldiginda istisna firlatir`() = runTest {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{ bu bozuk bir json metnidir !".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val source = AniListHttpMetadataSource(
            client = mockClient,
            endpoint = "http://localhost:12345/graphql",
        )

        shouldThrow<Exception> {
            source.searchByTitle("Test")
        }
    }

    @Test
    fun `searchByTitle ag kopmasinda IOException firlatir`() = runTest {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("Ağ bağlantısı koptu")
            }
            .build()

        val source = AniListHttpMetadataSource(
            client = mockClient,
            endpoint = "http://localhost:12345/graphql",
        )

        val ex = shouldThrow<IOException> {
            source.searchByTitle("Test")
        }
        ex.message shouldContain "Ağ bağlantısı koptu"
    }

    @Test
    fun `searchByTitle 429 sonrasi ikinci denemede basariyla sonuclanir`() = runTest {
        var attempts = 0
        val successResponseJson = """
            {
                "data": {
                    "Page": {
                        "media": [
                            {
                                "id": 123,
                                "title": { "romaji": "Success Manga", "english": "Success Manga", "native": "" },
                                "synonyms": [],
                                "countryOfOrigin": "JP",
                                "genres": ["Action"],
                                "tags": [],
                                "format": "MANGA",
                                "staff": { "edges": [] }
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts++
                if (attempts == 1) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .header("Retry-After", "0")
                        .body("Rate limit exceeded".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(successResponseJson.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val testLimiter = AniListRateLimiter(
            permits = 25,
            periodMs = 60_000L,
            delayFn = { /* beklemeden devam et */ },
        )

        val source = AniListHttpMetadataSource(
            client = mockClient,
            rateLimiter = testLimiter,
            endpoint = "http://localhost:12345/graphql",
        )

        val results = source.searchByTitle("Success Manga")
        attempts shouldBe 2
        results shouldHaveSize 1
        results[0].id shouldBe 123L
    }

    @Test
    fun `searchByTitle yanit basarili errors yok ve media bosken bos liste doner`() = runTest {
        val emptyJson = """
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
                    .body(emptyJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val source = AniListHttpMetadataSource(
            client = mockClient,
            endpoint = "http://localhost:12345/graphql",
        )

        val results = source.searchByTitle("Empty Media")
        results.isEmpty() shouldBe true
    }

    @Test
    fun `AniListRateLimiter RateLimitResponse Error durumunda istisna firlatir`() = runTest {
        val limiter = AniListRateLimiter()
        val ex = shouldThrow<IOException> {
            limiter.executeWithRetry<String> {
                RateLimitResponse.Error(IOException("Özel hata fırlatıldı"))
            }
        }
        ex.message shouldContain "Özel hata fırlatıldı"
    }

    @Test
    fun `AniListRateLimiter 429 sonrasi tum denemeler tukendiginde AniListRateLimitException firlatir`() = runTest {
        val limiter = AniListRateLimiter(
            delayFn = { /* hemen geç */ },
        )
        val ex = shouldThrow<AniListRateLimitException> {
            limiter.executeWithRetry<String>(maxRetries = 3) {
                RateLimitResponse.RateLimited(retryAfterSeconds = 1L)
            }
        }
        ex.message shouldContain "3 deneme tükendi"
    }
}

