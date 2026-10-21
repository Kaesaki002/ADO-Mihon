package eu.kanade.tachiyomi.ui.library.auto

import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.category.model.Category

/**
 * Kütüphane eşleşmelerine göre kaynak etiketlerinden sanal kategoriler üreten
 * ve gerçek kategorilerin ardına ekleyen saf (pure) fonksiyonlar.
 */
object AutoCategoryGrouper {

    /**
     * Verilen kategori adı için sabit ve deterministik bir negatif ID üretir.
     * -1L (sentinel) ve 0L (UNCATEGORIZED_ID) asla üretilmez.
     * Değer aralığı: -2L .. -2,147,483,649L
     */
    fun virtualCategoryId(name: String): Long {
        val hash = name.trim().lowercase().hashCode().toLong().and(0x7fffffffL)
        return -2L - hash
    }

    /**
     * Kütüphane favorilerini kaynak etiketlerine göre analiz eder ve [enabled] true ise
     * gerçek kategorilerden SONRA sanal kategorileri ekler.
     *
     * @param groupedFavorites Gerçek kategorilere göre gruplanmış manga ID haritası
     * @param favorites Kütüphanedeki tüm favori mangalar
     * @param enabled Otomatik kategoriler açık mı
     * @param minMangaCount Bir sanal sekme oluşması için gereken asgari manga sayısı (varsayılan: 3)
     */
    fun groupWithAutoCategories(
        groupedFavorites: Map<Category, List<Long>>,
        favorites: List<LibraryItem>,
        enabled: Boolean,
        minMangaCount: Int = 3,
    ): Map<Category, List<Long>> {
        if (!enabled || favorites.isEmpty()) return groupedFavorites

        // 1. Mangaların kaynak etiketlerini MangaTagInput modeline dönüştür
        val tagInputs = favorites.map { item ->
            MangaTagInput(
                id = item.id,
                genres = item.libraryManga.manga.genre,
            )
        }

        // 2. Saf fonksiyon ile otomatik kategorileri üret
        val autoResult = generateAutoCategories(
            mangas = tagInputs,
            minMangaCount = minMangaCount,
        )

        if (autoResult.categories.isEmpty()) return groupedFavorites

        // 3. Mevcut sıralamayı (LinkedHashMap) koruyarak sanal kategorileri sona ekle
        val resultMap = LinkedHashMap<Category, List<Long>>(groupedFavorites)
        val favoriteIds = favorites.map { it.id }.toSet()

        var orderOffset = (groupedFavorites.keys.maxOfOrNull { it.order } ?: 0L) + 1L

        for (autoCat in autoResult.categories) {
            val vId = virtualCategoryId(autoCat.name)
            val category = Category(
                id = vId,
                name = autoCat.name,
                order = orderOffset++,
                flags = 0L,
            )

            val matchingIds = autoCat.mangaIds.filter { it in favoriteIds }
            if (matchingIds.isNotEmpty()) {
                resultMap[category] = matchingIds
            }
        }

        return resultMap
    }
}
