package io.github.katsuhidem.translation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationCacheTest {
    @Test
    fun lookupSeparatesHitFailedAndMissing() = runTest {
        val cache = newCache(StandardTestDispatcher(testScheduler))
        val hit = planFor("Hello").key
        val failed = planFor("Too long").key
        val missing = planFor("New one").key
        cache.saveSuccess(hit, "こんにちは")
        cache.saveFailure(failed, FailureReason.TRUNCATED)

        val result = cache.lookup(listOf(hit, failed, missing))
        assertEquals(CacheResult.Hit("こんにちは"), result[hit])
        assertEquals(CacheResult.Failed(FailureReason.TRUNCATED), result[failed])
        assertEquals(CacheResult.Missing, result[missing])
    }

    @Test
    fun forgetRemovesOneArticleAndLeavesTheOthers() = runTest {
        val cache = newCache(StandardTestDispatcher(testScheduler))
        val gone = planFor("Hello", urlHash = "u1").key
        val goneFailure = planFor("Too long", urlHash = "u1").key
        val kept = planFor("Hello", urlHash = "u2").key
        cache.saveSuccess(gone, "こんにちは")
        cache.saveFailure(goneFailure, FailureReason.TRUNCATED)
        cache.saveSuccess(kept, "こんにちは")

        cache.forget("u1")

        val result = cache.lookup(listOf(gone, goneFailure, kept))
        assertEquals(CacheResult.Missing, result[gone])
        assertEquals(CacheResult.Missing, result[goneFailure])
        assertEquals(CacheResult.Hit("こんにちは"), result[kept])
    }

    @Test
    fun changedConditionDoesNotReturnTheOldTranslation() = runTest {
        val cache = newCache(StandardTestDispatcher(testScheduler))
        val plain = planFor("Hello", urlHash = "u1", config = TranslationConfig(style = TranslationStyle.PLAIN)).key
        val polite = planFor("Hello", urlHash = "u1", config = TranslationConfig(style = TranslationStyle.POLITE)).key
        val edited = planFor("Hello!", urlHash = "u1", config = TranslationConfig(style = TranslationStyle.PLAIN)).key
        val otherModel = planFor(
            "Hello", urlHash = "u1", config = TranslationConfig(style = TranslationStyle.PLAIN), modelRev = "rev-b",
        ).key
        cache.saveSuccess(plain, "やあ")

        val result = cache.lookup(listOf(plain, polite, edited, otherModel))
        assertEquals(CacheResult.Hit("やあ"), result[plain])
        assertEquals(CacheResult.Missing, result[polite])
        assertEquals(CacheResult.Missing, result[edited])
        assertEquals(CacheResult.Missing, result[otherModel])
    }

    @Test
    fun successRemovesTheFailureOfTheSameKey() = runTest {
        val cache = newCache(StandardTestDispatcher(testScheduler))
        val key = planFor("Hello").key
        cache.saveFailure(key, FailureReason.CODE_CHECK)
        assertEquals(CacheResult.Failed(FailureReason.CODE_CHECK), cache.lookup(listOf(key))[key])

        cache.saveSuccess(key, "こんにちは")
        assertEquals(CacheResult.Hit("こんにちは"), cache.lookup(listOf(key))[key])
        // A later failure record for the same key does not hide the stored translation.
        cache.saveFailure(key, FailureReason.TRUNCATED)
        assertEquals(CacheResult.Hit("こんにちは"), cache.lookup(listOf(key))[key])
    }

    @Test
    fun savingNotifiesTheUrlHash() = runTest {
        val cache = newCache(StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<Set<String>>()
        val job = launch { cache.changes.collect { seen += it } }
        runCurrent()
        cache.saveSuccess(planFor("Hello", urlHash = "u1").key, "こんにちは")
        cache.saveFailure(planFor("Bye", urlHash = "u2").key, FailureReason.TRUNCATED)
        runCurrent()
        assertEquals(listOf(setOf("u1")), seen)
        job.cancel()
    }

    @Test
    fun pruneRemovesOldRowsThenTheOldestAboveTheLimit() = runTest {
        val clock = FakeClock(now = 0)
        val cache = newCache(StandardTestDispatcher(testScheduler), clock)
        val keys = (1..5).map { planFor("Title $it", urlHash = "u$it").key }
        keys.forEachIndexed { i, key ->
            clock.now = (i + 1) * 100L
            cache.saveSuccess(key, "訳$i")
        }
        clock.now = 600
        // Rows older than 350 ms go (created at 100 and 200); then keep the newest two.
        cache.prune(maxRows = 2, maxAgeMillis = 350)
        val result = cache.lookup(keys)
        assertEquals(
            listOf(false, false, false, true, true),
            keys.map { result[it] is CacheResult.Hit },
        )
    }

    @Test
    fun feedLanguageIsStoredOnceAndModeIsKept() = runTest {
        val cache = newCache(StandardTestDispatcher(testScheduler))
        assertNull(cache.feedMeta(listOf("f1"))["f1"])

        cache.setFeedLanguageIfAbsent("f1", FeedLanguage.JAPANESE)
        cache.setFeedLanguageIfAbsent("f1", FeedLanguage.OTHER)
        assertEquals(FeedTranslationMeta(FeedTranslationMode.AUTO, FeedLanguage.JAPANESE), cache.feedMeta(listOf("f1"))["f1"])

        cache.setFeedMode("f1", FeedTranslationMode.OFF)
        assertEquals(FeedTranslationMeta(FeedTranslationMode.OFF, FeedLanguage.JAPANESE), cache.feedMeta(listOf("f1"))["f1"])

        cache.setFeedMode("f2", FeedTranslationMode.ON)
        cache.setFeedLanguageIfAbsent("f2", FeedLanguage.OTHER)
        assertEquals(FeedTranslationMeta(FeedTranslationMode.ON, FeedLanguage.OTHER), cache.feedMeta(listOf("f2"))["f2"])
    }

    @Test
    fun lookupHandlesMoreKeysThanOneSqlChunk() = runTest {
        val cache = newCache(StandardTestDispatcher(testScheduler))
        val keys = (1..1200).map { planFor("Title $it", urlHash = "u$it").key }
        cache.saveSuccess(keys[0], "first")
        cache.saveSuccess(keys[1199], "last")
        val result = cache.lookup(keys)
        assertEquals(1200, result.size)
        assertEquals(CacheResult.Hit("first"), result[keys[0]])
        assertEquals(CacheResult.Hit("last"), result[keys[1199]])
        assertEquals(1198, result.values.count { it == CacheResult.Missing })
    }

    @Test
    fun batchedDeliversTogetherAtIntervalsAndNeverDropsTheLast() = runTest {
        val source = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
        val seen = mutableListOf<Set<String>>()
        val job = launch { source.batched(300).collect { seen += it } }
        runCurrent()

        source.emit(setOf("a"))
        source.emit(setOf("b"))
        advanceTimeBy(299)
        runCurrent()
        assertTrue(seen.isEmpty(), "nothing before the interval")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(setOf("a", "b")), seen)

        // Changes that keep coming are still delivered every interval (not a debounce).
        repeat(5) {
            source.emit(setOf("c$it"))
            advanceTimeBy(200)
            runCurrent()
        }
        assertTrue(seen.size >= 3, "delivered while changes keep coming: $seen")

        source.emit(setOf("last"))
        advanceTimeBy(300)
        runCurrent()
        assertTrue(seen.last().contains("last"))
        job.cancel()
    }

    @Test
    fun batchedFlushesWhenTheSourceEnds() = runTest {
        val result = flowOf(setOf("a"), setOf("b")).batched(300).toList()
        assertEquals(listOf(setOf("a", "b")), result)
    }
}
