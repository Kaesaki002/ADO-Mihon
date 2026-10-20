package eu.kanade.tachiyomi.ui.library.auto.match

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class AniListMetadataSourceTest {

    @Test
    fun `searchByTitle sorgu metni tags blogunda isGeneralSpoiler ve isMediaSpoiler icerir`() {
        val query = AniListHttpMetadataSource.SEARCH_QUERY

        query shouldContain "tags {"
        val tagsBlock = query.substringAfter("tags {").substringBefore("}")
        tagsBlock shouldContain "name"
        tagsBlock shouldContain "rank"
        tagsBlock shouldContain "isGeneralSpoiler"
        tagsBlock shouldContain "isMediaSpoiler"
    }

    @Test
    fun `getByIds sorgu metni tags blogunda isGeneralSpoiler ve isMediaSpoiler icerir`() {
        val query = AniListHttpMetadataSource.GET_BY_IDS_QUERY

        query shouldContain "tags {"
        val tagsBlock = query.substringAfter("tags {").substringBefore("}")
        tagsBlock shouldContain "name"
        tagsBlock shouldContain "rank"
        tagsBlock shouldContain "isGeneralSpoiler"
        tagsBlock shouldContain "isMediaSpoiler"
    }
}
