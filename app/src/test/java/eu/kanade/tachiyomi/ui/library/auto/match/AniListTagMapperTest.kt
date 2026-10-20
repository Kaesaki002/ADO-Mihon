package eu.kanade.tachiyomi.ui.library.auto.match

import eu.kanade.tachiyomi.ui.library.auto.CategoryLevel
import eu.kanade.tachiyomi.ui.library.auto.TagDictionary
import eu.kanade.tachiyomi.ui.library.auto.generateAutoCategories
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.InputStream

class AniListTagMapperTest {

    private fun loadResourceCandidates(filename: String): List<AniListMediaCandidate> {
        val path = "/anilist/$filename"
        val stream: InputStream = javaClass.getResourceAsStream(path)
            ?: javaClass.classLoader?.getResourceAsStream("anilist/$filename")
            ?: throw IllegalStateException("Test kaynağı bulunamadı: $path")

        val jsonText = stream.bufferedReader().use { it.readText() }
        val dtos = AniListJsonParser.decodeFromString<List<AniListMediaDto>>(jsonText)
        return dtos.map { it.toCandidate() }
    }

    @Test
    fun `wind breaker KR countryOfOrigin Manhwa kokenine donusturulur`() {
        val candidates = loadResourceCandidates("wind_breaker.json")
        val krCandidate = candidates.first { it.id == 86099L && it.countryOfOrigin == "KR" }

        val tagInput = mapToMangaTagInput(mangaId = 100L, candidate = krCandidate)

        val genres = tagInput.genres.shouldNotBeNull()
        genres shouldContain "Manhwa"
        genres shouldNotContain "Manga"

        // Otomatik kategori motoruna 3 kopya verilerek kategori üretimi doğrulanır
        val mangas = listOf(
            tagInput.copy(id = 1L),
            tagInput.copy(id = 2L),
            tagInput.copy(id = 3L),
        )
        val result = generateAutoCategories(mangas, minMangaCount = 3)
        val originCategories = result.categories.filter { it.level == CategoryLevel.ORIGIN }
        originCategories.map { it.name } shouldBe listOf("Manhwa")
    }

    @Test
    fun `wind breaker JP countryOfOrigin Manga kokenine donusturulur`() {
        val candidates = loadResourceCandidates("wind_breaker.json")
        val jpCandidate = candidates.first { it.id == 135083L && it.countryOfOrigin == "JP" }

        val tagInput = mapToMangaTagInput(mangaId = 200L, candidate = jpCandidate)

        val genres = tagInput.genres.shouldNotBeNull()
        genres shouldContain "Manga"
        genres shouldNotContain "Manhwa"

        val mangas = listOf(
            tagInput.copy(id = 1L),
            tagInput.copy(id = 2L),
            tagInput.copy(id = 3L),
        )
        val result = generateAutoCategories(mangas, minMangaCount = 3)
        val originCategories = result.categories.filter { it.level == CategoryLevel.ORIGIN }
        originCategories.map { it.name } shouldBe listOf("Manga")
    }

    @Test
    fun `CN ve TW countryOfOrigin Manhua kokenine donusturulur diger ulkeler koken eklemez`() {
        mapCountryToOrigin("CN") shouldBe "Manhua"
        mapCountryToOrigin("cn") shouldBe "Manhua"
        mapCountryToOrigin("TW") shouldBe "Manhua"
        mapCountryToOrigin("tw") shouldBe "Manhua"

        // Tanımsız veya diğer ülkeler
        mapCountryToOrigin("US") shouldBe null
        mapCountryToOrigin("FR") shouldBe null
        mapCountryToOrigin("") shouldBe null
        mapCountryToOrigin("   ") shouldBe null
        mapCountryToOrigin(null) shouldBe null

        val cnCandidate = AniListMediaCandidate(id = 1L, countryOfOrigin = "CN", genres = listOf("Action"))
        val tagInputCN = mapToMangaTagInput(1L, cnCandidate)
        val genresCN = tagInputCN.genres.shouldNotBeNull()
        genresCN shouldContain "Manhua"

        val usCandidate = AniListMediaCandidate(id = 2L, countryOfOrigin = "US", genres = listOf("Action"))
        val tagInputUS = mapToMangaTagInput(2L, usCandidate)
        val genresUS = tagInputUS.genres.shouldNotBeNull()
        genresUS shouldNotContain "Manga"
        genresUS shouldNotContain "Manhwa"
        genresUS shouldNotContain "Manhua"
    }

    @Test
    fun `rank 60 ve uzeri tagler eklenir 60 alti elenir`() {
        val candidates = loadResourceCandidates("wind_breaker.json")
        val jpCandidate = candidates.first { it.id == 135083L }

        val tagInput = mapToMangaTagInput(mangaId = 1L, candidate = jpCandidate, minTagRank = 60)
        val genres = tagInput.genres.shouldNotBeNull()

        // Rank >= 60 olan etiketler eklenmeli:
        // Delinquents (93), Shounen (81), Martial Arts (79), School (60), Crime (60), LGBTQ+ Themes (60)
        genres shouldContain "Delinquents"
        genres shouldContain "Shounen"
        genres shouldContain "Martial Arts"
        genres shouldContain "School"
        genres shouldContain "Crime"
        genres shouldContain "LGBTQ+ Themes"

        // Rank < 60 olan etiketler elenmeli:
        // Crossdressing (56), Transgender (42), Heterosexual (40), Tragedy (33)
        genres shouldNotContain "Crossdressing"
        genres shouldNotContain "Transgender"
        genres shouldNotContain "Heterosexual"
        genres shouldNotContain "Tragedy"
    }

    @Test
    fun `min rank 60 ve 70 karsilastirmasinda Ruri Dragon Shounen etiketi 60ta dahil edilir 70te elenir`() {
        val candidates = loadResourceCandidates("ruri_dragon.json")
        // Ruri Dragon one-shot (id: 127750) adayı için JSON'daki Shounen etiketi rank 66'dır
        val ruriDragonOneShot = candidates.first { it.id == 127750L }

        val tagInput60 = mapToMangaTagInput(mangaId = 1L, candidate = ruriDragonOneShot, minTagRank = 60)
        val tagInput70 = mapToMangaTagInput(mangaId = 1L, candidate = ruriDragonOneShot, minTagRank = 70)

        val genres60 = tagInput60.genres.shouldNotBeNull()
        val genres70 = tagInput70.genres.shouldNotBeNull()

        // Eşik 60 iken rank 66 korunur
        genres60 shouldContain "Shounen"

        // Eşik 70 yapıldığında rank 66 elenir
        genres70 shouldNotContain "Shounen"
    }

    @Test
    fun `eslesmemis null metadata icin etiket listesi bos doner`() {
        val tagInput = mapToMangaTagInput(mangaId = 42L, candidate = null)

        tagInput.id shouldBe 42L
        val genres = tagInput.genres.shouldNotBeNull()
        genres.shouldBeEmpty()

        val result = generateAutoCategories(listOf(tagInput), minMangaCount = 1)
        result.categories.shouldBeEmpty()
        result.unrecognizedTags.shouldBeEmpty()
    }

    @Test
    fun `taninmayan etiketler generateAutoCategories unrecognizedTags raporuna duser`() {
        val candidates = loadResourceCandidates("wind_breaker.json")
        val jpCandidate = candidates.first { it.id == 135083L }

        val tagInput = mapToMangaTagInput(mangaId = 1L, candidate = jpCandidate, minTagRank = 60)

        // "Delinquents", "Gangs", "Found Family" gibi sözlükte olmayan etiketler raporlanmalıdır
        val result = generateAutoCategories(listOf(tagInput), minMangaCount = 1)
        val unrecognizedNames = result.unrecognizedTags.map { it.tag }

        unrecognizedNames shouldContain "Delinquents"
        unrecognizedNames shouldContain "Gangs"
        unrecognizedNames shouldContain "Found Family"
    }

    @Test
    fun `yeni eklenen sozluk tanimlari Witch Ecchi Mahou Shoujo Harem Zombies dogru eslesir`() {
        val customCandidate = AniListMediaCandidate(
            id = 999L,
            countryOfOrigin = "JP",
            genres = listOf("Ecchi", "Mahou Shoujo"),
            tags = listOf(
                AniListTag(name = "Witch", rank = 85),
                AniListTag(name = "Harem", rank = 80),
                AniListTag(name = "Zombie", rank = 75),
            ),
        )

        val tagInput = customCandidate.toMangaTagInput(mangaId = 1L)
        val mangas = listOf(tagInput.copy(id = 1L), tagInput.copy(id = 2L), tagInput.copy(id = 3L))
        val result = generateAutoCategories(mangas, minMangaCount = 3)

        val categoryNames = result.categories.map { it.name }
        categoryNames shouldContain "Ecchi"
        categoryNames shouldContain "Mahou Shoujo"
        categoryNames shouldContain "Magic" // "Witch" -> "Magic" alias'ı
        categoryNames shouldContain "Harem"
        categoryNames shouldContain "Zombies" // "Zombie" -> "Zombies" alias'ı
    }

    @Test
    fun `toplu mapAllToMangaTagInput fonksiyonu tum haritayi donusturur`() {
        val candidates = loadResourceCandidates("wind_breaker.json")
        val jpCandidate = candidates.first { it.id == 135083L }
        val krCandidate = candidates.first { it.id == 86099L }

        val map = mapOf(
            1L to jpCandidate,
            2L to krCandidate,
            3L to null,
        )

        val results = mapAllToMangaTagInput(map)
        results.size shouldBe 3

        results[0].genres.shouldNotBeNull() shouldContain "Manga"
        results[1].genres.shouldNotBeNull() shouldContain "Manhwa"
        results[2].genres.shouldNotBeNull().shouldBeEmpty()
    }

    @Test
    fun `gercek smoke_test yanitinda spoiler etiketler elenir spoiler olmayanlar kalir`() {
        val candidates = loadResourceCandidates("smoke_test_spoilers.json")
        val deathNote = candidates.first { it.id == 30021L }
        val windBreaker = candidates.first { it.id == 135083L }

        // 1. Death Note Kontrolü:
        // "Tragedy" (rank 92) hem isGeneralSpoiler=true hem isMediaSpoiler=true -> elenmeli
        // "Detective" (96), "Police" (94), "Anti-Hero" (93) spoiler=false -> kalmalı
        // "Drama", "Mystery" gibi genres alanları spoiler filtresinden etkilenmemeli
        val deathNoteInput = mapToMangaTagInput(mangaId = 1L, candidate = deathNote, minTagRank = 60)
        val dnGenres = deathNoteInput.genres.shouldNotBeNull()

        dnGenres shouldNotContain "Tragedy"
        dnGenres shouldContain "Detective"
        dnGenres shouldContain "Police"
        dnGenres shouldContain "Anti-Hero"
        dnGenres shouldContain "Drama"
        dnGenres shouldContain "Mystery"

        // 2. Wind Breaker JP Kontrolü:
        // "Found Family" (rank 83) isMediaSpoiler=true -> elenmeli
        // "Delinquents" (93), "Male Protagonist" (92), "Shounen" (81) spoiler=false -> kalmalı
        // "Action", "Comedy" gibi genres alanları kalmalı
        val wbInput = mapToMangaTagInput(mangaId = 2L, candidate = windBreaker, minTagRank = 60)
        val wbGenres = wbInput.genres.shouldNotBeNull()

        wbGenres shouldNotContain "Found Family"
        wbGenres shouldContain "Delinquents"
        wbGenres shouldContain "Male Protagonist"
        wbGenres shouldContain "Shounen"
        wbGenres shouldContain "Action"
        wbGenres shouldContain "Comedy"
    }

    @Test
    fun `spoiler alani olmayan eski JSONlar basariyla okunur ve geriye donuk uyumludur`() {
        // wind_breaker.json dosyası eski formatta olup isGeneralSpoiler / isMediaSpoiler alanlarını içermez
        val candidates = loadResourceCandidates("wind_breaker.json")
        val jpCandidate = candidates.first { it.id == 135083L }

        // Varsayılan false değerleri ile okunmalı
        val firstTag = jpCandidate.tags.first()
        firstTag.isGeneralSpoiler shouldBe false
        firstTag.isMediaSpoiler shouldBe false

        val tagInput = mapToMangaTagInput(mangaId = 1L, candidate = jpCandidate, minTagRank = 60)
        val genres = tagInput.genres.shouldNotBeNull()

        // Eski dosyada Found Family spoiler alanı olmadığı için elenmez
        genres shouldContain "Found Family"
        genres shouldContain "Delinquents"
    }
}
