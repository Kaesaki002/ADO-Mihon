package eu.kanade.tachiyomi.ui.library.auto.match

import eu.kanade.tachiyomi.ui.library.auto.MangaTagInput

/**
 * AniList etiketleri için varsayılan asgari güvenilirlik rank eşiği.
 */
const val DEFAULT_MIN_TAG_RANK: Int = 60

/**
 * AniList countryOfOrigin kodunu (JP, KR, CN, TW) köken etiketine çevirir.
 * - JP -> "Manga"
 * - KR -> "Manhwa"
 * - CN, TW -> "Manhua"
 * - Boş, tanımsız veya diğer ülkeler için null döner (köken etiketi eklenmez).
 */
fun mapCountryToOrigin(countryCode: String?): String? {
    return when (countryCode?.trim()?.uppercase()) {
        "JP" -> "Manga"
        "KR" -> "Manhwa"
        "CN", "TW" -> "Manhua"
        else -> null
    }
}

/**
 * AniList aday modelini (veya eşleşme yoksa null) [MangaTagInput] modeline dönüştüren saf fonksiyon.
 *
 * @param mangaId Kütüphanedeki yerel manga kimliği
 * @param candidate Eşleşen AniList adayı (eşleşme yoksa null)
 * @param minTagRank AniList etiketleri için asgari rank eşiği (varsayılan: 60)
 */
fun mapToMangaTagInput(
    mangaId: Long,
    candidate: AniListMediaCandidate?,
    minTagRank: Int = DEFAULT_MIN_TAG_RANK,
): MangaTagInput {
    if (candidate == null) {
        return MangaTagInput(id = mangaId, genres = emptyList())
    }

    val tags = mutableListOf<String>()

    // 1. Köken etiketi: countryOfOrigin alanından belirlenir
    mapCountryToOrigin(candidate.countryOfOrigin)?.let { tags.add(it) }

    // 2. Türler (genres)
    for (genre in candidate.genres) {
        val trimmed = genre.trim()
        if (trimmed.isNotEmpty()) {
            tags.add(trimmed)
        }
    }

    // 3. Etiketler (tags): rank >= minTagRank olan ve spoiler olmayanlar
    for (tag in candidate.tags) {
        if (tag.rank >= minTagRank && !tag.isGeneralSpoiler && !tag.isMediaSpoiler) {
            val trimmed = tag.name.trim()
            if (trimmed.isNotEmpty()) {
                tags.add(trimmed)
            }
        }
    }

    return MangaTagInput(
        id = mangaId,
        genres = tags.distinct(),
    )
}

/**
 * [AniListMediaCandidate] için uzantı (extension) fonksiyonu.
 */
fun AniListMediaCandidate?.toMangaTagInput(
    mangaId: Long,
    minTagRank: Int = DEFAULT_MIN_TAG_RANK,
): MangaTagInput = mapToMangaTagInput(mangaId, this, minTagRank)

/**
 * Birden fazla manga için toplu dönüştürme yardımcısı.
 */
fun mapAllToMangaTagInput(
    mangaCandidateMap: Map<Long, AniListMediaCandidate?>,
    minTagRank: Int = DEFAULT_MIN_TAG_RANK,
): List<MangaTagInput> {
    return mangaCandidateMap.map { (mangaId, candidate) ->
        mapToMangaTagInput(mangaId, candidate, minTagRank)
    }
}
