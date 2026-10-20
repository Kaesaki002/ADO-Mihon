package eu.kanade.tachiyomi.ui.library.auto.match

import java.text.Normalizer

object StringSimilarity {

    /**
     * Başlığı harf büyüklüğü, Türkçe ı/i, aksanlar ve noktalama işaretlerinden arındırır.
     * Unicode harf (\p{L}) ve rakamları (\p{N}) korur.
     */
    fun normalizeTitle(text: String?): String {
        if (text.isNullOrBlank()) return ""
        // Unicode NFD ile harfleri ve aksan işaretlerini ayır (ör: ō -> o + macron)
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        // \p{M} ile aksan/vurgu işaretlerini temizle
        val withoutAccents = nfd.replace("\\p{M}+".toRegex(), "")
        // Küçük harfe dönüştür ve Türkçe 'ı' harfini 'i' yap
        val lowered = withoutAccents.lowercase().replace('ı', 'i')
        // Harf ve rakam olmayan tüm noktalama ve sembolleri boşluğa çevir
        val cleaned = lowered.replace("[^\\p{L}\\p{N}\\s]|_".toRegex(), " ")
        // Fazla boşlukları temizle
        return cleaned.trim().replace("\\s+".toRegex(), " ")
    }

    /**
     * Rapidfuzz fuzz.ratio karşılığı (Indel / LCS benzerliği).
     * Sonuç 0.0 ile 1.0 arasında bir değer döner (Python'daki ratio / 100.0).
     */
    fun ratio(s1: String, s2: String): Double {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1 == s2) return 1.0

        val lcs = lcsLength(s1, s2)
        return (2.0 * lcs) / (s1.length + s2.length)
    }

    /**
     * Rapidfuzz fuzz.token_sort_ratio karşılığı.
     * Kelimeleri sıralayıp aralarına tek boşluk koyarak ratio hesaplar.
     */
    fun tokenSortRatio(s1: String, s2: String): Double {
        val t1 = s1.split(" ").filter { it.isNotEmpty() }.sorted().joinToString(" ")
        val t2 = s2.split(" ").filter { it.isNotEmpty() }.sorted().joinToString(" ")
        return ratio(t1, t2)
    }

    /**
     * Rapidfuzz max(ratio, token_sort_ratio) / 100.0 karşılığı.
     * Girdileri normalize ettikten sonra maksimum benzerlik oranını döner.
     */
    fun calculateSimilarity(s1: String, s2: String): Double {
        val n1 = normalizeTitle(s1)
        val n2 = normalizeTitle(s2)
        if (n1.isEmpty() || n2.isEmpty()) return 0.0
        val rRatio = ratio(n1, n2)
        val rToken = tokenSortRatio(n1, n2)
        return maxOf(rRatio, rToken)
    }

    /**
     * Longest Common Subsequence (En Uzun Ortak Alt Dizi) uzunluğu.
     * Bellek tüketimini O(min(N, M)) düzeyinde tutar.
     */
    private fun lcsLength(s1: String, s2: String): Int {
        val str1 = if (s1.length <= s2.length) s1 else s2
        val str2 = if (s1.length <= s2.length) s2 else s1

        val n = str1.length
        val m = str2.length

        var prev = IntArray(n + 1)
        var curr = IntArray(n + 1)

        for (j in 1..m) {
            val c2 = str2[j - 1]
            for (i in 1..n) {
                curr[i] = if (str1[i - 1] == c2) {
                    prev[i - 1] + 1
                } else {
                    maxOf(prev[i], curr[i - 1])
                }
            }
            val temp = prev
            prev = curr
            curr = temp
        }

        return prev[n]
    }
}
