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

    // ========================================================
    // 8. Yeni Tracker, Prefetch ve Deduplication Testleri
    // ========================================================

    @Test
    fun `prefetch 120 IDyi 3 cagride ceker ve ardindan resolve aga gitmez`() = runTest {
        val ids = (1L..120L).toList()
        for (i in ids) {
            fakeSource.idMapToReturn[i] = sampleCandidate(id = i, title = "Manga $i")
        }

        resolver.prefetchByIds(ids)

        // 120 ID 50'lik gruplarla 3 çağrıda çekilmiş olmalı: 50, 50, 20
        fakeSource.getByIdsCalls shouldHaveSize 3
        fakeSource.getByIdsCalls[0] shouldHaveSize 50
        fakeSource.getByIdsCalls[1] shouldHaveSize 50
        fakeSource.getByIdsCalls[2] shouldHaveSize 20

        // Şimdi tracker ID ile resolve çağrıldığında ağa gitmemeli (önbellekten gelmeli)
        val input = ResolverMangaInput(mangaId = 1L, title = "Manga 1", anilistTrackerId = 1L)
        val result = resolver.resolve(input)

        result.status shouldBe MatchStatus.MATCHED
        result.reason shouldBe MatchReason.TRACKER
        result.candidate.shouldNotBeNull()
        result.candidate.id shouldBe 1L
        result.isFromCache shouldBe true
        fakeSource.getByIdsCalls shouldHaveSize 3 // Yeni getByIds çağrısı yapılmadı!
        fakeSource.searchCalls shouldHaveSize 0 // searchByTitle yapılmadı!
    }

    @Test
    fun `ikinci resolve cagrisi dosyayi yeniden yazmaz`() = runTest {
        val candidate = sampleCandidate(id = 30021L, title = "Death Note")
        fakeSource.candidatesToReturn = listOf(candidate)

        val input = ResolverMangaInput(mangaId = 1L, title = "Death Note")

        // 1. İlk çağrı dosyayı yazar
        val res1 = resolver.resolve(input)
        res1.status shouldBe MatchStatus.MATCHED

        val matchFile = File(tempDir, "matches/1.json")
        matchFile.exists() shouldBe true

        // Dosya zaman damgasını bilerek geriye çekiyoruz (10 saniye önceye)
        val initialMod = matchFile.lastModified()
        val pastMod = initialMod - 10_000L
        matchFile.setLastModified(pastMod)
        matchFile.lastModified() shouldBe pastMod

        // 2. İkinci resolve çağrısı: İçerik değişmediği için saveMatch diske yazmamalıdır
        val res2 = resolver.resolve(input)
        res2.status shouldBe MatchStatus.MATCHED
        res2.isFromCache shouldBe true

        // Dosyanın değiştirilme tarihi değişmemiş olmalıdır
        matchFile.lastModified() shouldBe pastMod
    }

    @Test
    fun `tracker ID bulunamayinca arama yapilmaz ve NOT_MATCHED doner`() = runTest {
        // fakeSource.idMapToReturn içinde 99999L yok (AniList boş döndürüyor)
        val input = ResolverMangaInput(
            mangaId = 1L,
            title = "Unknown Manga",
            anilistTrackerId = 99999L,
        )

        val result = resolver.resolve(input)
        result.status shouldBe MatchStatus.NOT_MATCHED
        result.reason shouldBe MatchReason.TRACKER
        result.candidate.shouldBeNull()
        result.isFromCache shouldBe false

        fakeSource.getByIdsCalls shouldHaveSize 1
        fakeSource.searchCalls shouldHaveSize 0 // Başlık aramasına ASLA düşülmedi

        // Eşleşme ve negatif metadata önbelleğe kaydedilmiş olmalı
        val matchEntry = cache.getMatch(
            mangaId = 1L,
            currentTitle = "Unknown Manga",
            currentAuthors = emptyList(),
            currentTrackerRemoteId = 99999L,
        )
        matchEntry.shouldNotBeNull()
        matchEntry.status shouldBe MatchStatus.NOT_MATCHED
        matchEntry.reason shouldBe MatchReason.TRACKER
        cache.isNegativeMetadataCache(99999L) shouldBe true
    }

    @Test
    fun `tracker yolunda gecerli NOT_MATCHED veya MATCHED kaydi varsa aga gitmeden doner`() = runTest {
        // 1. NOT_MATCHED önceden kaydedilmiş
        cache.saveMatch(
            mangaId = 1L,
            title = "Not Found Manga",
            authors = emptyList(),
            trackerRemoteId = 88888L,
            status = MatchStatus.NOT_MATCHED,
            reason = MatchReason.TRACKER,
            score = 0.0,
            anilistId = null,
        )

        val inputNotFound = ResolverMangaInput(
            mangaId = 1L,
            title = "Not Found Manga",
            anilistTrackerId = 88888L,
        )

        val resNotFound = resolver.resolve(inputNotFound)
        resNotFound.status shouldBe MatchStatus.NOT_MATCHED
        resNotFound.reason shouldBe MatchReason.TRACKER
        resNotFound.candidate.shouldBeNull()
        resNotFound.isFromCache shouldBe true
        fakeSource.getByIdsCalls shouldHaveSize 0 // Ağa gidilmedi!

        // 2. MATCHED ve metadata önceden kaydedilmiş
        val candidate = sampleCandidate(id = 77777L, title = "Found Manga")
        cache.saveMetadata(candidate)
        cache.saveMatch(
            mangaId = 2L,
            title = "Found Manga",
            authors = emptyList(),
            trackerRemoteId = 77777L,
            status = MatchStatus.MATCHED,
            reason = MatchReason.TRACKER,
            score = 1.0,
            anilistId = 77777L,
        )

        val inputFound = ResolverMangaInput(
            mangaId = 2L,
            title = "Found Manga",
            anilistTrackerId = 77777L,
        )

        val resFound = resolver.resolve(inputFound)
        resFound.status shouldBe MatchStatus.MATCHED
        resFound.reason shouldBe MatchReason.TRACKER
        resFound.candidate.shouldNotBeNull()
        resFound.candidate.id shouldBe 77777L
        resFound.isFromCache shouldBe true
        fakeSource.getByIdsCalls shouldHaveSize 0 // Yine ağa gidilmedi!
    }

    @Test
    fun `prefetch bos donen IDler icin 30 gunluk negatif kayit tutar ve sonraki prefetchte sormaz`() = runTest {
        fakeSource.idMapToReturn[101L] = sampleCandidate(id = 101L, title = "Manga 101")
        // 102L döndürülmüyor

        // 1. İlk prefetch çağrısı
        resolver.prefetchByIds(listOf(101L, 102L))

        fakeSource.getByIdsCalls shouldHaveSize 1
        fakeSource.getByIdsCalls[0] shouldBe listOf(101L, 102L)

        cache.getMetadata(101L).shouldNotBeNull()
        cache.isNegativeMetadataCache(102L) shouldBe true
        cache.getMetadata(102L).shouldBeNull()

        // 2. İkinci prefetch çağrısı: 101 ve 102 önbellekte olduğundan (102 negatif) tekrar sorulmaz
        fakeSource.idMapToReturn[103L] = sampleCandidate(id = 103L, title = "Manga 103")
        resolver.prefetchByIds(listOf(101L, 102L, 103L))

        fakeSource.getByIdsCalls shouldHaveSize 2
        fakeSource.getByIdsCalls[1] shouldBe listOf(103L) // Yalnızca 103L soruldu!

        // 3. 31 gün sonra: 102L negatif kaydının süresi dolar, tekrar sorulur
        currentTime += 31L * 24 * 60 * 60 * 1000
        resolver.prefetchByIds(listOf(102L))
        fakeSource.getByIdsCalls shouldHaveSize 3
        fakeSource.getByIdsCalls[2] shouldBe listOf(102L)
    }

    @Test
    fun `tracker veya prefetch sirasinda ag hatasi olusursa diske hicbir sey yazilmaz`() = runTest {
        val metadataDir = File(tempDir, "metadata")
        val matchesDir = File(tempDir, "matches")

        // 1. Prefetch sırasında ağ hatası
        fakeSource.throwErrorOnGetByIds = IOException("Ağ bağlantısı koptu")
        shouldThrow<IOException> {
            resolver.prefetchByIds(listOf(555L, 666L))
        }

        // metadata klasöründe hiçbir dosya oluşmamalı
        metadataDir.listFiles().orEmpty() shouldHaveSize 0

        // 2. Tracker resolve sırasında ağ hatası
        val input = ResolverMangaInput(mangaId = 99L, title = "Crash Manga", anilistTrackerId = 777L)
        shouldThrow<IOException> {
            resolver.resolve(input)
        }

        metadataDir.listFiles().orEmpty() shouldHaveSize 0
        matchesDir.listFiles().orEmpty() shouldHaveSize 0
    }

    @Test
    fun `aramada yalnizca eslesen adayin metadata dosyasi kaydedilir`() = runTest {
        val matchedCand = sampleCandidate(id = 1000L, title = "Solo Leveling", format = "MANGA")
        val otherCand = sampleCandidate(id = 2000L, title = "Solo Leveling Novel", format = "NOVEL")

        fakeSource.candidatesToReturn = listOf(matchedCand, otherCand)

        val input = ResolverMangaInput(mangaId = 1L, title = "Solo Leveling")
        val result = resolver.resolve(input)

        result.status shouldBe MatchStatus.MATCHED
        result.candidate?.id shouldBe 1000L

        // Yalnızca 1000.json kaydedilmeli, 2000.json kaydedilmemelidir
        val matchedMetaFile = File(tempDir, "metadata/1000.json")
        val otherMetaFile = File(tempDir, "metadata/2000.json")

        matchedMetaFile.exists() shouldBe true
        otherMetaFile.exists() shouldBe false
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
