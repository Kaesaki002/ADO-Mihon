package eu.kanade.tachiyomi.ui.library.auto.match

/**
 * Eşleştirme sonuç durumları.
 */
enum class MatchStatus {
    MATCHED,
    AMBIGUOUS,
    NOT_MATCHED,
}

/**
 * Eşleştirme kararının verilme nedeni.
 */
enum class MatchReason {
    SCORE,
    FORMAT,
    AUTHOR,
    COUNTRY,
    NONE,
}

/**
 * Eşleştiriciye gönderilen girdi verisi.
 *
 * @param title Manga başlığı
 * @param authors İsteğe bağlı yazar listesi (virgül, ve, & ile ayrılmış çoklu yazarlar dahil)
 * @param country İsteğe bağlı ülke kodu ("JP", "KR", "CN")
 */
data class MatchInput(
    val title: String,
    val authors: List<String> = emptyList(),
    val country: String? = null,
) {
    companion object {
        /**
         * Virgül, '&', 've', 'and' ile ayrılmış yazar metnini parçalar.
         */
        fun parseAuthors(rawAuthors: String?): List<String> {
            if (rawAuthors.isNullOrBlank()) return emptyList()
            return rawAuthors
                .split(Regex("[,&]|\\bve\\b|\\band\\b", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }
}

/**
 * Eşleştirme sonucu.
 */
data class MatchResult(
    val inputTitle: String,
    val status: MatchStatus,
    val reason: MatchReason,
    val score: Double,
    val matchedCandidate: AniListMediaCandidate? = null,
    val matchedTitle: String? = null,
    val secondCandidate: AniListMediaCandidate? = null,
    val secondScore: Double = 0.0,
)

/**
 * Puanlanmış aday yardımcı sınıfı.
 */
private data class ScoredCandidate(
    val score: Double,
    val bestTitle: String,
    val candidate: AniListMediaCandidate,
)

object AniListMatcher {

    /**
     * Verilen girdi başlığını aday listesine karşı puanlar ve en iyi eşleşmeyi döner.
     *
     * @param input Girdi başlığı, yazarlar ve ülke ipucu
     * @param candidates AniList'ten dönen adaylar
     * @param threshold Asgari eşleşme eşiği (varsayılan: 0.85)
     * @param ambiguityMargin İki adayın birbirine çok yakın sayılacağı marj (varsayılan: 0.04)
     */
    fun match(
        input: MatchInput,
        candidates: List<AniListMediaCandidate>,
        threshold: Double = 0.85,
        ambiguityMargin: Double = 0.04,
    ): MatchResult {
        if (candidates.isEmpty()) {
            return MatchResult(
                inputTitle = input.title,
                status = MatchStatus.NOT_MATCHED,
                reason = MatchReason.NONE,
                score = 0.0,
            )
        }

        // 1. Her adayı olası tüm başlıklarıyla karşılaştırıp en yüksek puanını al
        val scoredCandidates = candidates.map { candidate ->
            var bestScore = 0.0
            var bestCandTitle = ""

            for (candTitle in candidate.allTitles) {
                val score = StringSimilarity.calculateSimilarity(input.title, candTitle)
                if (score > bestScore) {
                    bestScore = score
                    bestCandTitle = candTitle
                }
            }

            ScoredCandidate(score = bestScore, bestTitle = bestCandTitle, candidate = candidate)
        }.sortedByDescending { it.score }

        val top = scoredCandidates[0]
        val second = scoredCandidates.getOrNull(1)

        val topScore = top.score
        val secondScore = second?.score ?: 0.0

        // 2. Eşik ve marj kontrolü
        if (topScore < threshold) {
            return MatchResult(
                inputTitle = input.title,
                status = MatchStatus.NOT_MATCHED,
                reason = MatchReason.NONE,
                score = topScore,
                secondCandidate = second?.candidate,
                secondScore = secondScore,
            )
        }

        // İkinci aday varsa, eşiği geçiyorsa ve fark marj içindeyse:
        if (second != null && secondScore >= threshold && (topScore - secondScore) <= ambiguityMargin) {
            // Eşik ve marj içindeki TÜM adayları al
            val closeCandidates = scoredCandidates.filter {
                it.score >= threshold && (topScore - it.score) <= ambiguityMargin
            }

            var resolved = false
            var winner: ScoredCandidate = top
            var reason = MatchReason.NONE

            // A) Yazar kontrolü (yazar verilmişse ve marjdaki adaylardan YALNIZCA BİRİ eşleşiyorsa)
            if (input.authors.isNotEmpty()) {
                val authorMatches = closeCandidates.filter {
                    checkAuthorMatch(input.authors, it.candidate)
                }
                if (authorMatches.size == 1) {
                    winner = authorMatches[0]
                    reason = MatchReason.AUTHOR
                    resolved = true
                }
            }

            // B) Ülke kontrolü (ülke verilmişse, yazarla çözülmediyse ve YALNIZCA BİRİ eşleşiyorsa)
            if (!resolved && !input.country.isNullOrBlank()) {
                val countryMatches = closeCandidates.filter {
                    checkCountryMatch(input.country, it.candidate)
                }
                if (countryMatches.size == 1) {
                    winner = countryMatches[0]
                    reason = MatchReason.COUNTRY
                    resolved = true
                }
            }

            // C) Format kontrolü (ek alan yoksa veya çözülemediyse)
            if (!resolved) {
                val mangaCands = closeCandidates.filter {
                    it.candidate.format.equals("MANGA", ignoreCase = true)
                }
                val otherFormatCands = closeCandidates.filter {
                    val fmt = it.candidate.format?.uppercase()
                    fmt == "ONE_SHOT" || fmt == "NOVEL"
                }

                // Sadece tek bir MANGA adayı varsa ve diğer tüm yakın adaylar ONE_SHOT veya NOVEL ise:
                if (mangaCands.size == 1 && (mangaCands.size + otherFormatCands.size == closeCandidates.size)) {
                    winner = mangaCands[0]
                    reason = MatchReason.FORMAT
                    resolved = true
                }
            }

            if (resolved) {
                val altCandidates = scoredCandidates.filter { it.candidate.id != winner.candidate.id }
                val altSecond = altCandidates.firstOrNull()

                return MatchResult(
                    inputTitle = input.title,
                    status = MatchStatus.MATCHED,
                    reason = reason,
                    score = winner.score,
                    matchedCandidate = winner.candidate,
                    matchedTitle = winner.bestTitle,
                    secondCandidate = altSecond?.candidate,
                    secondScore = altSecond?.score ?: 0.0,
                )
            } else {
                return MatchResult(
                    inputTitle = input.title,
                    status = MatchStatus.AMBIGUOUS,
                    reason = MatchReason.NONE,
                    score = topScore,
                    matchedCandidate = top.candidate,
                    matchedTitle = top.bestTitle,
                    secondCandidate = second.candidate,
                    secondScore = secondScore,
                )
            }
        }

        // İkinci aday yoksa veya puanı marjın dışındaysa doğrudan en yüksek puanlı kazanır
        return MatchResult(
            inputTitle = input.title,
            status = MatchStatus.MATCHED,
            reason = MatchReason.SCORE,
            score = topScore,
            matchedCandidate = top.candidate,
            matchedTitle = top.bestTitle,
            secondCandidate = second?.candidate,
            secondScore = secondScore,
        )
    }

    /**
     * Yazar eşleşmesini sıkı kontrol eder:
     * 1. Doğrudan tam eşleşme
     * 2. Kelime sırası bağımsız eşleşme ("Nii Satoru" == "Satoru Nii")
     * 3. Boşluksuz/tire temizlenmiş eşleşme ("Jo Yongseok" == "Yong-seok Jo")
     * 4. Yüksek eşikli (>= 0.90) benzerlik
     */
    fun checkAuthorMatch(inputAuthors: List<String>, candidate: AniListMediaCandidate): Boolean {
        if (inputAuthors.isEmpty()) return false

        for (author in inputAuthors) {
            val trimmedAuthor = author.trim()
            if (trimmedAuthor.isEmpty()) continue

            val normInput = StringSimilarity.normalizeTitle(trimmedAuthor)
            if (normInput.isEmpty()) continue

            val compactInput = normInput.replace(" ", "")
            val tokensInput = normInput.split(" ").filter { it.isNotEmpty() }.sorted()

            for (candName in candidate.staffNames) {
                val trimmedCand = candName.trim()
                if (trimmedCand.isEmpty()) continue

                // Doğrudan tam eşleşme (özellikle native isimler için)
                if (trimmedAuthor.equals(trimmedCand, ignoreCase = true)) {
                    return true
                }

                val normCand = StringSimilarity.normalizeTitle(trimmedCand)
                if (normCand.isEmpty()) continue

                // 1. Sıra bağımsız kelime bazlı tam eşleşme
                val tokensCand = normCand.split(" ").filter { it.isNotEmpty() }.sorted()
                if (tokensInput == tokensCand) {
                    return true
                }

                // 2. Boşluksuz / tire temizlenmiş tam eşleşme
                val compactCand = normCand.replace(" ", "")
                if (compactInput == compactCand) {
                    return true
                }

                // Hece birleşimi farkları (örn. "jo yongseok" vs "yong-seok jo")
                if (compactInput.length == compactCand.length &&
                    compactInput.toList().sorted() == compactCand.toList().sorted()
                ) {
                    val inputTokens = normInput.split(" ").filter { it.isNotEmpty() }
                    val candTokens = normCand.split(" ").filter { it.isNotEmpty() }
                    if (inputTokens.all { it in compactCand } && candTokens.all { it in compactInput }) {
                        return true
                    }
                }

                // 3. Yüksek eşikli (>= 0.90) benzerlik
                val rRatio = StringSimilarity.ratio(normInput, normCand)
                val rToken = StringSimilarity.tokenSortRatio(normInput, normCand)
                if (maxOf(rRatio, rToken) >= 0.90) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Ülke kodunu kontrol eder (TW girişi ve adayı CN ile denk sayılır).
     */
    fun checkCountryMatch(targetCountry: String, candidate: AniListMediaCandidate): Boolean {
        var candCountry = (candidate.countryOfOrigin ?: "").uppercase()
        if (candCountry == "TW") candCountry = "CN"
        val normTarget = if (targetCountry.uppercase() == "TW") "CN" else targetCountry.uppercase()
        return candCountry == normTarget
    }
}
