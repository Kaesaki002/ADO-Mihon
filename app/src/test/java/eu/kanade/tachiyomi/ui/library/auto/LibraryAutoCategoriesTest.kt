package eu.kanade.tachiyomi.ui.library.auto

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryAutoCategoriesTest {

    @Test
    fun `aksan ve yazim varyasyonlari tek bir kategoriye normalize edilir`() {
        // "Shounen", "Shōnen" (NFD ile ō -> o) ve "shonen" tek "Shounen" kategorisinde toplanmalı
        val mangas = listOf(
            MangaTagInput(1L, listOf("Shounen")),
            MangaTagInput(2L, listOf("Shōnen")),
            MangaTagInput(3L, listOf("shonen")),
            MangaTagInput(4L, listOf("SHOUNEN")),
        )

        val result = generateAutoCategories(mangas, minMangaCount = 3)

        result.categories shouldHaveSize 1
        val category = result.categories.first()
        category.name shouldBe "Shounen"
        category.level shouldBe CategoryLevel.DEMOGRAPHIC
        category.mangaIds shouldContainExactly setOf(1L, 2L, 3L, 4L)
        result.unrecognizedTags.shouldBeEmpty()
    }

    @Test
    fun `noktalama ve bosluk varyasyonlari dogru eslesir`() {
        val mangas = listOf(
            MangaTagInput(1L, listOf("Sci-Fi")),
            MangaTagInput(2L, listOf("sci fi")),
            MangaTagInput(3L, listOf("Science Fiction")),
        )

        val result = generateAutoCategories(mangas, minMangaCount = 3)

        result.categories shouldHaveSize 1
        result.categories.first().name shouldBe "Sci-Fi"
        result.categories.first().mangaIds shouldContainExactly setOf(1L, 2L, 3L)
    }

    @Test
    fun `turkce noktasiz i ve i harfleri ayni tanima eslesir`() {
        // "Kıyamet Sonrası" ve "Kiyamet Sonrasi" aynı "kiyametsonrasi" anahtarına normalize olur
        TagNormalizer.normalize("Kıyamet Sonrası") shouldBe "kiyametsonrasi"
        TagNormalizer.normalize("Kiyamet Sonrasi") shouldBe "kiyametsonrasi"

        // Her ikisi de DEFAULT_TAG_DEFINITIONS içindeki "Post-Apocalyptic" tanımının alias'ıdır
        val mangas = listOf(
            MangaTagInput(1L, listOf("Kıyamet Sonrası")),
            MangaTagInput(2L, listOf("Kiyamet Sonrasi")),
            MangaTagInput(3L, listOf("Post-Apocalyptic")),
        )

        val result = generateAutoCategories(mangas, minMangaCount = 3)

        result.categories shouldHaveSize 1
        val category = result.categories.first()
        category.name shouldBe "Post-Apocalyptic"
        category.level shouldBe CategoryLevel.THEME
        category.mangaIds shouldContainExactly setOf(1L, 2L, 3L)
    }

    @Test
    fun `varsayilan sozlukteki hicbir iki farkli tanim ayni normalize anahtara dusmez`() {
        val seenKeys = mutableMapOf<String, String>() // normalizedKey -> canonicalName
        for (def in DEFAULT_TAG_DEFINITIONS) {
            val allNames = listOf(def.canonicalName) + def.aliases
            for (name in allNames) {
                val key = TagNormalizer.normalize(name)
                val existing = seenKeys[key]
                if (existing != null && existing != def.canonicalName) {
                    throw AssertionError(
                        "Sözlükte çakışma: '$name' (${def.canonicalName}) ile '$existing' aynı normalize anahtara ('$key') düşüyor.",
                    )
                }
                seenKeys[key] = def.canonicalName
            }
        }
    }

    @Test
    fun `japanese korean chinese artik koken kategorilerine eslesmez`() {
        val mangas = listOf(
            MangaTagInput(1L, listOf("Japanese", "Korean", "Chinese")),
        )
        val result = generateAutoCategories(mangas, minMangaCount = 1)
        result.categories.shouldBeEmpty()
        result.unrecognizedTags.map { it.tag } shouldContainExactly listOf("Chinese", "Japanese", "Korean")
    }

    @Test
    fun `yeni aliaslar school sport monster dogru kategorilere eslesir`() {
        val mangas = listOf(
            MangaTagInput(1L, listOf("School", "Sport", "Monster")),
            MangaTagInput(2L, listOf("School Life", "Sports", "Monsters")),
        )
        val result = generateAutoCategories(mangas, minMangaCount = 2)
        result.categories.map { it.name } shouldContainExactly listOf("Sports", "Monsters", "School Life")
    }

    @Test
    fun `disaridan hazir TagDictionary verilebilir`() {
        val customDict = TagDictionary(
            listOf(TagDefinition("OzelKategori", CategoryLevel.GENRE)),
        )
        val mangas = listOf(
            MangaTagInput(1L, listOf("OzelKategori")),
            MangaTagInput(2L, listOf("OzelKategori")),
        )
        val result = generateAutoCategories(mangas, minMangaCount = 2, tagDictionary = customDict)
        result.categories shouldHaveSize 1
        result.categories.first().name shouldBe "OzelKategori"
    }

    @Test
    fun `bilinmeyen etiketler kategori olusturmaz ve raporda listelenir`() {
        val mangas = listOf(
            MangaTagInput(1L, listOf("BilinmeyenTurA", "Action")),
            MangaTagInput(2L, listOf("BilinmeyenTurA", "BilinmeyenTurB")),
            MangaTagInput(3L, listOf("BilinmeyenTurA", "Action")),
            MangaTagInput(4L, listOf("Action")),
        )

        val result = generateAutoCategories(mangas, minMangaCount = 3)

        // Action kategorisi 3 mangada geçtiği için oluşur
        result.categories shouldHaveSize 1
        result.categories.first().name shouldBe "Action"
        result.categories.first().mangaIds shouldContainExactly setOf(1L, 3L, 4L)

        // Bilinmeyen etiketler kategoriye dönüşmez, raporda sayılır
        result.unrecognizedTags shouldHaveSize 2
        result.unrecognizedTags[0] shouldBe UnrecognizedTag("BilinmeyenTurA", count = 3)
        result.unrecognizedTags[1] shouldBe UnrecognizedTag("BilinmeyenTurB", count = 1)
    }

    @Test
    fun `esik altindaki kategoriler elenir`() {
        val mangas = listOf(
            MangaTagInput(1L, listOf("Action", "Romance")),
            MangaTagInput(2L, listOf("Action", "Romance")),
            MangaTagInput(3L, listOf("Action")),
        )

        // Eşik 3: Action (3 manga) oluşur, Romance (2 manga) elenir
        val resultThreshold3 = generateAutoCategories(mangas, minMangaCount = 3)
        resultThreshold3.categories shouldHaveSize 1
        resultThreshold3.categories.first().name shouldBe "Action"

        // Eşik 2 yapıldığında: Hem Action (3) hem Romance (2) oluşur
        val resultThreshold2 = generateAutoCategories(mangas, minMangaCount = 2)
        resultThreshold2.categories shouldHaveSize 2
        resultThreshold2.categories.map { it.name } shouldContainExactly listOf("Action", "Romance")
    }

    @Test
    fun `seviye hiyerarsisine ve manga sayisina gore siralanir`() {
        // Seviyeler: ORIGIN > FORMAT > DEMOGRAPHIC > GENRE > THEME
        val mangas = listOf(
            MangaTagInput(1L, listOf("Manga", "Webtoon", "Shounen", "Action", "Comedy", "Isekai")),
            MangaTagInput(2L, listOf("Manga", "Webtoon", "Shounen", "Action", "Comedy", "Isekai")),
            MangaTagInput(3L, listOf("Manga", "Webtoon", "Shounen", "Action", "Isekai")),
            MangaTagInput(4L, listOf("Action")), // Action toplam 4 manga, Comedy toplam 2 manga
        )

        val result = generateAutoCategories(mangas, minMangaCount = 2)

        val categoryOrder = result.categories.map { it.name to it.level }
        categoryOrder shouldContainExactly listOf(
            "Manga" to CategoryLevel.ORIGIN,
            "Webtoon" to CategoryLevel.FORMAT,
            "Shounen" to CategoryLevel.DEMOGRAPHIC,
            "Action" to CategoryLevel.GENRE, // Genre içinde 4 manga
            "Comedy" to CategoryLevel.GENRE, // Genre içinde 2 manga
            "Isekai" to CategoryLevel.THEME,
        )
    }

    @Test
    fun `koken seviyesi sadece manga manhwa manhua icerir webtoon format seviyesindedir`() {
        val mangas = listOf(
            MangaTagInput(1L, listOf("Manga", "Manhwa", "Webtoon")),
            MangaTagInput(2L, listOf("Manga", "Manhwa", "Webtoon")),
            MangaTagInput(3L, listOf("Manga", "Manhwa", "Webtoon")),
        )

        val result = generateAutoCategories(mangas, minMangaCount = 3)

        val levels = result.categories.associate { it.name to it.level }
        levels["Manga"] shouldBe CategoryLevel.ORIGIN
        levels["Manhwa"] shouldBe CategoryLevel.ORIGIN
        levels["Webtoon"] shouldBe CategoryLevel.FORMAT
    }
}

