package com.prof18.feedflow.shared.domain.contentprefetch

import com.prof18.feedflow.core.model.ArticleOpenMode
import com.prof18.feedflow.core.model.FeedSource
import com.prof18.feedflow.database.DatabaseHelper
import com.prof18.feedflow.shared.data.SettingsRepository
import com.prof18.feedflow.shared.domain.feeditem.ArticleContentParser
import com.prof18.feedflow.shared.domain.feeditem.FeedItemContentFileHandler
import com.prof18.feedflow.shared.domain.feeditem.ReaderContentFetcher
import com.prof18.feedflow.shared.test.KoinTestBase
import com.prof18.feedflow.shared.test.TestDispatcherProvider
import com.prof18.feedflow.shared.test.buildFeedItem
import com.prof18.feedflow.shared.test.testLogger
import com.prof18.feedflow.shared.test.toParsedFeedSource
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** fork (Phase 4a): article prefetching held back by the app ("Wi-Fi only"). */
class ContentPrefetchGateTest : KoinTestBase() {

    private val parser = RecordingParser()
    private val gate = SwitchGate()

    private val databaseHelper: DatabaseHelper by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val feedItemContentFileHandler: FeedItemContentFileHandler by inject()

    override fun getTestModules(): List<Module> =
        super.getTestModules() + module {
            single<ArticleContentParser> { parser }
        }

    private fun createPrefetcher() = ContentPrefetcher(
        logger = testLogger,
        databaseHelper = databaseHelper,
        readerContentFetcher = ReaderContentFetcher(
            articleContentParser = parser,
            feedItemContentFileHandler = feedItemContentFileHandler,
            htmlRetriever = getKoin().get(),
            recoveryRepository = getKoin().get(),
            coordinator = getKoin().get(),
            sessionHttp = null,
            dateFormatter = getKoin().get(),
        ),
        gate = gate,
    )

    private fun createRepository(prefetcher: ContentPrefetcher) = ContentPrefetchRepositoryImpl(
        logger = testLogger,
        settingsRepository = settingsRepository,
        databaseHelper = databaseHelper,
        contentPrefetcher = prefetcher,
        backgroundPrefetchScheduler = CoroutineBackgroundPrefetchScheduler(
            logger = testLogger,
            contentPrefetcher = prefetcher,
            dispatcherProvider = TestDispatcherProvider,
        ),
    )

    @Test
    fun `nothing is fetched while the gate is closed and everything stays queued`() =
        runTest(TestDispatcherProvider.testDispatcher) {
            settingsRepository.setPrefetchArticleContent(true)
            insertItems(count = 17)
            gate.open = false

            createRepository(createPrefetcher()).prefetchContent()
            advanceUntilIdle()

            assertEquals(emptyList(), parser.parsedUrls, "no article was requested")
            assertEquals(17, databaseHelper.getUnfetchedItems().size, "no article was marked as fetched")
            assertEquals(17, databaseHelper.getNextPrefetchBatch().size, "the first page is queued too")
            assertFalse(feedItemContentFileHandler.isContentAvailable("item-17"))
        }

    @Test
    fun `queued articles are fetched once the gate opens`() = runTest(TestDispatcherProvider.testDispatcher) {
        settingsRepository.setPrefetchArticleContent(true)
        insertItems(count = 17)
        gate.open = false
        val prefetcher = createPrefetcher()
        val repository = createRepository(prefetcher)
        repository.prefetchContent()
        advanceUntilIdle()

        gate.open = true
        repository.startBackgroundFetching()
        advanceUntilIdle()

        assertEquals(17, parser.parsedUrls.size)
        assertEquals(0, databaseHelper.getUnfetchedItems().size)
        assertEquals(0, databaseHelper.getNextPrefetchBatch().size)
        assertTrue(feedItemContentFileHandler.isContentAvailable("item-1"))
        assertTrue(feedItemContentFileHandler.isContentAvailable("item-17"))
    }

    @Test
    fun `a gate that closes midway stops the batch and keeps the rest`() =
        runTest(TestDispatcherProvider.testDispatcher) {
            settingsRepository.setPrefetchArticleContent(true)
            insertItems(count = 5)
            gate.open = true
            parser.onParse = { if (parser.parsedUrls.size == 2) gate.open = false }

            createRepository(createPrefetcher()).prefetchContent()
            advanceUntilIdle()

            assertEquals(2, parser.parsedUrls.size, "the article after the change was not requested")
            assertEquals(3, databaseHelper.getUnfetchedItems().size)
            assertEquals(3, databaseHelper.getNextPrefetchBatch().size)
        }

    private suspend fun insertItems(count: Int) {
        val feedSource = FeedSource(
            id = "source-1",
            url = "https://example.com/source-1/rss.xml",
            title = "Feed source-1",
            category = null,
            lastSyncTimestamp = null,
            logoUrl = null,
            websiteUrl = "https://example.com",
            fetchFailed = false,
            articleOpenMode = ArticleOpenMode.DEFAULT,
            isHiddenFromTimeline = false,
            isPinned = false,
            isNotificationEnabled = false,
            isHideImagesEnabled = false,
        )
        databaseHelper.insertFeedSource(listOf(feedSource.toParsedFeedSource()))
        val items = (1..count).map { index ->
            parser.contentByUrl["https://example.com/item-$index"] = "Content $index"
            buildFeedItem(id = "item-$index", title = "Item $index", pubDateMillis = index.toLong(), source = feedSource)
        }
        databaseHelper.insertFeedItems(items, lastSyncTimestamp = 0)
    }

    private class SwitchGate : ContentPrefetchGate {
        var open = true
        override fun wifiOnly(): Boolean = true
        override fun allowsPrefetch(): Boolean = open
    }

    private class RecordingParser : ArticleContentParser {
        val contentByUrl = mutableMapOf<String, String?>()
        val parsedUrls = mutableListOf<String>()
        var onParse: () -> Unit = {}

        override suspend fun parse(url: String): String? {
            parsedUrls += url
            onParse()
            return contentByUrl[url]
        }

        override suspend fun prepareFeedContent(html: String, baseUrl: String?): String = html
    }
}
