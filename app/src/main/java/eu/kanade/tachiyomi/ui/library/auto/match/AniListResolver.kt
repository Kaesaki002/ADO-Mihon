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
     * Verilen ID listesinden henüz önbellekte (metadata veya negatif kayıt) bulunmayanları
     * 50'şerli getByIds çağrıları ile çeker ve saveMetadata ile kaydeder.
     * Döndürülmeyen ID'ler için 30 günlük negatif kayıt tutar ve sonraki prefetch'te yeniden sorgulamaz.
     * Ağ hatasında diske hiçbir şey yazmaz ve hatayı yukarı fırlatır.
     */
    suspend fun prefetchByIds(ids: List<Long>) {
        val distinctIds = ids.filter { it > 0 }.distinct()
        if (distinctIds.isEmpty()) return

        // 1. Önbellekte (ne metadata ne de negatif kayıt olarak) bulunmayan ID'leri belirle
        val missingIds = mutableListOf<Long>()
        for (id in distinctIds) {
            if (!matchCache.isCachedOrNegativelyCached(id)) {
                missingIds.add(id)
            }
        }

        if (missingIds.isEmpty()) return

        // 2. 50'şerli gruplar halinde çek
        val allFetched = mutableMapOf<Long, AniListMediaCandidate>()
        for (chunk in missingIds.chunked(50)) {
            val fetched = try {
                metadataSource.getByIds(chunk)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw e // Ağ hatasında hiçbir şey yazma
            }
            allFetched.putAll(fetched)
        }

        for (id in missingIds) {
            val candidate = allFetched[id]
            if (candidate != null) {
                matchCache.saveMetadata(candidate)
            } else {
                matchCache.saveNotFoundMetadata(id)
            }
        }
    }

    /**
     * Mangayı sırasıyla tracker, önbellek ve başlık araması ile çözümler.
     * Ağ hataları veya CancellationException önbelleğe asla yazılmaz.
     */
    suspend fun resolve(input: ResolverMangaInput): ResolverResult {
        // ========================================================
        // 1. Tracker Kontrolü (tracker_id = 2 ise doğrudan kullan)
        // Sıra: getMatch -> getMetadata -> ağ
        // ========================================================
        if (input.anilistTrackerId != null && input.anilistTrackerId > 0) {
            val trackerId = input.anilistTrackerId

            // 1.1 getMatch kontrolü: geçerli (süresi dolmamış) bir NOT_MATCHED ya da MATCHED kaydı varsa ağa gitmeden dön
            val cachedMatch = matchCache.getMatch(
                mangaId = input.mangaId,
                currentTitle = input.title,
                currentAuthors = input.authors,
                currentTrackerRemoteId = trackerId,
            )

            if (cachedMatch != null) {
                when (cachedMatch.status) {
                    MatchStatus.NOT_MATCHED -> {
                        return ResolverResult(
                            candidate = null,
                            status = MatchStatus.NOT_MATCHED,
                            reason = cachedMatch.reason,
                            isFromCache = true,
                        )
                    }
                    MatchStatus.MATCHED -> {
                        val cachedMeta = matchCache.getMetadata(trackerId)
                        if (cachedMeta != null) {
                            return ResolverResult(
                                candidate = cachedMeta,
                                status = MatchStatus.MATCHED,
                                reason = cachedMatch.reason,
                                isFromCache = true,
                            )
                        }
                    }
                    MatchStatus.AMBIGUOUS -> {
                        // Eski AMBIGUOUS ezilmek üzere devam eder
                    }
                }
            }

            // 1.2 getMetadata kontrolü: eşleşme kaydı yoksa ama metadata önceden prefetch edildiyse
            val cachedMeta = matchCache.getMetadata(trackerId)
            if (cachedMeta != null) {
                matchCache.saveMatch(
                    mangaId = input.mangaId,
                    title = input.title,
                    authors = input.authors,
                    trackerRemoteId = trackerId,
                    status = MatchStatus.MATCHED,
                    reason = MatchReason.TRACKER,
                    score = 1.0,
                    anilistId = trackerId,
                )
                return ResolverResult(
                    candidate = cachedMeta,
                    status = MatchStatus.MATCHED,
                    reason = MatchReason.TRACKER,
                    isFromCache = true,
                )
            }

            // 1.3 Negatif metadata kontrolü: 30 gün içinde bulunamamışsa ağa gitme
            if (matchCache.isNegativeMetadataCache(trackerId)) {
                matchCache.saveMatch(
                    mangaId = input.mangaId,
                    title = input.title,
                    authors = input.authors,
                    trackerRemoteId = trackerId,
                    status = MatchStatus.NOT_MATCHED,
                    reason = MatchReason.TRACKER,
                    score = 0.0,
                    anilistId = null,
                )
                return ResolverResult(
                    candidate = null,
                    status = MatchStatus.NOT_MATCHED,
                    reason = MatchReason.TRACKER,
                    isFromCache = true,
                )
            }

            // 1.4 Doğrudan AniList ID'si ile ağdan çek (tahmin yok)
            try {
                val fetchedMap = metadataSource.getByIds(listOf(trackerId))
                val candidate = fetchedMap[trackerId]

                if (candidate != null) {
                    matchCache.saveMetadata(candidate)
                    matchCache.saveMatch(
                        mangaId = input.mangaId,
                        title = input.title,
                        authors = input.authors,
                        trackerRemoteId = trackerId,
                        status = MatchStatus.MATCHED,
                        reason = MatchReason.TRACKER,
                        score = 1.0,
                        anilistId = trackerId,
                    )

                    return ResolverResult(
                        candidate = candidate,
                        status = MatchStatus.MATCHED,
                        reason = MatchReason.TRACKER,
                        isFromCache = false,
                    )
                } else {
                    // Tracker ID için AniList kayıt döndürmezse başlık aramasına DÜŞME!
                    // Sonuç kategorisiz (NOT_MATCHED) olsun ve kısa süreli (30 gün) saklansın
                    matchCache.saveNotFoundMetadata(trackerId)
                    matchCache.saveMatch(
                        mangaId = input.mangaId,
                        title = input.title,
                        authors = input.authors,
                        trackerRemoteId = trackerId,
                        status = MatchStatus.NOT_MATCHED,
                        reason = MatchReason.TRACKER,
                        score = 0.0,
                        anilistId = null,
                    )
                    return ResolverResult(
                        candidate = null,
                        status = MatchStatus.NOT_MATCHED,
                        reason = MatchReason.TRACKER,
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

        val matchInput = MatchInput(
            title = input.title,
            authors = input.authors,
            country = input.country,
        )
        val matchResult = AniListMatcher.match(matchInput, candidates)

        // Aramada yalnızca eşleşen adayın metadata'sını önbelleğe al
        if (matchResult.status == MatchStatus.MATCHED && matchResult.matchedCandidate != null) {
            matchCache.saveMetadata(matchResult.matchedCandidate)
        }

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
