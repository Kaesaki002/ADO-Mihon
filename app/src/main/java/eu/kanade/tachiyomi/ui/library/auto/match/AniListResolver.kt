package eu.kanade.tachiyomi.ui.library.auto.match

import kotlinx.coroutines.CancellationException

/**
 * Çözümleyici girdi modeli (saf veri sınıfı, veritabanı bağımsızdır).
 *
 * @param mangaId Yerel manga ID'si
 * @param title Manga başlığı
 * @param authors Yazar listesi
 * @param country Ülke kodu (JP, KR, CN)
 * @param anilistTrackerId Manga üzerinde tracker_id = 2 (AniList) olan kaydın remote_id'si (varsa)
 */
data class ResolverMangaInput(
    val mangaId: Long,
    val title: String,
    val authors: List<String> = emptyList(),
    val country: String? = null,
    val anilistTrackerId: Long? = null,
)

/**
 * Çözümleme sonucu.
 */
data class ResolverResult(
    val candidate: AniListMediaCandidate?,
    val status: MatchStatus,
    val reason: MatchReason,
    val isFromCache: Boolean = false,
)

/**
 * AniList manga çözümleyicisi.
 * Sıra: tracker -> önbellek -> arama -> kategorisiz.
 */
class AniListResolver(
    private val metadataSource: AniListMetadataSource,
    private val matchCache: AniListMatchCache,
) {
    /**
     * Mangayı sırasıyla tracker, önbellek ve başlık araması ile çözümler.
     * Ağ hataları veya CancellationException önbelleğe asla yazılmaz.
     */
    suspend fun resolve(input: ResolverMangaInput): ResolverResult {
        // ========================================================
        // 1. Tracker Kontrolü (tracker_id = 2 ise doğrudan kullan)
        // ========================================================
        if (input.anilistTrackerId != null && input.anilistTrackerId > 0) {
            val cachedMatch = matchCache.getMatch(
                mangaId = input.mangaId,
                currentTitle = input.title,
                currentAuthors = input.authors,
                currentTrackerRemoteId = input.anilistTrackerId,
            )

            if (cachedMatch != null && cachedMatch.status == MatchStatus.MATCHED && cachedMatch.anilistId == input.anilistTrackerId) {
                val cachedMeta = matchCache.getMetadata(input.anilistTrackerId)
                if (cachedMeta != null) {
                    return ResolverResult(
                        candidate = cachedMeta,
                        status = MatchStatus.MATCHED,
                        reason = cachedMatch.reason,
                        isFromCache = true,
                    )
                }
            }

            // Önbellekte yoksa doğrudan AniList ID'si ile çek (tahmin yok)
            try {
                val fetchedMap = metadataSource.getByIds(listOf(input.anilistTrackerId))
                val candidate = fetchedMap[input.anilistTrackerId]

                if (candidate != null) {
                    matchCache.saveMetadata(candidate)
                    matchCache.saveMatch(
                        mangaId = input.mangaId,
                        title = input.title,
                        authors = input.authors,
                        trackerRemoteId = input.anilistTrackerId,
                        status = MatchStatus.MATCHED,
                        reason = MatchReason.SCORE,
                        score = 1.0,
                        anilistId = input.anilistTrackerId,
                    )

                    return ResolverResult(
                        candidate = candidate,
                        status = MatchStatus.MATCHED,
                        reason = MatchReason.SCORE,
                        isFromCache = false,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Ağ hatasında diske hiçbir şey yazma ve hatayı yukarı fırlat
                throw e
            }
        }

        // ========================================================
        // 2. Önbellek Kontrolü (Manga eşleşme önbelleği)
        // ========================================================
        val cachedMatch = matchCache.getMatch(
            mangaId = input.mangaId,
            currentTitle = input.title,
            currentAuthors = input.authors,
            currentTrackerRemoteId = input.anilistTrackerId,
        )

        if (cachedMatch != null) {
            when (cachedMatch.status) {
                MatchStatus.MATCHED -> {
                    val anilistId = cachedMatch.anilistId
                    if (anilistId != null) {
                        val cachedMeta = matchCache.getMetadata(anilistId)
                        if (cachedMeta != null) {
                            return ResolverResult(
                                candidate = cachedMeta,
                                status = MatchStatus.MATCHED,
                                reason = cachedMatch.reason,
                                isFromCache = true,
                            )
                        }

                        // Metadata dosyası silinmişse veya süresi dolmuşsa API'den tazele
                        try {
                            val fetched = metadataSource.getByIds(listOf(anilistId))[anilistId]
                            if (fetched != null) {
                                matchCache.saveMetadata(fetched)
                                return ResolverResult(
                                    candidate = fetched,
                                    status = MatchStatus.MATCHED,
                                    reason = cachedMatch.reason,
                                    isFromCache = false,
                                )
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            throw e
                        }
                    }
                }
                MatchStatus.AMBIGUOUS -> {
                    return ResolverResult(
                        candidate = null,
                        status = MatchStatus.AMBIGUOUS,
                        reason = MatchReason.NONE,
                        isFromCache = true,
                    )
                }
                MatchStatus.NOT_MATCHED -> {
                    return ResolverResult(
                        candidate = null,
                        status = MatchStatus.NOT_MATCHED,
                        reason = MatchReason.NONE,
                        isFromCache = true,
                    )
                }
            }
        }

        // ========================================================
        // 3. Arama ve Eşleştirme (AniList GraphQL arama + Matcher)
        // ========================================================
        val candidates = try {
            metadataSource.searchByTitle(input.title)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Ağ veya 5xx hatası oluşursa ASLA önbelleğe yazma
            throw e
        }

        // Bulunan tüm adayların metadata'sını önbelleğe al
        for (cand in candidates) {
            matchCache.saveMetadata(cand)
        }

        val matchInput = MatchInput(
            title = input.title,
            authors = input.authors,
            country = input.country,
        )
        val matchResult = AniListMatcher.match(matchInput, candidates)

        // Eşleşme sonucunu önbelleğe yaz
        matchCache.saveMatch(
            mangaId = input.mangaId,
            title = input.title,
            authors = input.authors,
            trackerRemoteId = input.anilistTrackerId,
            status = matchResult.status,
            reason = matchResult.reason,
            score = matchResult.score,
            anilistId = matchResult.matchedCandidate?.id,
        )

        // ========================================================
        // 4. Çözümleme / Kategorisiz Kararı
        // ========================================================
        return when (matchResult.status) {
            MatchStatus.MATCHED -> ResolverResult(
                candidate = matchResult.matchedCandidate,
                status = MatchStatus.MATCHED,
                reason = matchResult.reason,
                isFromCache = false,
            )
            MatchStatus.AMBIGUOUS -> ResolverResult(
                candidate = null,
                status = MatchStatus.AMBIGUOUS,
                reason = MatchReason.NONE,
                isFromCache = false,
            )
            MatchStatus.NOT_MATCHED -> ResolverResult(
                candidate = null,
                status = MatchStatus.NOT_MATCHED,
                reason = MatchReason.NONE,
                isFromCache = false,
            )
        }
    }
}
