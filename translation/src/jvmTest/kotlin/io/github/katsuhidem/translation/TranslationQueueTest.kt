package io.github.katsuhidem.translation

import io.github.katsuhidem.translation.TranslationQueue.Outcome
import io.github.katsuhidem.translation.TranslationQueue.Priority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationQueueTest {
    private class Fixture(scope: TestScope, idleUnloadMillis: Long = 60_000) {
        val engine = FakeEngine()
        val cache = newCache(StandardTestDispatcher(scope.testScheduler))
        val queue = TranslationQueue(engine, cache, scope.backgroundScope, idleUnloadMillis)

        /** Makes the next translation wait until [release] is called. */
        fun hold(): CompletableDeferred<Unit> {
            engine.started = CompletableDeferred()
            return CompletableDeferred<Unit>().also { engine.gate = it }
        }
    }

    @Test
    fun completedTranslationIsStoredAndReturned() = runTest {
        val f = Fixture(this)
        val plan = planFor("hello")
        val outcome = f.queue.submit(plan, owner = "batch", priority = Priority.BATCH).await()

        assertEquals(Outcome.Success("HELLO"), outcome)
        assertEquals(CacheResult.Hit("HELLO"), f.cache.lookup(listOf(plan.key))[plan.key])
        assertEquals(1, f.engine.loadCount)
    }

    @Test
    fun modelIsNotLoadedUntilSomethingIsSubmitted() = runTest {
        val f = Fixture(this)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, f.engine.loadCount)
    }

    @Test
    fun visibleRequestsRunBeforeBatchRequests() = runTest {
        val f = Fixture(this)
        val gate = f.hold()
        val first = f.queue.submit(planFor("batch-a"), "batch", Priority.BATCH)
        f.engine.started!!.await()
        val b = f.queue.submit(planFor("batch-b"), "batch", Priority.BATCH)
        val c = f.queue.submit(planFor("batch-c"), "batch", Priority.BATCH)
        val v = f.queue.submit(planFor("visible"), "screen", Priority.VISIBLE)
        gate.complete(Unit)
        listOf(first, b, c, v).forEach { it.await() }

        assertEquals(listOf("batch-a", "visible", "batch-b", "batch-c"), f.engine.translated)
    }

    @Test
    fun screenCancelDoesNotRemoveTheBatchRequestForTheSameText() = runTest {
        val f = Fixture(this)
        val gate = f.hold()
        f.queue.submit(planFor("running"), "batch", Priority.BATCH)
        f.engine.started!!.await()

        val shared = planFor("shared")
        val fromBatch = f.queue.submit(shared, "batch", Priority.BATCH)
        val fromScreen = f.queue.submit(shared, "screen", Priority.VISIBLE)
        val screenOnly = f.queue.submit(planFor("screen-only"), "screen", Priority.VISIBLE)
        assertEquals(3, f.queue.size(), "the same key is queued once")

        f.queue.cancel("screen")
        gate.complete(Unit)

        assertEquals(Outcome.Cancelled, screenOnly.await())
        assertEquals(Outcome.Success("SHARED"), fromBatch.await())
        assertEquals(Outcome.Success("SHARED"), fromScreen.await())
        assertFalse("screen-only" in f.engine.translated)
    }

    @Test
    fun cancellingTheRunningRequestStopsItAndStoresNothing() = runTest {
        val f = Fixture(this)
        val gate = f.hold()
        val running = planFor("running")
        val result = f.queue.submit(running, "screen", Priority.VISIBLE)
        f.engine.started!!.await()
        val next = f.queue.submit(planFor("next"), "batch", Priority.BATCH)

        f.queue.cancel("screen")
        assertEquals(Outcome.Cancelled, result.await())
        assertEquals(CacheResult.Missing, f.cache.lookup(listOf(running.key))[running.key])

        // The queue goes on with the next request (which may already be waiting at the gate).
        gate.complete(Unit)
        assertEquals(Outcome.Success("NEXT"), next.await())
        assertFalse("running" in f.engine.translated)
    }

    @Test
    fun truncatedResultIsRecordedAsFailureAndNotStored() = runTest {
        val f = Fixture(this)
        f.engine.terminal = { TranslationEvent.Truncated(it.uppercase()) }
        val plan = planFor("endless")
        val outcome = f.queue.submit(plan, "batch", Priority.BATCH).await()

        assertEquals(Outcome.Failure(FailureReason.TRUNCATED), outcome)
        assertEquals(CacheResult.Failed(FailureReason.TRUNCATED), f.cache.lookup(listOf(plan.key))[plan.key])
    }

    @Test
    fun overBudgetInputIsRejectedBeforeInference() = runTest {
        val f = Fixture(this)
        f.engine.tokensFor = { 2_000 } // prompt 2000 + budget 3 x 1999 > 4096
        val plan = planFor("huge")
        val outcome = f.queue.submit(plan, "batch", Priority.BATCH).await()

        assertEquals(Outcome.Failure(FailureReason.OVER_BUDGET), outcome)
        assertTrue(f.engine.translated.isEmpty(), "no inference for an over-budget input")
        assertEquals(CacheResult.Failed(FailureReason.OVER_BUDGET), f.cache.lookup(listOf(plan.key))[plan.key])
    }

    @Test
    fun outputBudgetFollowsTheSourceLength() = runTest {
        val f = Fixture(this)
        f.queue.submit(planFor("a".repeat(100) + " b"), "batch", Priority.BATCH).await()
        f.queue.submit(planFor("short one"), "batch", Priority.BATCH).await()
        // FakeEngine counts one token per character.
        assertEquals(listOf(3 * 102, 64), f.engine.maxTokensSeen)
    }

    @Test
    fun engineErrorsAndMissingModelAreNotRecorded() = runTest {
        val f = Fixture(this)
        f.engine.failLoad = true
        val noModel = planFor("no model")
        assertEquals(Outcome.Error("no model"), f.queue.submit(noModel, "batch", Priority.BATCH).await())
        assertEquals(CacheResult.Missing, f.cache.lookup(listOf(noModel.key))[noModel.key])

        f.engine.failLoad = false
        f.engine.terminal = { TranslationEvent.Failed("out of memory") }
        val failed = planFor("engine error")
        assertEquals(Outcome.Error("out of memory"), f.queue.submit(failed, "batch", Priority.BATCH).await())
        assertEquals(CacheResult.Missing, f.cache.lookup(listOf(failed.key))[failed.key])
    }

    @Test
    fun failedValidationIsRecordedAsCodeCheck() = runTest {
        val f = Fixture(this)
        val plan = planFor("run npm install now")
        val outcome = f.queue.submit(plan, "screen", Priority.VISIBLE, validate = { "npm install" in it }).await()
        // The fake upper-cases everything, so the code did not survive.
        assertEquals(Outcome.Failure(FailureReason.CODE_CHECK), outcome)
        assertEquals(CacheResult.Failed(FailureReason.CODE_CHECK), f.cache.lookup(listOf(plan.key))[plan.key])
    }

    @Test
    fun deltasReachTheListener() = runTest {
        val f = Fixture(this)
        val deltas = mutableListOf<String>()
        f.queue.submit(planFor("hello"), "screen", Priority.VISIBLE, onDelta = { deltas += it }).await()
        assertEquals(listOf("H"), deltas)
    }

    @Test
    fun modelIsUnloadedAfterIdleTimeButNeverWhileRunning() = runTest {
        val f = Fixture(this, idleUnloadMillis = 1_000)
        val gate = f.hold()
        val result = f.queue.submit(planFor("slow"), "batch", Priority.BATCH)
        f.engine.started!!.await()

        advanceTimeBy(5_000) // far longer than the idle time, with an inference running
        runCurrent()
        assertEquals(0, f.engine.unloadCount)
        assertTrue(f.engine.loaded)

        gate.complete(Unit)
        assertEquals(Outcome.Success("SLOW"), result.await())
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, f.engine.unloadCount, "still within the idle time")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, f.engine.unloadCount)

        // The next request loads it again.
        f.engine.gate = null
        assertEquals(Outcome.Success("AGAIN"), f.queue.submit(planFor("again"), "batch", Priority.BATCH).await())
        assertEquals(2, f.engine.loadCount)
    }
}
