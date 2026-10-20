package eu.kanade.tachiyomi.ui.library.auto.match

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Eşleştirmede kullanılacak saf AniList aday modeli.
 */
@Serializable
data class AniListMediaCandidate(
    val id: Long,
    val titleRomaji: String? = null,
    val titleEnglish: String? = null,
    val titleNative: String? = null,
    val synonyms: List<String> = emptyList(),
    val format: String? = null,
    val countryOfOrigin: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<AniListTag> = emptyList(),
    val staffNames: List<String> = emptyList(),
) {
    /**
     * Eşleştirme puanı hesaplanırken karşılaştırılacak tüm olası başlıklar.
     */
    val allTitles: List<String>
        get() = (listOfNotNull(titleRomaji, titleEnglish, titleNative) + synonyms)
            .filter { it.isNotBlank() }
}

@Serializable
data class AniListTag(
    val name: String,
    val rank: Int,
)

// ==========================================
// JSON Serileştirme / Deserileştirme DTO'ları
// ==========================================

val AniListJsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Serializable
data class AniListMediaDto(
    val id: Long,
    val title: AniListTitleDto? = null,
    val synonyms: List<String> = emptyList(),
    val countryOfOrigin: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<AniListTagDto> = emptyList(),
    val format: String? = null,
    val staff: AniListStaffDto? = null,
) {
    fun toCandidate(): AniListMediaCandidate {
        val staffList = mutableListOf<String>()
        staff?.edges?.forEach { edge ->
            edge.node?.name?.full?.let { if (it.isNotBlank()) staffList.add(it) }
            edge.node?.name?.native?.let { if (it.isNotBlank()) staffList.add(it) }
        }

        return AniListMediaCandidate(
            id = id,
            titleRomaji = title?.romaji,
            titleEnglish = title?.english,
            titleNative = title?.native,
            synonyms = synonyms,
            format = format,
            countryOfOrigin = countryOfOrigin,
            genres = genres,
            tags = tags.mapNotNull {
                val n = it.name
                val r = it.rank
                if (n != null && r != null) AniListTag(n, r) else null
            },
            staffNames = staffList,
        )
    }
}

@Serializable
data class AniListTitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class AniListTagDto(
    val name: String? = null,
    val rank: Int? = null,
)

@Serializable
data class AniListStaffDto(
    val edges: List<AniListStaffEdgeDto> = emptyList(),
)

@Serializable
data class AniListStaffEdgeDto(
    val role: String? = null,
    val node: AniListStaffNodeDto? = null,
)

@Serializable
data class AniListStaffNodeDto(
    val name: AniListStaffNameDto? = null,
)

@Serializable
data class AniListStaffNameDto(
    val full: String? = null,
    val native: String? = null,
)
