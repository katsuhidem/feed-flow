package io.github.katsuhidem.translation

import io.github.katsuhidem.translation.TranslationQueue.Outcome
import io.github.katsuhidem.translation.TranslationQueue.Priority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TitleTranslatorTest {
    private class Fixture(scope: TestScope, idleUnloadMillis: Long = 60_000) {
        val engine = FakeEngine()
        val cache = newCache(StandardTestDispatcher(scope.testScheduler))
        val queue = TranslationQueue(engine, cache, scope.backgroundScope, idleUnloadMillis)
        var config = TranslationConfig()
        var installed = true
        val feedTitles = mutableMapOf<String, List<String>>()
        var recentTitleCalls = 0
        val translator = TitleTranslator(
            cache = cache,
            queue = queue,
            config = { config },
            modelRev = { "rev-a" },
            canTranslate = { installed },
            recentTitles = { feedId ->
                recentTitleCalls++
                feedTitles[feedId].orEmpty()
            },
        )
    }

    private val japaneseFeed = listOf("新機能を公開しました", "障害のお知らせ", "メンテナンス情報", "採用イベントを開催", "年末年始の営業について")
    private val chineseFeed = listOf("苹果发布新款设备", "北京地铁新线路开通", "多地迎来强降雨", "大学推出免费课程", "博物馆重新开放")

    private fun en(i: Int) = TitleItem("en$i", "english title $i", feedId = "feed-en")

    @Test
    fun englishTitlesGetTranslatedAndJapaneseFeedsAreLeftAlone() = runTest {
        val f = Fixture(this)
        f.feedTitles["feed-ja"] = japaneseFeed
        val items = listOf(
            en(1),
            TitleItem("ja1", "新機能を公開しました", "feed-ja"),
            // No kana, but the feed is Japanese.
            TitleItem("ja2", "2026年11月期 決算短信", "feed-ja"),
        )
        f.translator.request(items, owner = "screen", priority = Priority.VISIBLE).awaitAll()

        assertEquals(mapOf("en1" to "ENGLISH TITLE 1"), f.translator.lookup(items))
        assertEquals(listOf("english title 1"), f.engine.translated)
    }

    @Test
    fun chineseFeedIsTranslatedAndNotTakenForJapanese() = runTest {
        val f = Fixture(this)
        f.feedTitles["feed-zh"] = chineseFeed
        val items = listOf(TitleItem("zh1", "苹果发布新款设备", "feed-zh"))
        f.translator.request(items, "screen", Priority.VISIBLE).awaitAll()
        assertEquals(setOf("zh1"), f.translator.lookup(items).keys)
    }

    @Test
    fun cachedTitlesAreShownWithoutLoadingTheModel() = runTest {
        val f = Fixture(this, idleUnloadMillis = 1_000)
        val items = listOf(en(1), en(2))
        f.translator.request(items, "batch", Priority.BATCH).awaitAll()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, f.engine.unloadCount)

        // Lookup and a second request for the same titles: nothing to do, no load.
        assertEquals(2, f.translator.lookup(items).size)
        assertTrue(f.translator.request(items, "screen", Priority.VISIBLE).isEmpty())
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, f.engine.loadCount, "the model was not loaded again")
    }

    @Test
    fun changingTheStyleHidesOldTranslationsAndTranslatesAgain() = runTest {
        val f = Fixture(this)
        val items = listOf(en(1))
        f.config = TranslationConfig(style = TranslationStyle.PLAIN)
        f.translator.request(items, "screen", Priority.VISIBLE).awaitAll()
        assertEquals(1, f.translator.lookup(items).size)

        f.config = TranslationConfig(style = TranslationStyle.POLITE)
        assertTrue(f.translator.lookup(items).isEmpty(), "the plain translation is not shown for polite")
        f.translator.request(items, "screen", Priority.VISIBLE).awaitAll()
        assertEquals(1, f.translator.lookup(items).size)
        assertEquals(2, f.engine.translated.size)

        // Going back finds the first translation again without new work.
        f.config = TranslationConfig(style = TranslationStyle.PLAIN)
        assertEquals(1, f.translator.lookup(items).size)
        assertTrue(f.translator.request(items, "screen", Priority.VISIBLE).isEmpty())
    }

    @Test
    fun failedTitlesAreNotQueuedAgainUnlessRetried() = runTest {
        val f = Fixture(this)
        val items = listOf(en(1))
        f.engine.terminal = { TranslationEvent.Truncated(it) }
        assertEquals(
            listOf<Outcome>(Outcome.Failure(FailureReason.TRUNCATED)),
            f.translator.request(items, "batch", Priority.BATCH).awaitAll(),
        )
        assertTrue(f.translator.request(items, "batch", Priority.BATCH).isEmpty(), "failure suppresses a new attempt")

        f.engine.terminal = { TranslationEvent.Completed(it.uppercase()) }
        val retried = f.translator.request(items, "screen", Priority.VISIBLE, retryFailed = true).awaitAll()
        assertEquals(listOf<Outcome>(Outcome.Success("ENGLISH TITLE 1")), retried)
        assertEquals(1, f.translator.lookup(items).size)
    }

    @Test
    fun nothingIsQueuedWithoutAnInstalledModel() = runTest {
        val f = Fixture(this)
        f.installed = false
        assertTrue(f.translator.request(listOf(en(1)), "screen", Priority.VISIBLE).isEmpty())
        val result = f.translator.translateBatch(listOf(en(1)), "batch")
        assertEquals(0, result.translated)
        assertEquals(0, f.engine.loadCount)
    }

    @Test
    fun batchStopsAtTheLimitAndTheNextBatchContinues() = runTest {
        val f = Fixture(this)
        val items = (1..25).map { en(it) }
        val first = f.translator.translateBatch(items, "batch", limit = 20)
        assertEquals(20, first.translated)
        assertEquals(5, first.remaining)

        val second = f.translator.translateBatch(items, "batch", limit = 20)
        assertEquals(5, second.translated)
        assertEquals(0, second.remaining)
        assertEquals(25, f.translator.lookup(items).size)
        assertEquals(25, f.engine.translated.size, "nothing was translated twice")
    }

    @Test
    fun feedLanguageIsEstimatedOnceAndThenKept() = runTest {
        val f = Fixture(this)
        f.feedTitles["feed-ja"] = japaneseFeed
        val item = listOf(TitleItem("ja2", "2026年11月期 決算短信", "feed-ja"))
        assertTrue(f.translator.request(item, "screen", Priority.VISIBLE).isEmpty())
        assertEquals(1, f.recentTitleCalls)

        // Even if the feed's recent titles later look different, the stored estimate stays.
        f.feedTitles["feed-ja"] = chineseFeed
        assertTrue(f.translator.request(item, "screen", Priority.VISIBLE).isEmpty())
        assertEquals(1, f.recentTitleCalls, "the stored estimate is used, not a new one")
        assertEquals(FeedLanguage.JAPANESE, f.cache.feedMeta(listOf("feed-ja"))["feed-ja"]?.language)
    }

    @Test
    fun feedWithTooFewTitlesFallsBackToTheScriptAndIsAskedAgainLater() = runTest {
        val f = Fixture(this)
        f.feedTitles["feed-new"] = listOf("お知らせ")
        val kana = TitleItem("n1", "お知らせを公開しました", "feed-new")
        val english = TitleItem("n2", "hello world", "feed-new")
        f.translator.request(listOf(kana, english), "screen", Priority.VISIBLE).awaitAll()
        assertEquals(setOf("n2"), f.translator.lookup(listOf(kana, english)).keys)
        assertEquals(null, f.cache.feedMeta(listOf("feed-new"))["feed-new"]?.language)
    }

    @Test
    fun feedSetToOffIsNotTranslated() = runTest {
        val f = Fixture(this)
        f.cache.setFeedMode("feed-en", FeedTranslationMode.OFF)
        assertTrue(f.translator.request(listOf(en(1)), "screen", Priority.VISIBLE).isEmpty())
    }

    @Test
    fun duplicateItemsAreQueuedOnce() = runTest {
        val f = Fixture(this)
        val outcomes = f.translator.request(listOf(en(1), en(1)), "screen", Priority.VISIBLE).awaitAll()
        assertEquals(1, outcomes.size)
        assertEquals(1, f.engine.translated.size)
    }
}
