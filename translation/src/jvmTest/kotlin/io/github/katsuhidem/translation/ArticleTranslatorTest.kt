package io.github.katsuhidem.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleTranslatorTest {
    private class Fixture(scope: TestScope, idleUnloadMillis: Long = 60_000) {
        val engine = FakeEngine()
        val cache = newCache(StandardTestDispatcher(scope.testScheduler))
        val queue = TranslationQueue(engine, cache, scope.backgroundScope, idleUnloadMillis)
        var config = TranslationConfig()
        var installed = true
        private var clock = 0L
        init {
            // Closer to the real tokenizer than the fake's default of one token per character,
            // so a full-size piece fits the context.
            engine.tokensFor = { it.length / 4 + 1 }
        }

        val translator = ArticleTranslator(
            cache = cache,
            queue = queue,
            config = { config },
            modelRev = { "rev-a" },
            canTranslate = { installed },
            nowMillis = { clock.also { clock += 100 } },
        )

        fun open(vararg blocks: ArticleBlock, title: String? = "An article") =
            translator.open("article-1", title, blocks.toList())
    }

    private class Updates {
        val all = mutableListOf<BlockUpdate>()
        val sink: (BlockUpdate) -> Unit = { all += it }
        fun done() = all.filterIsInstance<BlockUpdate.Done>().associate { it.blockId to it.text }
        fun partials() = all.filterIsInstance<BlockUpdate.Partial>().map { it.blockId }
        fun shown(blockId: Int): String? = when (val last = all.lastOrNull { it.blockId == blockId }) {
            is BlockUpdate.Done -> last.text
            is BlockUpdate.Partial -> last.text
            else -> null
        }
    }

    private val codeBlock = ArticleBlock(
        id = 3,
        text = "Run npm install first, then run npm install again with --force.",
        codes = listOf(ProtectedSpan(4, "npm install"), ProtectedSpan(32, "npm install"), ProtectedSpan(55, "--force")),
    )

    /**
     * Three pieces at sentence ends; the marker word makes the last piece
     * recognisable. The sentences differ, so no two pieces are the same text
     * (identical pieces share one cache key and are translated once).
     */
    private fun longBlock(id: Int, lastWord: String = "end"): ArticleBlock {
        val body = (1..60).joinToString(" ") { "Sentence number $it stands here for the test." }
        return ArticleBlock(id, "$body The $lastWord.")
    }

    @Test
    fun blocksAreTranslatedInDocumentOrderAndJapaneseOrShortOnesAreSkipped() = runTest {
        val f = Fixture(this)
        val session = f.open(
            ArticleBlock(1, "first paragraph"),
            ArticleBlock(2, "これは日本語の段落です。"),
            ArticleBlock(3, "1."),
            ArticleBlock(4, "second paragraph"),
            ArticleBlock(5, "npm install", codes = listOf(ProtectedSpan(0, "npm install"))),
        )
        val updates = Updates()
        val summary = session.run(retryFailed = false, onUpdate = updates.sink)

        assertEquals(mapOf(1 to "FIRST PARAGRAPH", 4 to "SECOND PARAGRAPH"), updates.done())
        assertEquals(listOf("first paragraph", "second paragraph"), f.engine.translated)
        assertEquals(2, summary.done)
        assertEquals(3, summary.skipped)
        assertFalse(summary.canRetry)
        assertEquals(BlockState.SKIPPED, session.states.value[2])
        assertEquals(BlockState.SKIPPED, session.states.value[5], "a block that is only code has nothing to translate")
    }

    @Test
    fun inAJapaneseArticleNamesLabelsAndKanaLessHeadingsStayAsTheyAre() = runTest {
        val f = Fixture(this)
        val quote = "Simple things should be simple, complex things should be possible."
        val session = f.open(
            ArticleBlock(1, "nrs"),
            ArticleBlock(2, "大量のプルリクエストをまとめて処理する方法を紹介します。"),
            ArticleBlock(3, "同時実行数"),
            ArticleBlock(4, "同時に実行する数は設定で変えられます。"),
            ArticleBlock(5, "CodeRabbit対応"),
            ArticleBlock(6, "レビューの指摘にも自動で対応します。"),
            ArticleBlock(7, quote),
            ArticleBlock(8, "この言葉のとおりに作りました。"),
            ArticleBlock(9, "まとめると、手作業が減ります。"),
            ArticleBlock(10, "Discussion"),
        )
        val updates = Updates()
        val summary = session.run(retryFailed = false, onUpdate = updates.sink)

        assertEquals(mapOf(7 to quote.uppercase()), updates.done(), "only the quoted English sentence is translated")
        assertEquals(listOf(quote), f.engine.translated)
        assertEquals(9, summary.skipped)
    }

    @Test
    fun plainBlocksShowTextWhileGeneratedAndCodeBlocksOnlyWhenVerified() = runTest {
        val f = Fixture(this)
        val session = f.open(ArticleBlock(1, "plain paragraph"), codeBlock)
        val updates = Updates()
        session.run(false, onUpdate = updates.sink)

        assertEquals(listOf(1), updates.partials(), "only the block without code streams")
        assertEquals(
            "RUN npm install FIRST, THEN RUN npm install AGAIN WITH --force.",
            updates.done()[3],
            "both occurrences of the same code are back, unchanged",
        )
    }

    @Test
    fun translationThatLostCodeIsNeverShownOrStoredAndIsNotTriedAgainByItself() = runTest {
        val f = Fixture(this)
        // The engine drops one placeholder.
        f.engine.terminal = { TranslationEvent.Completed(it.uppercase().replace("[[2]]", "")) }
        val session = f.open(codeBlock)
        val updates = Updates()
        val summary = session.run(false, onUpdate = updates.sink)

        assertNull(updates.shown(3))
        assertTrue(updates.all.none { it is BlockUpdate.Done || it is BlockUpdate.Partial })
        assertEquals(BlockState.FAILED, session.states.value[3])
        assertTrue(summary.canRetry)

        // "Translate" again: the failure record keeps it out of the queue.
        val before = f.engine.translated.size
        session.run(false, onUpdate = updates.sink)
        assertEquals(before, f.engine.translated.size)

        // "Retry" lifts the suppression; this time the engine behaves.
        f.engine.terminal = { TranslationEvent.Completed(it.uppercase()) }
        val retried = session.run(retryFailed = true, onUpdate = updates.sink)
        assertEquals(1, retried.done)
        assertEquals("RUN npm install FIRST, THEN RUN npm install AGAIN WITH --force.", updates.shown(3))
    }

    @Test
    fun splitBlockIsShownAndStoredOnlyWhenEveryPieceSucceeded() = runTest {
        val f = Fixture(this)
        val block = longBlock(1, lastWord = "poison")
        f.engine.terminal = { source ->
            if ("poison" in source) TranslationEvent.Truncated(source) else TranslationEvent.Completed(source.uppercase())
        }
        val session = f.open(block, title = null)
        val updates = Updates()
        val summary = session.run(false, onUpdate = updates.sink)

        assertEquals(3, f.engine.translated.size, "the block was split into three pieces")
        assertTrue(updates.all.none { it is BlockUpdate.Done || it is BlockUpdate.Partial }, "nothing of the block is shown")
        assertEquals(1, summary.failed)
        // None of the pieces that did succeed was stored.
        assertTrue(f.cache.lookup(emptyList()).isEmpty())
        val again = Updates()
        session.run(false, onUpdate = again.sink)
        assertTrue(again.done().isEmpty(), "no piece comes back from the cache")
    }

    @Test
    fun splitBlockWithCodeIsVerifiedPerPieceAndJoined() = runTest {
        val f = Fixture(this)
        val sentence = "This sentence is exactly forty chars long. "
        val head = "Run make first. "
        val tail = " Then run make again."
        val text = head + sentence.repeat(60).trim() + tail
        val block = ArticleBlock(
            id = 1,
            text = text,
            codes = listOf(ProtectedSpan(4, "make"), ProtectedSpan(text.length - "make again.".length, "make")),
        )
        val session = f.open(block, title = null)
        val updates = Updates()
        val summary = session.run(false, onUpdate = updates.sink)

        assertEquals(1, summary.done)
        assertTrue(f.engine.translated.size >= 3)
        assertTrue(updates.partials().isEmpty(), "a split block never shows partial text")
        val shown = updates.done().getValue(1)
        assertTrue(shown.startsWith("RUN make FIRST."), shown.take(40))
        assertTrue(shown.endsWith("THEN RUN make AGAIN."), shown.takeLast(40))
        assertFalse("[[" in shown)
    }

    @Test
    fun reopenedArticleComesFromTheCacheWithoutLoadingTheModel() = runTest {
        val f = Fixture(this, idleUnloadMillis = 1_000)
        val blocks = arrayOf(ArticleBlock(1, "first paragraph"), codeBlock)
        f.open(*blocks).run(false) {}
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, f.engine.unloadCount)

        val updates = Updates()
        val summary = f.open(*blocks).run(false, onUpdate = updates.sink)
        assertEquals(2, summary.done)
        assertEquals(setOf(1, 3), updates.done().keys)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, f.engine.loadCount, "no second load")
    }

    @Test
    fun changingTheStyleRemovesOldTranslationsAndTranslatesAgain() = runTest {
        val f = Fixture(this)
        val session = f.open(ArticleBlock(1, "first paragraph"))
        f.config = TranslationConfig(style = TranslationStyle.PLAIN)
        session.run(false) {}

        f.config = TranslationConfig(style = TranslationStyle.POLITE)
        f.engine.terminal = { TranslationEvent.Completed("polite: $it") }
        val updates = Updates()
        session.run(false, onUpdate = updates.sink)
        // The old text is cleared before the new one arrives.
        assertEquals(BlockUpdate.Cleared(1), updates.all.first())
        assertEquals("polite: first paragraph", updates.shown(1))
        assertEquals(2, f.engine.translated.size)
    }

    @Test
    fun cancellingARunStopsOnlyThisArticleAndCanBeRetried() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.engine.gate = gate
        f.engine.started = CompletableDeferred()
        val session = f.open(ArticleBlock(1, "first paragraph"), ArticleBlock(2, "second paragraph"))
        val updates = Updates()
        val job = launch { session.run(false, onUpdate = updates.sink) }
        f.engine.started!!.await()
        runCurrent()
        assertEquals(listOf(1), updates.partials())

        job.cancel()
        job.join()
        assertNull(updates.shown(1), "the partial text is removed")
        assertEquals(BlockState.INTERRUPTED, session.states.value[1])
        assertEquals(BlockState.INTERRUPTED, session.states.value[2])
        runCurrent() // let the worker notice the cancelled inference
        assertEquals(0, f.queue.size(), "this article's requests are gone from the queue")
        assertEquals(emptyList(), f.engine.translated, "nothing was translated to the end")

        // Retry from the kept block list.
        gate.complete(Unit)
        val retry = Updates()
        val summary = session.run(retryFailed = true, onUpdate = retry.sink)
        assertEquals(2, summary.done)
        assertEquals(setOf(1, 2), retry.done().keys)
    }

    @Test
    fun articleWithOnlyFailedBlocksDoesNotLoadTheModelUntilRetry() = runTest {
        val f = Fixture(this, idleUnloadMillis = 1_000)
        f.engine.terminal = { TranslationEvent.Truncated(it) }
        val session = f.open(ArticleBlock(1, "first paragraph"))
        session.run(false) {}
        advanceTimeBy(1_500)
        runCurrent()
        assertFalse(f.engine.loaded)
        val loads = f.engine.loadCount

        val summary = session.run(false) {}
        advanceTimeBy(100)
        runCurrent()
        assertEquals(loads, f.engine.loadCount, "\"translate\" with only failed blocks loads nothing")
        assertTrue(summary.canRetry)

        f.engine.terminal = { TranslationEvent.Completed(it.uppercase()) }
        assertEquals(1, session.run(retryFailed = true) {}.done)
        assertEquals(loads + 1, f.engine.loadCount)
    }

    @Test
    fun overBudgetBlockStaysOriginalAndIsNotRepeated() = runTest {
        val f = Fixture(this)
        f.engine.tokensFor = { 2_000 }
        val session = f.open(ArticleBlock(1, "a paragraph the tokenizer finds huge"))
        val updates = Updates()
        assertEquals(1, session.run(false, onUpdate = updates.sink).failed)
        assertNull(updates.shown(1))
        session.run(false, onUpdate = updates.sink)
        session.run(false, onUpdate = updates.sink)
        assertTrue(f.engine.translated.isEmpty(), "never sent to inference, and not counted again and again")
    }

    @Test
    fun withoutAModelNothingIsQueuedButCachedBlocksStillShow() = runTest {
        val f = Fixture(this)
        f.open(ArticleBlock(1, "first paragraph")).run(false) {}
        f.installed = false
        val updates = Updates()
        val summary = f.open(ArticleBlock(1, "first paragraph"), ArticleBlock(2, "second paragraph")).run(false, onUpdate = updates.sink)
        assertTrue(summary.modelMissing)
        assertEquals(setOf(1), updates.done().keys)
        assertEquals(1, f.engine.translated.size)
    }

    @Test
    fun cachedOnlyRunShowsWhatIsStoredAndTranslatesNothing() = runTest {
        val f = Fixture(this, idleUnloadMillis = 1_000)
        f.open(ArticleBlock(1, "first paragraph")).run(false) {}
        advanceTimeBy(1_500)
        runCurrent()
        val loads = f.engine.loadCount

        val session = f.open(ArticleBlock(1, "first paragraph"), ArticleBlock(2, "second paragraph"))
        val updates = Updates()
        val summary = session.run(retryFailed = false, cachedOnly = true, onUpdate = updates.sink)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(mapOf(1 to "FIRST PARAGRAPH"), updates.done())
        assertEquals(1, summary.done)
        assertFalse(summary.modelMissing)
        assertEquals(BlockState.PENDING, session.states.value[2])
        assertEquals(loads, f.engine.loadCount, "opening an article never loads the model")

        // The button then translates the rest from the same block list.
        assertEquals(2, session.run(false, onUpdate = updates.sink).done)
    }

    @Test
    fun contextIsTheTitleAndTheStartOfThePreviousBlock() = runTest {
        val f = Fixture(this)
        f.open(ArticleBlock(1, "first paragraph"), ArticleBlock(2, "second paragraph"), title = "The title").run(false) {}
        assertTrue("<|plamo:tag|>context<|plamo:msg|>The title<|plamo:tag|>input" in f.engine.prompts[0])
        assertTrue("<|plamo:tag|>context<|plamo:msg|>The title\nfirst paragraph<|plamo:tag|>input" in f.engine.prompts[1])
    }

    @Test
    fun sameBlocksGiveTheSameKeysWhetherOrNotAModelIsLoaded() = runTest {
        val f = Fixture(this)
        val blocks = arrayOf(longBlock(1), codeBlock)
        f.open(*blocks).run(false) {}
        val translatedOnce = f.engine.translated.size
        // A second session finds every piece in the cache: same split, same keys.
        val summary = f.open(*blocks).run(false) {}
        assertEquals(2, summary.done)
        assertEquals(translatedOnce, f.engine.translated.size)
    }
}
