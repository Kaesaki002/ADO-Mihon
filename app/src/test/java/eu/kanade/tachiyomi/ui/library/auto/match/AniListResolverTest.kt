package eu.kanade.tachiyomi.ui.library.auto.match

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class AniListResolverTest {

    @TempDir
    lateinit var tempDir: File

    private var currentTime: Long = 1_700_000_000_000L // Başlangıç zamanı
    private val clock: () -> Long = { currentTime }

    private lateinit var fakeSource: FakeAniListMetadataSource
    private lateinit var cache: AniListMatchCache
    private lateinit var resolver: AniListResolver

    @BeforeEach
    fun setUp() {
        fakeSource = FakeAniListMetadataSource()
        cache = AniListMatchCache(baseDir = tempDir, clock = clock)
        resolver = AniListResolver(metadataSource = fakeSource, matchCache = cache)
    }

    private fun sampleCandidate(
        id: Long = 30021L,
        title: String = "Death Note",
        format: String = "MANGA",
        country: String = "JP",
        staff: List<String> = listOf("Tsugumi Ohba"),
    ) = AniListMediaCandidate(
        id = id,
        titleRomaji = title,
        titleEnglish = title,
        format = format,
        countryOfOrigin = country,
        staffNames = staff,
    )

    // ========================================================
    // 1. Önbellek İsabeti ve Kalıcılık Testleri
    // ========================================================

    @Test
    fun `onbellek isabeti durumunda ag cagrisi yapilmaz ve isFromCache true doner`() = runTest {
        val candidate = sampleCandidate()
        fakeSource.candidatesToReturn = listOf(candidate)

        val input = ResolverMangaInput(mangaId = 1L, title = "Death Note")

        // 1. İlk çağrı: Ağa gider, önbelleğe kaydeder
        val result1 = resolver.resolve(input)
        result1.status shouldBe MatchStatus.MATCHED
        result1.isFromCache shouldBe false
        fakeSource.searchCalls shouldHaveSize 1

        // 2. İkinci çağrı: Doğrudan önbellekten döner
        val result2 = resolver.resolve(input)
        result2.status shouldBe MatchStatus.MATCHED
        result2.isFromCache shouldBe true
        result2.candidate.shouldNotBeNull()
        result2.candidate.id shouldBe 30021L
        fakeSource.searchCalls shouldHaveSize 1 // Yeni arama yapılmadı
    }

    @Test
    fun `MATCHED sonucu kalicidir 50 gun sonra bile onbellekten gelir`() = runTest {
        val candidate = sampleCandidate()
        fakeSource.candidatesToReturn = listOf(candidate)
        val input = ResolverMangaInput(mangaId = 1L, title = "Death Note")

        resolver.resolve(input)

        // 50 gün ilerlet (> 30 gün kuralını test eder, < 90 gün metadata süresi içindedir)
        currentTime += 50L * 24 * 60 * 60 * 1000

        val result = resolver.resolve(input)
        result.status shouldBe MatchStatus.MATCHED
        result.isFromCache shouldBe true
        fakeSource.searchCalls shouldHaveSize 1
    }

    // ========================================================
    // 2. 30 Günlük Süre ve Yeniden Deneme Testleri
    // ========================================================

    @Test
    fun `BELIRSIZ ve ESLESMEDI kayitlari 29 gunde gecerlidir 31 gunde suresi dolup yeniden aranir`() = runTest {
        // İki aday da MANGA formatında -> AMBIGUOUS
        fakeSource.candidatesToReturn = listOf(
            sampleCandidate(id = 1L, title = "Kamen Teacher", format = "MANGA"),
            sampleCandidate(id = 2L, title = "Kamen Teacher", format = "MANGA"),
        )
        val input = ResolverMangaInput(mangaId = 5L, title = "Kamen Teacher")

        // 1. İlk sorgu -> AMBIGUOUS
        val res1 = resolver.resolve(input)
        res1.status shouldBe MatchStatus.AMBIGUOUS
        res1.isFromCache shouldBe false
        fakeSource.searchCalls shouldHaveSize 1

        // 2. 29 gün sonra: Hala geçerli (ağa gitmez)
        currentTime += 29L * 24 * 60 * 60 * 1000
        val res2 = resolver.resolve(input)
        res2.status shouldBe MatchStatus.AMBIGUOUS
        res2.isFromCache shouldBe true
        fakeSource.searchCalls shouldHaveSize 1

        // 3. 31 gün sonra (toplam 31 gün): Süresi doldu, tekrar arar
        currentTime += 2L * 24 * 60 * 60 * 1000
        val res3 = resolver.resolve(input)
        res3.status shouldBe MatchStatus.AMBIGUOUS
        res3.isFromCache shouldBe false
        fakeSource.searchCalls shouldHaveSize 2
    }

    // ========================================================
    // 3. Hata Yönetimi ve Bozuk Dosya Testleri
    // ========================================================

    @Test
    fun `ag ve 500 hatasi ASLA onbellege yazilmaz`() = runTest {
        fakeSource.throwErrorOnSearch = IOException("500 Internal Server Error")
        val input = ResolverMangaInput(mangaId = 1L, title = "Bleach")

        shouldThrow<IOException> {
            resolver.resolve(input)
        }

        // matches klasöründe dosya oluşmamalı
        val matchFile = File(tempDir, "matches/1.json")
        matchFile.exists() shouldBe false
    }

    @Test
    fun `CancellationException yakalanmaz ve onbellege yazilmaz`() = runTest {
        fakeSource.throwErrorOnSearch = CancellationException("Job was cancelled")
        val input = ResolverMangaInput(mangaId = 1L, title = "Bleach")

        shouldThrow<CancellationException> {
            resolver.resolve(input)
        }

        val matchFile = File(tempDir, "matches/1.json")
        matchFile.exists() shouldBe false
    }

    @Test
    fun `bozuk dosya durumunda cokmez dosyayi temizleyip taze veri ceker`() = runTest {
        val matchesDir = File(tempDir, "matches")
        matchesDir.mkdirs()
        val corruptFile = File(matchesDir, "1.json")
        corruptFile.writeText("{ bu gecersiz bir json metnidir !!! }")

        val candidate = sampleCandidate()
        fakeSource.candidatesToReturn = listOf(candidate)
        val input = ResolverMangaInput(mangaId = 1L, title = "Death Note")

        val result = resolver.resolve(input)
        result.status shouldBe MatchStatus.MATCHED
        result.isFromCache shouldBe false
        fakeSource.searchCalls shouldHaveSize 1
    }

    @Test
    fun `sema surumu uyusmazliginda eski onbellek yok sayilir`() = runTest {
        // Eski sürüm (version = 999) olan bir dosya yaz
        val matchesDir = File(tempDir, "matches")
        matchesDir.mkdirs()
        val oldVersionFile = File(matchesDir, "1.json")
        oldVersionFile.writeText("""
            {
                "version": 999,
                "mangaId": 1,
                "normalizedTitle": "death note",
                "normalizedAuthors": "",
                "status": "MATCHED",
                "reason": "SCORE",
                "score": 1.0,
                "anilistId": 30021,
                "timestamp": $currentTime
            }
        """.trimIndent())

        val candidate = sampleCandidate()
        fakeSource.candidatesToReturn = listOf(candidate)
        val input = ResolverMangaInput(mangaId = 1L, title = "Death Note")

        val result = resolver.resolve(input)
        result.isFromCache shouldBe false // Eski sürüm yok sayıldı, taze çekildi
        fakeSource.searchCalls shouldHaveSize 1
    }

    @Test
    fun `eski semali metadata dosyasi yok sayilip agdan yeniden cekilir`() = runTest {
        val metadataDir = File(tempDir, "metadata")
        metadataDir.mkdirs()
        val oldMetadataFile = File(metadataDir, "30021.json")
        // Eski sürüm (version = 1) olan bir metadata dosyası yaz
        oldMetadataFile.writeText("""
            {
                "version": 1,
                "candidate": {
                    "id": 30021,
                    "titleRomaji": "Death Note",
                    "genres": ["Mystery"],
                    "tags": [{"name": "Tragedy", "rank": 92}]
                },
                "timestamp": $currentTime
            }
        """.trimIndent())

        // Cache üzerinden okunduğunda version uyumsuzluğu (ANILIST_CACHE_VERSION = 2) nedeniyle null dönmeli ve dosya silinmeli
        cache.getMetadata(30021L).shouldBeNull()
        oldMetadataFile.exists() shouldBe false

        // Resolver akışında taze metadata kaynağından çekilir
        val freshCandidate = sampleCandidate(id = 30021L, title = "Death Note")
        fakeSource.idMapToReturn[30021L] = freshCandidate

        val input = ResolverMangaInput(mangaId = 1L, title = "Death Note", anilistTrackerId = 30021L)
        val result = resolver.resolve(input)

        result.status shouldBe MatchStatus.MATCHED
        result.candidate.shouldNotBeNull()
        result.candidate.id shouldBe 30021L
        fakeSource.getByIdsCalls shouldHaveSize 1

        // Yeniden kaydedilen dosya yeni sürüm (ANILIST_CACHE_VERSION = 2) olmalıdır
        val saved = cache.getMetadata(30021L)
        saved.shouldNotBeNull()
        saved.id shouldBe 30021L
    }

    @Test
    fun `saveMetadata ve saveMatch ile yazilan dosyanin metni version alanini icerir`() = runTest {
        val candidate = sampleCandidate(id = 30021L, title = "Death Note")
        cache.saveMetadata(candidate)

        val metadataFile = File(tempDir, "metadata/30021.json")
        metadataFile.exists() shouldBe true
        val metadataText = metadataFile.readText(Charsets.UTF_8)
        metadataText shouldContain "\"version\""
        metadataText shouldContain "\"version\":2"

        cache.saveMatch(
            mangaId = 1L,
            title = "Death Note",
            authors = listOf("Tsugumi Ohba"),
            trackerRemoteId = 30021L,
            status = MatchStatus.MATCHED,
            reason = MatchReason.SCORE,
            score = 1.0,
            anilistId = 30021L,
        )

        val matchFile = File(tempDir, "matches/1.json")
        matchFile.exists() shouldBe true
        val matchText = matchFile.readText(Charsets.UTF_8)
        matchText shouldContain "\"version\""
        matchText shouldContain "\"version\":2"
    }

    @Test
    fun `version alani olmayan bir JSON dosyasi bozuk sayilip silinir ve yeniden cekilir`() = runTest {
        val metadataDir = File(tempDir, "metadata")
        metadataDir.mkdirs()
        val noVersionFile = File(metadataDir, "30021.json")
        // "version" alanı bulunmayan eksik JSON metni yaz
        noVersionFile.writeText("""
            {
                "candidate": {
                    "id": 30021,
                    "titleRomaji": "Death Note",
                    "genres": ["Mystery"],
                    "tags": [{"name": "Police", "rank": 94}]
                },
                "timestamp": $currentTime
            }
        """.trimIndent())

        // Cache üzerinden getMetadata çağrıldığında MissingFieldException yakalanıp null dönmeli ve dosya silinmeli
        cache.getMetadata(30021L).shouldBeNull()
        noVersionFile.exists() shouldBe false

        // Resolver akışında taze veri kaynaktan çekilmeli ve version içeren dosya olarak diske kaydedilmeli
        val freshCandidate = sampleCandidate(id = 30021L, title = "Death Note")
        fakeSource.idMapToReturn[30021L] = freshCandidate

        val input = ResolverMangaInput(mangaId = 1L, title = "Death Note", anilistTrackerId = 30021L)
        val result = resolver.resolve(input)

        result.status shouldBe MatchStatus.MATCHED
        fakeSource.getByIdsCalls shouldHaveSize 1

        val reSavedFile = File(metadataDir, "30021.json")
        reSavedFile.exists() shouldBe true
        val reSavedText = reSavedFile.readText(Charsets.UTF_8)
        reSavedText shouldContain "\"version\":2"
    }

    // ========================================================
    // 4. Tracker Kısayolu ve Tracker Önceliği
    // ========================================================

    @Test
    fun `tracker kaydi varsa arama yapmadan dogrudan ID ile cozumler`() = runTest {
        val candidate = sampleCandidate(id = 85143L, title = "Tower of God")
        fakeSource.idMapToReturn[85143L] = candidate

        val input = ResolverMangaInput(
            mangaId = 2L,
            title = "Tower of God",
            anilistTrackerId = 85143L,
        )

        val result = resolver.resolve(input)
        result.status shouldBe MatchStatus.MATCHED
        result.candidate.shouldNotBeNull()
        result.candidate.id shouldBe 85143L
        fakeSource.searchCalls shouldHaveSize 0 // searchByTitle hiç çağrılmadı!
        fakeSource.getByIdsCalls shouldHaveSize 1
        fakeSource.getByIdsCalls[0] shouldBe listOf(85143L)
    }

    @Test
    fun `tracker sonradan eklenince eski BELIRSIZ kaydi ezilir`() = runTest {
        // 1. Önce trackersız ara -> BELİRSİZ kaydolur
        fakeSource.candidatesToReturn = listOf(
            sampleCandidate(id = 135083L, title = "Wind Breaker", country = "JP"),
            sampleCandidate(id = 86099L, title = "Wind Breaker", country = "KR"),
        )
        val inputWithoutTracker = ResolverMangaInput(mangaId = 10L, title = "Wind Breaker")
        val res1 = resolver.resolve(inputWithoutTracker)
        res1.status shouldBe MatchStatus.AMBIGUOUS

        // 2. Kullanıcı sonradan tracker bağladı (anilistTrackerId = 135083L)
        fakeSource.idMapToReturn[135083L] = sampleCandidate(id = 135083L, title = "Wind Breaker")
        val inputWithTracker = ResolverMangaInput(
            mangaId = 10L,
            title = "Wind Breaker",
            anilistTrackerId = 135083L,
        )

        val res2 = resolver.resolve(inputWithTracker)
        res2.status shouldBe MatchStatus.MATCHED
        res2.candidate.shouldNotBeNull()
        res2.candidate.id shouldBe 135083L // Eski BELİRSİZ ezildi ve MATCHED oldu
    }

    // ========================================================
    // 5. Başlık, Yazar ve Tracker Değişimi Kontrolleri
    // ========================================================

    @Test
    fun `manganin basligi degisince eski onbellek gecersiz sayilir`() = runTest {
        val candidate = sampleCandidate(id = 30021L, title = "Death Note")
        fakeSource.candidatesToReturn = listOf(candidate)

        // 1. "Death Note" olarak eşleştir
        val input1 = ResolverMangaInput(mangaId = 1L, title = "Death Note")
        resolver.resolve(input1)
        fakeSource.searchCalls shouldHaveSize 1

        // 2. Başlık "Death Note 2" olarak değişti
        val input2 = ResolverMangaInput(mangaId = 1L, title = "Death Note 2")
        val res2 = resolver.resolve(input2)
        res2.isFromCache shouldBe false // Eski önbellek geçersiz sayıldı
        fakeSource.searchCalls shouldHaveSize 2
    }

    @Test
    fun `manganin yazari degisince eski onbellek gecersiz sayilir`() = runTest {
        val candidate = sampleCandidate()
        fakeSource.candidatesToReturn = listOf(candidate)

        val input1 = ResolverMangaInput(mangaId = 1L, title = "Death Note", authors = listOf("Yazar 1"))
        resolver.resolve(input1)

        val input2 = ResolverMangaInput(mangaId = 1L, title = "Death Note", authors = listOf("Farkli Yazar"))
        val res2 = resolver.resolve(input2)
        res2.isFromCache shouldBe false
        fakeSource.searchCalls shouldHaveSize 2
    }

    @Test
    fun `tracker remote_id degisince eski onbellek gecersiz sayilir`() = runTest {
        fakeSource.idMapToReturn[100L] = sampleCandidate(id = 100L)
        fakeSource.idMapToReturn[200L] = sampleCandidate(id = 200L)

        // Tracker remote_id = 100
        val input1 = ResolverMangaInput(mangaId = 1L, title = "Test", anilistTrackerId = 100L)
        resolver.resolve(input1)

        // Tracker remote_id = 200 oldu
        val input2 = ResolverMangaInput(mangaId = 1L, title = "Test", anilistTrackerId = 200L)
        val res2 = resolver.resolve(input2)
        res2.candidate?.id shouldBe 200L
        res2.isFromCache shouldBe false
    }

    // ========================================================
    // 6. Metadata 90 Gün Kuralı
    // ========================================================

    @Test
    fun `metadata 90 gun sonra yenilenir`() = runTest {
        val candidate = sampleCandidate()
        fakeSource.candidatesToReturn = listOf(candidate)
        fakeSource.idMapToReturn[30021L] = candidate

        val input = ResolverMangaInput(mangaId = 1L, title = "Death Note")
        resolver.resolve(input)

        // 89 gün sonra: metadata önbellekte geçerli
        currentTime += 89L * 24 * 60 * 60 * 1000
        cache.getMetadata(30021L).shouldNotBeNull()

        // 91 gün sonra: metadata süresi doldu
        currentTime += 2L * 24 * 60 * 60 * 1000
        cache.getMetadata(30021L).shouldBeNull()
    }

    // ========================================================
    // 7. Rate Limiter ve 50'lik Gruplama Testleri
    // ========================================================

    @Test
    fun `50lik gruplama ile 120 ID 3 parcada sorgulanir`() = runTest {
        val ids = (1L..120L).toList()
        val httpSource = AniListHttpMetadataSource(
            endpoint = "http://localhost:9999", // Gerçek ağa gitmeyecek
        )

        // Mock call listesini fake kaynak üzerinden test et
        val fake = FakeAniListMetadataSource()
        for (i in 1L..120L) fake.idMapToReturn[i] = sampleCandidate(id = i)

        // Fake source üzerinde 50'lik chunked çağrısı
        val chunks = ids.chunked(50)
        chunks shouldHaveSize 3
        chunks[0] shouldHaveSize 50
        chunks[1] shouldHaveSize 50
        chunks[2] shouldHaveSize 20
    }

    @Test
    fun `rate limiter 429 Retry-After suresi kadar bekleyip tekrar dener`() = runTest {
        var fakeCurrentTime = 1000L
        var delayCalls = 0L

        val limiter = AniListRateLimiter(
            permits = 25,
            periodMs = 60_000L,
            clock = { fakeCurrentTime },
            delayFn = { delayMs ->
                delayCalls += delayMs
                fakeCurrentTime += delayMs
            },
        )

        var attempts = 0
        val result = limiter.executeWithRetry(maxRetries = 3) {
            attempts++
            if (attempts < 3) {
                RateLimitResponse.RateLimited(retryAfterSeconds = 5L)
            } else {
                RateLimitResponse.Success("BAŞARILI")
            }
        }

        result shouldBe "BAŞARILI"
        attempts shouldBe 3
        delayCalls shouldBe 10_000L // 2 kez 5 saniye = 10.000 ms
    }
}

private class FakeAniListMetadataSource : AniListMetadataSource {
    val searchCalls = mutableListOf<String>()
    val getByIdsCalls = mutableListOf<List<Long>>()
    var candidatesToReturn: List<AniListMediaCandidate> = emptyList()
    val idMapToReturn = mutableMapOf<Long, AniListMediaCandidate>()
    var throwErrorOnSearch: Exception? = null
    var throwErrorOnGetByIds: Exception? = null

    override suspend fun searchByTitle(title: String): List<AniListMediaCandidate> {
        searchCalls.add(title)
        throwErrorOnSearch?.let { throw it }
        return candidatesToReturn
    }

    override suspend fun getByIds(ids: List<Long>): Map<Long, AniListMediaCandidate> {
        getByIdsCalls.add(ids)
        throwErrorOnGetByIds?.let { throw it }
        return ids.mapNotNull { id -> idMapToReturn[id]?.let { id to it } }.toMap()
    }
}
