package com.prof18.feedflow.shared.domain.feed

import com.prof18.feedflow.core.model.FeedItem
import com.prof18.feedflow.core.model.FeedItemId
import com.prof18.feedflow.core.model.FeedOrder
import com.prof18.feedflow.database.DatabaseHelper
import com.prof18.feedflow.shared.data.FeedAppearanceSettingsRepository
import com.prof18.feedflow.shared.data.SettingsRepository
import com.prof18.feedflow.shared.presentation.model.ArticleListSource
import com.prof18.feedflow.shared.test.KoinTestBase
import com.prof18.feedflow.shared.test.TestDispatcherProvider.testDispatcher
import com.prof18.feedflow.shared.test.buildFeedItem
import com.prof18.feedflow.shared.test.buildFeedItemsForSource
import com.prof18.feedflow.shared.test.generators.FeedSourceGenerator
import com.prof18.feedflow.shared.test.toParsedFeedSource
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest
import org.koin.test.inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArticleNavigationRepositoryTest : KoinTestBase() {

    private val articleNavigationRepository: ArticleNavigationRepository by inject()
    private val feedStateRepository: FeedStateRepository by inject()
    private val databaseHelper: DatabaseHelper by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val feedAppearanceSettingsRepository: FeedAppearanceSettingsRepository by inject()

    @Test
    fun `search order wins over home order and stops at search boundaries`() = runTest(testDispatcher) {
        val homeItems = seedItems(count = 3, prefix = "overlap")
        val a = homeItems[0]
        val c = homeItems[2]
        articleNavigationRepository.setSearchResults(listOf(c, a).toImmutableList())

        assertEquals(
            ArticlePosition(currentPosition = 1, totalArticles = 2),
            articleNavigationRepository.getArticlePosition(c.id),
        )
        assertNull(articleNavigationRepository.getPreviousArticle(c.id))
        assertEquals(a.id, articleNavigationRepository.getNextArticle(c.id)?.id)

        assertEquals(
            ArticlePosition(currentPosition = 2, totalArticles = 2),
            articleNavigationRepository.getArticlePosition(a.id),
        )
        assertEquals(c.id, articleNavigationRepository.getPreviousArticle(a.id)?.id)
        assertNull(articleNavigationRepository.getNextArticle(a.id))
    }

    @Test
    fun `search-only articles navigate while home is loaded`() = runTest(testDispatcher) {
        val homeItems = seedItems(count = 2, prefix = "home")
        val source = homeItems.first().feedSource
        val searchOnlyItems = listOf(
            buildFeedItem(
                id = "search-only-x",
                title = "Search only X",
                pubDateMillis = 30_000,
                source = source,
            ),
            buildFeedItem(
                id = "search-only-y",
                title = "Search only Y",
                pubDateMillis = 29_000,
                source = source,
            ),
        )
        articleNavigationRepository.setSearchResults(searchOnlyItems.toImmutableList())

        assertEquals(2, feedStateRepository.feedState.value.size)
        assertEquals(listOf("search-only-x", "search-only-y"), searchOnlyItems.map { it.id })
        assertEquals(
            ArticlePosition(currentPosition = 1, totalArticles = 2),
            articleNavigationRepository.getArticlePosition("search-only-x"),
        )
        assertEquals("search-only-y", articleNavigationRepository.getNextArticle("search-only-x")?.id)
        assertEquals(
            ArticlePosition(currentPosition = 2, totalArticles = 2),
            articleNavigationRepository.getArticlePosition("search-only-y"),
        )
        assertEquals("search-only-x", articleNavigationRepository.getPreviousArticle("search-only-y")?.id)
        assertEquals(homeItems.map { it.id }, feedStateRepository.feedState.value.map { it.id })
    }

    @Test
    fun `no snapshot delegates to home and missing IDs return null`() = runTest(testDispatcher) {
        val items = seedItems(count = 3, prefix = "home")
        val a = items[0]
        val b = items[1]
        val c = items[2]

        assertEquals(
            ArticlePosition(currentPosition = 2, totalArticles = 3),
            articleNavigationRepository.getArticlePosition(b.id),
        )
        assertEquals(a.id, articleNavigationRepository.getPreviousArticle(b.id)?.id)
        assertEquals(c.id, articleNavigationRepository.getNextArticle(b.id)?.id)
        assertNull(articleNavigationRepository.getArticlePosition("missing"))
        assertNull(articleNavigationRepository.getPreviousArticle("missing"))
        assertNull(articleNavigationRepository.getNextArticle("missing"))
    }

    @Test
    fun `nonmember of active snapshot delegates to home`() = runTest(testDispatcher) {
        val homeItems = seedItems(count = 3, prefix = "home")
        val a = homeItems[0]
        val b = homeItems[1]
        val c = homeItems[2]
        articleNavigationRepository.setSearchResults(listOf(c, a).toImmutableList())

        assertEquals(
            ArticlePosition(currentPosition = 2, totalArticles = 3),
            articleNavigationRepository.getArticlePosition(b.id),
        )
        assertEquals(a.id, articleNavigationRepository.getPreviousArticle(b.id)?.id)
        assertEquals(c.id, articleNavigationRepository.getNextArticle(b.id)?.id)
    }

    @Test
    fun `list source follows the search snapshot`() = runTest(testDispatcher) {
        val homeItems = seedItems(count = 3, prefix = "source")
        articleNavigationRepository.setSearchResults(listOf(homeItems[2]).toImmutableList())

        assertEquals(ArticleListSource.SEARCH, articleNavigationRepository.listSourceOf(homeItems[2].id))
        assertEquals(ArticleListSource.TIMELINE, articleNavigationRepository.listSourceOf(homeItems[0].id))

        articleNavigationRepository.clearSearchResults()
        assertEquals(ArticleListSource.TIMELINE, articleNavigationRepository.listSourceOf(homeItems[2].id))
    }

    @Test
    fun `clearing and empty snapshot fall back to home`() = runTest(testDispatcher) {
        val homeItems = seedItems(count = 3, prefix = "home")
        val c = homeItems[2]
        articleNavigationRepository.setSearchResults(listOf(homeItems[2], homeItems[0]).toImmutableList())

        articleNavigationRepository.clearSearchResults()
        assertHomeLastPosition(c.id, homeItems[1].id)

        articleNavigationRepository.setSearchResults(emptyList<FeedItem>().toImmutableList())
        assertHomeLastPosition(c.id, homeItems[1].id)
    }

    @Test
    fun `replacement uses the latest search list`() = runTest(testDispatcher) {
        val homeItems = seedItems(count = 3, prefix = "home")
        val a = homeItems[0]
        val c = homeItems[2]
        articleNavigationRepository.setSearchResults(listOf(c, a).toImmutableList())
        articleNavigationRepository.setSearchResults(listOf(a, c).toImmutableList())

        assertEquals(
            ArticlePosition(currentPosition = 1, totalArticles = 2),
            articleNavigationRepository.getArticlePosition(a.id),
        )
        assertNull(articleNavigationRepository.getPreviousArticle(a.id))
        assertEquals(c.id, articleNavigationRepository.getNextArticle(a.id)?.id)
        assertEquals(
            ArticlePosition(currentPosition = 2, totalArticles = 2),
            articleNavigationRepository.getArticlePosition(c.id),
        )
        assertEquals(a.id, articleNavigationRepository.getPreviousArticle(c.id)?.id)
        assertNull(articleNavigationRepository.getNextArticle(c.id))
    }

    @Test
    fun `home next navigation prefetches the next page`() = runTest(testDispatcher) {
        val items = seedItems(count = 45, prefix = "paged")
        assertEquals(40, feedStateRepository.feedState.value.size)

        val next = articleNavigationRepository.getNextArticle(items[35].id)

        assertEquals(items[36].id, next?.id)
        assertEquals(45, feedStateRepository.feedState.value.size)
    }

    @Test
    fun `search next navigation does not prefetch the home page`() = runTest(testDispatcher) {
        val items = seedItems(count = 45, prefix = "paged")
        assertEquals(40, feedStateRepository.feedState.value.size)
        articleNavigationRepository.setSearchResults(listOf(items[35], items[36]).toImmutableList())

        val next = articleNavigationRepository.getNextArticle(items[35].id)

        assertEquals(items[36].id, next?.id)
        assertEquals(40, feedStateRepository.feedState.value.size)
    }

    // fork (Phase 4i)
    @Test
    fun `while reading, the order stays as the timeline was when the reader opened`() = runTest(testDispatcher) {
        val items = seedItems(count = 4, prefix = "reading")
        val (a, b, c, d) = items
        articleNavigationRepository.beginReadingSession()

        // b and c are read; a reload without read articles drops them from the timeline.
        databaseHelper.updateReadStatus(listOf(FeedItemId(b.id), FeedItemId(c.id)), isRead = true)
        settingsRepository.setShowReadArticlesTimeline(false)
        feedStateRepository.getFeeds()
        assertEquals(listOf(a.id, d.id), feedStateRepository.feedState.value.map { it.id })

        // From c (gone from the timeline) and from d (still there), the neighbours are as read.
        assertEquals(d.id, articleNavigationRepository.getNextArticle(c.id)?.id)
        assertEquals(b.id, articleNavigationRepository.getPreviousArticle(c.id)?.id)
        assertEquals(c.id, articleNavigationRepository.getPreviousArticle(d.id)?.id)
        assertEquals(ArticlePosition(currentPosition = 4, totalArticles = 4), articleNavigationRepository.getArticlePosition(d.id))
        assertNull(articleNavigationRepository.getNextArticle(d.id))

        // After the reader closes, the timeline decides again.
        articleNavigationRepository.endReadingSession()
        assertEquals(a.id, articleNavigationRepository.getPreviousArticle(d.id)?.id)
        assertNull(articleNavigationRepository.getNextArticle(c.id))
    }

    @Test
    fun `past the end of the reading order the timeline continues and is kept`() = runTest(testDispatcher) {
        val items = seedItems(count = 45, prefix = "readpaged")
        assertEquals(40, feedStateRepository.feedState.value.size)
        articleNavigationRepository.beginReadingSession()

        assertEquals(items[40].id, articleNavigationRepository.getNextArticle(items[39].id)?.id)
        assertEquals(items[41].id, articleNavigationRepository.getNextArticle(items[40].id)?.id)
        assertEquals(items[39].id, articleNavigationRepository.getPreviousArticle(items[40].id)?.id)
    }

    @Test
    fun `an article outside the reading order uses the timeline`() = runTest(testDispatcher) {
        val items = seedItems(count = 3, prefix = "outside")
        articleNavigationRepository.beginReadingSession()
        assertNull(articleNavigationRepository.getPreviousArticle("not-in-order"))
        assertEquals(items[0].id, articleNavigationRepository.getPreviousArticle(items[1].id)?.id)
    }

    private suspend fun seedItems(count: Int, prefix: String): List<FeedItem> {
        settingsRepository.setShowReadArticlesTimeline(true)
        settingsRepository.setHideReadItems(false)
        feedAppearanceSettingsRepository.setFeedOrder(FeedOrder.NEWEST_FIRST)

        val source = FeedSourceGenerator.feedSource(
            id = "source-$prefix",
            title = "Source $prefix",
            url = "https://example.com/$prefix/feed.xml",
            websiteUrl = "https://example.com/$prefix",
        )
        databaseHelper.insertFeedSource(listOf(source.toParsedFeedSource()))

        val items = buildFeedItemsForSource(
            source = source,
            count = count,
            startTimestamp = 45_000,
        )
        databaseHelper.insertFeedItems(items, lastSyncTimestamp = 0)
        feedStateRepository.getFeeds()
        return items
    }

    private suspend fun assertHomeLastPosition(articleId: String, expectedPreviousId: String) {
        assertEquals(
            ArticlePosition(currentPosition = 3, totalArticles = 3),
            articleNavigationRepository.getArticlePosition(articleId),
        )
        assertEquals(expectedPreviousId, articleNavigationRepository.getPreviousArticle(articleId)?.id)
        assertNull(articleNavigationRepository.getNextArticle(articleId))
    }
}
