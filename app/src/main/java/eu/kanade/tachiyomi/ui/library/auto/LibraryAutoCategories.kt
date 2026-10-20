package eu.kanade.tachiyomi.ui.library.auto

import java.text.Normalizer

/**
 * Kategori seviyeleri (hiyerarşik öncelik sırası).
 */
enum class CategoryLevel(val priority: Int, val displayName: String) {
    ORIGIN(1, "Köken"),
    FORMAT(2, "Format"),
    DEMOGRAPHIC(3, "Hedef Kitle"),
    GENRE(4, "Ana Tür"),
    THEME(5, "Tema"),
}

/**
 * Sözlükteki her bir etiketin tanımı.
 * Yeni bir etiket veya tür eklemek buraya tek satır eklemek kadar basittir.
 */
data class TagDefinition(
    val canonicalName: String,
    val level: CategoryLevel,
    val aliases: List<String> = emptyList(),
)

/**
 * Girdi manga modeli (yalnızca ID ve tür listesi).
 */
data class MangaTagInput(
    val id: Long,
    val genres: Collection<String>?,
)

/**
 * Üretilen sanal kategori modeli.
 */
data class AutoCategory(
    val name: String,
    val level: CategoryLevel,
    val mangaIds: Set<Long>,
)

/**
 * Sözlükte tanınmayan ve hiçbir kategoriye atanmayan etiket raporu.
 */
data class UnrecognizedTag(
    val tag: String,
    val count: Int,
)

/**
 * Otomatik kategori üretim sonucu.
 */
data class AutoCategoriesResult(
    val categories: List<AutoCategory>,
    val unrecognizedTags: List<UnrecognizedTag>,
)

/**
 * Unicode NFD normalizasyonu ile aksanları ve yazım farklılıklarını temizleyen araç.
 */
object TagNormalizer {
    fun normalize(raw: String): String {
        // Unicode NFD ile harfleri ve aksan işaretlerini ayır (ör: ō -> o + macron)
        val nfd = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
        // \p{M} ile tüm aksan/vurgu işaretlerini temizle
        return nfd.replace("\\p{M}+".toRegex(), "")
            .lowercase()
            .replace('ı', 'i')
            .replace("[^a-z0-9]".toRegex(), "")
    }
}

/**
 * Etiket sözlüğü ve hızlı arama motoru.
 */
class TagDictionary(definitions: List<TagDefinition> = DEFAULT_TAG_DEFINITIONS) {
    companion object {
        val DEFAULT: TagDictionary by lazy { TagDictionary(DEFAULT_TAG_DEFINITIONS) }
    }

    private val lookup: Map<String, TagDefinition> = buildMap {
        for (def in definitions) {
            put(TagNormalizer.normalize(def.canonicalName), def)
            for (alias in def.aliases) {
                put(TagNormalizer.normalize(alias), def)
            }
        }
    }

    fun find(rawTag: String): TagDefinition? {
        val key = TagNormalizer.normalize(rawTag)
        return lookup[key]
    }
}

/**
 * Otomatik kategori üretici saf (pure) fonksiyon.
 *
 * @param mangas İşlenecek mangalar (id + genres)
 * @param minMangaCount Bir kategorinin oluşturulması için gereken asgari manga sayısı (varsayılan: 3)
 * @param tagDictionary Kullanılacak etiket sözlüğü (varsayılan: TagDictionary.DEFAULT)
 */
fun generateAutoCategories(
    mangas: Collection<MangaTagInput>,
    minMangaCount: Int = 3,
    tagDictionary: TagDictionary = TagDictionary.DEFAULT,
): AutoCategoriesResult {
    val categoryMangaMap = mutableMapOf<TagDefinition, MutableSet<Long>>()
    val unrecognizedRawTagCounts = mutableMapOf<String, Int>()

    for (manga in mangas) {
        val rawGenres = manga.genres ?: continue
        val seenUnrecognizedInThisManga = mutableSetOf<String>()

        for (rawTag in rawGenres) {
            val trimmed = rawTag.trim()
            if (trimmed.isEmpty()) continue

            val tagDef = tagDictionary.find(trimmed)
            if (tagDef != null) {
                categoryMangaMap.getOrPut(tagDef) { mutableSetOf() }.add(manga.id)
            } else {
                seenUnrecognizedInThisManga.add(trimmed)
            }
        }

        for (unrecognized in seenUnrecognizedInThisManga) {
            unrecognizedRawTagCounts[unrecognized] = (unrecognizedRawTagCounts[unrecognized] ?: 0) + 1
        }
    }

    val categories = categoryMangaMap
        .filter { (_, mangaIds) -> mangaIds.size >= minMangaCount }
        .map { (def, mangaIds) ->
            AutoCategory(
                name = def.canonicalName,
                level = def.level,
                mangaIds = mangaIds,
            )
        }
        .sortedWith(
            compareBy<AutoCategory> { it.level.priority }
                .thenByDescending { it.mangaIds.size }
                .thenBy { it.name },
        )

    val unrecognizedTags = unrecognizedRawTagCounts
        .map { (tag, count) -> UnrecognizedTag(tag, count) }
        .sortedWith(
            compareByDescending<UnrecognizedTag> { it.count }
                .thenBy { it.tag },
        )

    return AutoCategoriesResult(
        categories = categories,
        unrecognizedTags = unrecognizedTags,
    )
}

/**
 * Geriye dönük uyumluluk ve liste tabanlı sözlük için aşırı yükleme (overload).
 */
fun generateAutoCategories(
    mangas: Collection<MangaTagInput>,
    minMangaCount: Int = 3,
    dictionary: List<TagDefinition>,
): AutoCategoriesResult = generateAutoCategories(mangas, minMangaCount, TagDictionary(dictionary))

/**
 * Varsayılan etiket ve tür sözlüğü.
 * Mantıktan bağımsızdır; yeni etiket eklemek buraya tek satır eklemektir.
 */
val DEFAULT_TAG_DEFINITIONS: List<TagDefinition> = listOf(
    // 1. Köken (Origin) - Kesinlikle sadece Manga, Manhwa, Manhua (Japanese/Korean/Chinese ülke adları kaldırıldı)
    TagDefinition("Manga", CategoryLevel.ORIGIN),
    TagDefinition("Manhwa", CategoryLevel.ORIGIN),
    TagDefinition("Manhua", CategoryLevel.ORIGIN),

    // 2. Format
    TagDefinition("Webtoon", CategoryLevel.FORMAT),
    TagDefinition("Comic", CategoryLevel.FORMAT, listOf("Western Comic", "Comics")),

    // 3. Hedef Kitle (Demographic)
    TagDefinition("Shounen", CategoryLevel.DEMOGRAPHIC, listOf("Shonen")),
    TagDefinition("Seinen", CategoryLevel.DEMOGRAPHIC),
    TagDefinition("Shoujo", CategoryLevel.DEMOGRAPHIC, listOf("Shojo")),
    TagDefinition("Josei", CategoryLevel.DEMOGRAPHIC),
    TagDefinition("Kodomo", CategoryLevel.DEMOGRAPHIC, listOf("Kids")),

    // 4. Ana Tür (Genre)
    TagDefinition("Action", CategoryLevel.GENRE, listOf("Aksiyon")),
    TagDefinition("Adventure", CategoryLevel.GENRE, listOf("Macera")),
    TagDefinition("Comedy", CategoryLevel.GENRE, listOf("Komedi")),
    TagDefinition("Drama", CategoryLevel.GENRE),
    TagDefinition("Ecchi", CategoryLevel.GENRE),
    TagDefinition("Fantasy", CategoryLevel.GENRE, listOf("Fantastik")),
    TagDefinition("Horror", CategoryLevel.GENRE, listOf("Korku")),
    TagDefinition("Mahou Shoujo", CategoryLevel.GENRE, listOf("Magical Girl")),
    TagDefinition("Mystery", CategoryLevel.GENRE, listOf("Gizem")),
    TagDefinition("Psychological", CategoryLevel.GENRE, listOf("Psikolojik")),
    TagDefinition("Romance", CategoryLevel.GENRE, listOf("Romantik")),
    TagDefinition("Sci-Fi", CategoryLevel.GENRE, listOf("Science Fiction", "Bilim Kurgu")),
    TagDefinition("Slice of Life", CategoryLevel.GENRE),
    TagDefinition("Sports", CategoryLevel.GENRE, listOf("Sport", "Spor")),
    TagDefinition("Supernatural", CategoryLevel.GENRE, listOf("Dogaustu")),
    TagDefinition("Thriller", CategoryLevel.GENRE, listOf("Gerilim")),
    TagDefinition("Mecha", CategoryLevel.GENRE),

    // 5. Niş Tema (Theme)
    TagDefinition("Isekai", CategoryLevel.THEME),
    TagDefinition("Reincarnation", CategoryLevel.THEME, listOf("Reenkarnasyon")),
    TagDefinition("Time Travel", CategoryLevel.THEME, listOf("Zaman Yolculugu")),
    TagDefinition("Martial Arts", CategoryLevel.THEME, listOf("Dovus Sanatlari")),
    TagDefinition("School Life", CategoryLevel.THEME, listOf("School", "Okul")),
    TagDefinition("Historical", CategoryLevel.THEME, listOf("Tarihi")),
    TagDefinition("Music", CategoryLevel.THEME, listOf("Muzik")),
    TagDefinition("Medical", CategoryLevel.THEME, listOf("Medikal")),
    TagDefinition("Survival", CategoryLevel.THEME, listOf("Hayatta Kalma")),
    TagDefinition("Demons", CategoryLevel.THEME, listOf("Demon", "Seytan")),
    TagDefinition("Magic", CategoryLevel.THEME, listOf("Buyu", "Witch", "Witches")),
    TagDefinition("Monsters", CategoryLevel.THEME, listOf("Monster", "Canavarlar")),
    TagDefinition("Post-Apocalyptic", CategoryLevel.THEME, listOf("Kiyamet Sonrasi")),
    TagDefinition("Vampire", CategoryLevel.THEME, listOf("Vampir")),
    TagDefinition("Video Games", CategoryLevel.THEME, listOf("Oyun")),
    TagDefinition("Virtual Reality", CategoryLevel.THEME, listOf("VR")),
    TagDefinition("Superhero", CategoryLevel.THEME, listOf("Super Kahraman")),
    TagDefinition("Crime", CategoryLevel.THEME, listOf("Suc")),
    TagDefinition("Military", CategoryLevel.THEME, listOf("Askeri")),
    TagDefinition("Space", CategoryLevel.THEME, listOf("Uzay")),
    TagDefinition("Cooking", CategoryLevel.THEME, listOf("Yemek")),
    TagDefinition("Harem", CategoryLevel.THEME),
    TagDefinition("Zombies", CategoryLevel.THEME, listOf("Zombie")),
)
