package eu.kanade.tachiyomi.ui.library.auto

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.library.LibrarySettingsViewModel
import eu.kanade.tachiyomi.ui.library.LibraryViewModel
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.track.TrackerManager

class AutoCategoryGrouperTest {

    private fun createLibraryItem(id: Long, genres: List<String>): LibraryItem {
        val manga = Manga(
            id = id,
            source = 1L,
            lastUpdate = null,
            nextUpdate = null,
            fetchInterval = 0,
            favoriteAt = null,
            viewerFlags = 0L,
            chapterFlags = 0L,
            coverLastModified = null,
            url = "/manga/$id",
            title = "Manga $id",
            artist = null,
            author = null,
            description = null,
            genre = genres,
            status = 0L,
            thumbnailUrl = null,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            initialized = true,
            notes = "",
            memo = JsonObject(emptyMap()),
        )
        val libraryManga = LibraryManga(
            manga = manga,
            categories = listOf(1L),
            totalChapters = 10L,
            readCount = 0L,
            bookmarkCount = 0L,
            latestUpload = null,
            chapterFetchedAt = null,
            lastRead = null,
        )
        return LibraryItem(
            libraryManga = libraryManga,
            downloadCount = 0,
            unreadCount = 10L,
            isLocal = false,
            sourceName = "Source",
            sourceLanguage = "en",
            badges = LibraryItem.Badges(0, 10L, false, "en"),
        )
    }

    @Test
    fun `sozlukteki tum etiketler icin uretilen sanal IDler benzersizdir ve negatif sayidir`() {
        val allDefinitions = DEFAULT_TAG_DEFINITIONS
        val generatedIds = allDefinitions.map { def ->
            AutoCategoryGrouper.virtualCategoryId(def.canonicalName)
        }

        // 1. Tüm ID'ler benzersiz olmalıdır
        generatedIds.toSet() shouldHaveSize allDefinitions.size

        // 2. Hiçbir ID -1L veya 0L olamaz, hepsi < -1L olmalıdır
        for (id in generatedIds) {
            id shouldBeLessThan -1L
            id shouldNotBe 0L
        }

        // 3. Büyük/küçük harf ve boşluk duyarsızlığı aynı sonucu vermelidir
        val id1 = AutoCategoryGrouper.virtualCategoryId("Action")
        val id2 = AutoCategoryGrouper.virtualCategoryId("  action  ")
        id1 shouldBe id2
    }

    @Test
    fun `autoCategories kapaliyken gruplama degistirilmeden doner`() {
        val realCategory = Category(id = 1L, name = "Okunanlar", order = 0L, flags = 0L)
        val initialMap = mapOf(realCategory to listOf(1L, 2L, 3L))
        val favorites = listOf(
            createLibraryItem(1L, listOf("Action")),
            createLibraryItem(2L, listOf("Action")),
            createLibraryItem(3L, listOf("Action")),
        )

        val result = AutoCategoryGrouper.groupWithAutoCategories(
            groupedFavorites = initialMap,
            favorites = favorites,
            enabled = false,
            minMangaCount = 3,
        )

        result shouldBe initialMap
    }

    @Test
    fun `autoCategories acikken sanal kategoriler gercek kategorilerin ardina eklenir`() {
        val realCategory1 = Category(id = 1L, name = "Kategori A", order = 1L, flags = 0L)
        val realCategory2 = Category(id = 2L, name = "Kategori B", order = 2L, flags = 0L)
        val initialMap = linkedMapOf(
            realCategory1 to listOf(1L, 2L),
            realCategory2 to listOf(3L),
        )

        val favorites = listOf(
            createLibraryItem(1L, listOf("Action", "Fantasy")),
            createLibraryItem(2L, listOf("Action", "Romance")),
            createLibraryItem(3L, listOf("Action")),
        )

        val result = AutoCategoryGrouper.groupWithAutoCategories(
            groupedFavorites = initialMap,
            favorites = favorites,
            enabled = true,
            minMangaCount = 3,
        )

        val categories = result.keys.toList()
        categories shouldHaveSize 3
        // İlk iki kategori gerçek kategoriler olmalıdır
        categories[0] shouldBe realCategory1
        categories[1] shouldBe realCategory2

        // Üçüncü kategori Action sanal kategorisi olmalıdır (3 manga eşleşti, minMangaCount=3)
        val virtualCat = categories[2]
        virtualCat.name shouldBe "Action"
        virtualCat.id shouldBe AutoCategoryGrouper.virtualCategoryId("Action")
        virtualCat.id shouldBeLessThan -1L
        virtualCat.order shouldBe 3L // realCategory2.order (2L) + 1L
        result[virtualCat] shouldContainExactly listOf(1L, 2L, 3L)

        // Fantasy ve Romance 1'er manga olduğu için eklenmemeli
        categories.any { it.name == "Fantasy" } shouldBe false
        categories.any { it.name == "Romance" } shouldBe false
    }

    @Test
    fun `lastUsedCategory aralik disindayken veya sekmeler kuculunce 0a duser`() {
        val cat1 = Category(id = 1L, name = "Cat 1", order = 0L, flags = 0L)
        val cat2 = Category(id = 2L, name = "Cat 2", order = 1L, flags = 0L)
        val groupedFavorites = linkedMapOf(
            cat1 to listOf(1L),
            cat2 to listOf(2L),
        )

        // 1. Normal geçerli indeks (1 -> cat2)
        val stateValid = LibraryViewModel.State(
            groupedFavorites = groupedFavorites,
            activeCategoryIndex = 1,
        )
        stateValid.coercedActiveCategoryIndex shouldBe 1
        stateValid.activeCategory shouldBe cat2

        // 2. Sanal sekmeler kapandığında indeks dışarıda kalır (örn: 5) -> 0'a düşmeli
        val stateOutOfBounds = LibraryViewModel.State(
            groupedFavorites = groupedFavorites,
            activeCategoryIndex = 5,
        )
        stateOutOfBounds.coercedActiveCategoryIndex shouldBe 0
        stateOutOfBounds.activeCategory shouldBe cat1

        // 3. Negatif bir indeks gelirse -> 0'a düşmeli
        val stateNegative = LibraryViewModel.State(
            groupedFavorites = groupedFavorites,
            activeCategoryIndex = -2,
        )
        stateNegative.coercedActiveCategoryIndex shouldBe 0
        stateNegative.activeCategory shouldBe cat1

        // 4. Kütüphane tamamen boşsa -> 0'a düşmeli, activeCategory null olmalı
        val stateEmpty = LibraryViewModel.State(
            groupedFavorites = emptyMap(),
            activeCategoryIndex = 3,
        )
        stateEmpty.coercedActiveCategoryIndex shouldBe 0
        stateEmpty.activeCategory.shouldBeNull()
    }

    @Test
    fun `sanal kategoride setSort veritabanina veya repositoryye cagri yapmaz`() = runTest {
        val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)
        val setDisplayMode = mockk<SetDisplayMode>(relaxed = true)
        val setSortModeForCategory = mockk<SetSortModeForCategory>(relaxed = true)
        val trackerManager = mockk<TrackerManager> {
            every { loggedInTrackersFlow() } returns emptyFlow()
            every { loggedInTrackers() } returns emptyList()
        }

        val preferences = mockk<BasePreferences>(relaxed = true)
        val viewModel = LibrarySettingsViewModel(
            preferences = preferences,
            libraryPreferences = libraryPreferences,
            setDisplayMode = setDisplayMode,
            setSortModeForCategory = setSortModeForCategory,
            trackerManager = trackerManager,
        )

        // Sanal kategori (id < 0L)
        val virtualCategory = Category(
            id = -500L,
            name = "Action",
            order = 10L,
            flags = 0L,
        )

        viewModel.setSort(virtualCategory, LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending)

        // setSortModeForCategory çağrılmamalıdır
        coVerify(exactly = 0) {
            setSortModeForCategory.await(any<Category>(), any(), any())
            setSortModeForCategory.await(any<Long>(), any(), any())
        }

        // Gerçek kategoride ise çağrılmalıdır
        val realCategory = Category(
            id = 5L,
            name = "Shounen",
            order = 1L,
            flags = 0L,
        )
        viewModel.setSort(realCategory, LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending)

        coVerify(exactly = 1) {
            setSortModeForCategory.await(realCategory, LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending)
        }
    }
}
