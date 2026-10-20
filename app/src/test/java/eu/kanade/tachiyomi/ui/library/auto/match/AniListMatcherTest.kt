package eu.kanade.tachiyomi.ui.library.auto.match

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.InputStream

class AniListMatcherTest {

    private fun loadResourceCandidates(filename: String): List<AniListMediaCandidate> {
        val path = "/anilist/$filename"
        val stream: InputStream = javaClass.getResourceAsStream(path)
            ?: javaClass.classLoader?.getResourceAsStream("anilist/$filename")
            ?: throw IllegalStateException("Test kaynağı bulunamadı: $path")

        val jsonText = stream.bufferedReader().use { it.readText() }
        val dtos = AniListJsonParser.decodeFromString<List<AniListMediaDto>>(jsonText)
        return dtos.map { it.toCandidate() }
    }

    // ========================================================
    // 1. Python RapidFuzz Referans Çıktılarıyla Benzerlik Testleri
    // ========================================================

    @Test
    fun `rapidfuzz referans degerleri ile ratio ve token_sort_ratio birebir eslesir`() {
        val testPairs = listOf(
            TestPair("apple", "apples", expectedRatio = 90.909091, expectedTokenSort = 90.909091),
            TestPair("death note", "death note short stories", expectedRatio = 58.823529, expectedTokenSort = 58.823529),
            TestPair("nii satoru", "satoru nii", expectedRatio = 60.000000, expectedTokenSort = 100.000000),
            TestPair("kitten", "sitting", expectedRatio = 61.538462, expectedTokenSort = 61.538462),
            TestPair("abc", "ac", expectedRatio = 80.000000, expectedTokenSort = 80.000000),
            TestPair("abc", "cba", expectedRatio = 33.333333, expectedTokenSort = 33.333333),
            TestPair("wind breaker", "wind breaker", expectedRatio = 100.000000, expectedTokenSort = 100.000000),
            TestPair("bleach", "bleach short story edition", expectedRatio = 37.500000, expectedTokenSort = 37.500000),
            TestPair("ruri dragon", "ruri dragon one shot", expectedRatio = 70.967742, expectedTokenSort = 70.967742),
            TestPair("kamen teacher", "kamen teacher black", expectedRatio = 81.250000, expectedTokenSort = 81.250000),
        )

        for (pair in testPairs) {
            val actualRatioPct = StringSimilarity.ratio(pair.s1, pair.s2) * 100.0
            val actualTokenSortPct = StringSimilarity.tokenSortRatio(pair.s1, pair.s2) * 100.0

            actualRatioPct shouldBe (pair.expectedRatio plusOrMinus 0.0001)
            actualTokenSortPct shouldBe (pair.expectedTokenSort plusOrMinus 0.0001)
        }
    }

    private data class TestPair(
        val s1: String,
        val s2: String,
        val expectedRatio: Double,
        val expectedTokenSort: Double,
    )

    // ========================================================
    // 2. Normalizasyon Testleri
    // ========================================================

    @Test
    fun `baslik normalizasyonu unicode harf ve rakamlari korur noktalama ve aksanlari temizler`() {
        StringSimilarity.normalizeTitle("Shōnen Jump 2026!") shouldBe "shonen jump 2026"
        StringSimilarity.normalizeTitle("Kıyamet Sonrası") shouldBe "kiyamet sonrasi"
        StringSimilarity.normalizeTitle("Kiyamet Sonrasi") shouldBe "kiyamet sonrasi"
        StringSimilarity.normalizeTitle("Sci-Fi / Action_Adventure") shouldBe "sci fi action adventure"
        StringSimilarity.normalizeTitle("  Çok   Boşluklu   Metin  ") shouldBe "cok bosluklu metin"
        StringSimilarity.normalizeTitle("進撃の巨人 (Attack on Titan)") shouldBe "進撃の巨人 attack on titan"
    }

    // ========================================================
    // 3. Prototip Cache JSON'ları ile Gerçek AniList Eşleşme Testleri
    // ========================================================

    @Test
    fun `Death Note tek MANGA kurali ile ONE_SHOT arasindan eslesir`() {
        val candidates = loadResourceCandidates("death_note.json")
        val input = MatchInput("Death Note")

        val result = AniListMatcher.match(input, candidates)

        result.status shouldBe MatchStatus.MATCHED
        result.reason shouldBe MatchReason.FORMAT
        result.score shouldBe (1.00 plusOrMinus 0.01)
        result.matchedCandidate.shouldNotBeNull()
        result.matchedCandidate.id shouldBe 30021L // Ana Manga
        result.matchedCandidate.format shouldBe "MANGA"
        result.secondCandidate.shouldNotBeNull()
        result.secondCandidate.id shouldBe 33645L // One Shot
    }

    @Test
    fun `Bleach tek MANGA kuralı ile ONE_SHOT arasindan eslesir`() {
        val candidates = loadResourceCandidates("bleach.json")
        val input = MatchInput("Bleach")

        val result = AniListMatcher.match(input, candidates)

        result.status shouldBe MatchStatus.MATCHED
        result.reason shouldBe MatchReason.FORMAT
        result.score shouldBe (1.00 plusOrMinus 0.01)
        result.matchedCandidate.shouldNotBeNull()
        result.matchedCandidate.id shouldBe 30012L
    }

    @Test
    fun `Ruri Dragon tek MANGA kurali ile ONE_SHOT arasindan eslesir`() {
        val candidates = loadResourceCandidates("ruri_dragon.json")
        val input = MatchInput("Ruri Dragon")

        val result = AniListMatcher.match(input, candidates)

        result.status shouldBe MatchStatus.MATCHED
        result.reason shouldBe MatchReason.FORMAT
        result.score shouldBe (1.00 plusOrMinus 0.01)
        result.matchedCandidate.shouldNotBeNull()
        result.matchedCandidate.id shouldBe 150440L // Serialized Manga
    }

    @Test
    fun `Kamen Teacher her iki aday da MANGA oldugu icin BELIRSIZ olur`() {
        val candidates = loadResourceCandidates("kamen_teacher.json")
        val input = MatchInput("Kamen Teacher")

        val result = AniListMatcher.match(input, candidates)

        result.status shouldBe MatchStatus.AMBIGUOUS
        result.reason shouldBe MatchReason.NONE
        result.score shouldBe (1.00 plusOrMinus 0.01)
        result.secondScore shouldBe (1.00 plusOrMinus 0.01)
    }

    @Test
    fun `Food Wars esik alti skora sahip oldugu icin ESLESMEDI olur`() {
        val candidates = loadResourceCandidates("food_wars.json")
        val input = MatchInput("Food Wars!")

        val result = AniListMatcher.match(input, candidates)

        result.status shouldBe MatchStatus.NOT_MATCHED
        result.score shouldBe (0.75 plusOrMinus 0.02)
    }

    @Test
    fun `Wind Breaker Nii Satoru yazari ile JP mangasina eslesir`() {
        val candidates = loadResourceCandidates("wind_breaker.json")
        val input = MatchInput(title = "Wind Breaker", authors = listOf("Nii Satoru"))

        val result = AniListMatcher.match(input, candidates)

        result.status shouldBe MatchStatus.MATCHED
        result.reason shouldBe MatchReason.AUTHOR
        result.score shouldBe (1.00 plusOrMinus 0.01)
        result.matchedCandidate.shouldNotBeNull()
        result.matchedCandidate.id shouldBe 135083L // JP manga
        result.matchedCandidate.countryOfOrigin shouldBe "JP"
    }

    @Test
    fun `Wind Breaker Jo Yongseok yazari ile KR manhwasina eslesir`() {
        val candidates = loadResourceCandidates("wind_breaker.json")
        val input = MatchInput(title = "Wind Breaker", authors = listOf("Jo Yongseok"))

        val result = AniListMatcher.match(input, candidates)

        result.status shouldBe MatchStatus.MATCHED
        result.reason shouldBe MatchReason.AUTHOR
        result.score shouldBe (1.00 plusOrMinus 0.01)
        result.matchedCandidate.shouldNotBeNull()
        result.matchedCandidate.id shouldBe 86099L // KR manhwa
        result.matchedCandidate.countryOfOrigin shouldBe "KR"
    }

    // ========================================================
    // 4. Ek Sınır Durumu Testleri
    // ========================================================

    @Test
    fun `yazarsiz Wind Breaker iki aday da MANGA oldugu icin AMBIGUOUS kalir`() {
        val candidates = loadResourceCandidates("wind_breaker.json")
        val input = MatchInput(title = "Wind Breaker", authors = emptyList())

        val result = AniListMatcher.match(input, candidates)

        result.status shouldBe MatchStatus.AMBIGUOUS
        result.reason shouldBe MatchReason.NONE
    }

    @Test
    fun `yazar verilip hicbiri eslesmezse AMBIGUOUS kalir`() {
        val candidates = loadResourceCandidates("wind_breaker.json")
        val input = MatchInput(title = "Wind Breaker", authors = listOf("Bilinmeyen Biri"))

        val result = AniListMatcher.match(input, candidates)

        result.status shouldBe MatchStatus.AMBIGUOUS
        result.reason shouldBe MatchReason.NONE
    }

    @Test
    fun `ulke ipucu KR tek adayi secer`() {
        val candidates = loadResourceCandidates("wind_breaker.json")
        val input = MatchInput(title = "Wind Breaker", country = "KR")

        val result = AniListMatcher.match(input, candidates)

        result.status shouldBe MatchStatus.MATCHED
        result.reason shouldBe MatchReason.COUNTRY
        result.matchedCandidate.shouldNotBeNull()
        result.matchedCandidate.id shouldBe 86099L
    }

    @Test
    fun `ulke ipucu JP tek adayi secer`() {
        val candidates = loadResourceCandidates("wind_breaker.json")
        val input = MatchInput(title = "Wind Breaker", country = "JP")

        val result = AniListMatcher.match(input, candidates)

        result.status shouldBe MatchStatus.MATCHED
        result.reason shouldBe MatchReason.COUNTRY
        result.matchedCandidate.shouldNotBeNull()
        result.matchedCandidate.id shouldBe 135083L
    }

    @Test
    fun `yazar listesi bosken veya virgul ile coklu yazar verildiginde cokmez ve parse edilir`() {
        MatchInput.parseAuthors(null) shouldBe emptyList()
        MatchInput.parseAuthors("") shouldBe emptyList()
        MatchInput.parseAuthors("Tsugumi Ohba, Takeshi Obata") shouldBe listOf("Tsugumi Ohba", "Takeshi Obata")
        MatchInput.parseAuthors("ONE ve Yusuke Murata") shouldBe listOf("ONE", "Yusuke Murata")
        MatchInput.parseAuthors("Writer & Artist") shouldBe listOf("Writer", "Artist")

        val candidates = loadResourceCandidates("death_note.json")
        val inputEmpty = MatchInput("Death Note", authors = emptyList())
        val result = AniListMatcher.match(inputEmpty, candidates)
        result.status shouldBe MatchStatus.MATCHED
    }
}
