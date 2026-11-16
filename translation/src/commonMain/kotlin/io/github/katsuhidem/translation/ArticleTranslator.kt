package io.github.katsuhidem.translation

import io.github.katsuhidem.translation.TranslationQueue.Outcome
import io.github.katsuhidem.translation.TranslationQueue.Priority
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** One translatable block of an article, as the reader page reported it. */
data class ArticleBlock(
    val id: Int,
    val text: String,
    /** Inline code inside [text]. */
    val codes: List<ProtectedSpan> = emptyList(),
)

enum class BlockState {
    /** Not translated: Japanese, too short to judge, or empty. */
    SKIPPED,

    /** Waiting to be translated. */
    PENDING,
    RUNNING,
    DONE,

    /** Failed for a reason that will repeat; recorded, and retried only when the user asks. */
    FAILED,

    /** Cancelled or stopped by a transient error; not recorded. */
    INTERRUPTED,
}

sealed interface BlockUpdate {
    val blockId: Int

    /** Text so far, for blocks that are shown while they are generated. */
    data class Partial(override val blockId: Int, val text: String) : BlockUpdate
    data class Done(override val blockId: Int, val text: String) : BlockUpdate

    /** No translation is shown for this block (it has none, or its run failed or was cancelled). */
    data class Cleared(override val blockId: Int) : BlockUpdate
}

class RunSummary(
    val done: Int,
    val failed: Int,
    /** Cancelled, stopped by an error, or (after a cached-only run) still waiting. */
    val interrupted: Int,
    val skipped: Int,
    /** Blocks were left untranslated because no model is installed. */
    val modelMissing: Boolean,
) {
    /** True when the user can usefully press "retry". */
    val canRetry: Boolean get() = failed > 0 || interrupted > 0
}

/**
 * Side-by-side translation of an article's text.
 *
 * The block list is taken once per shown article ([open]) and kept; retries
 * and re-translation after a settings change work from that list, never
 * from the page again.
 */
class ArticleTranslator(
    private val cache: TranslationCache,
    private val queue: TranslationQueue,
    private val config: suspend () -> TranslationConfig,
    private val modelRev: () -> String,
    private val canTranslate: () -> Boolean,
    private val nowMillis: () -> Long,
) {
    /**
     * @param languageTag language of the article when known; only used as a hint for the source language
     */
    fun open(
        urlHash: String,
        title: String?,
        blocks: List<ArticleBlock>,
        languageTag: String? = null,
    ): ArticleSession = ArticleSession(this, urlHash, title, blocks, languageTag)

    internal suspend fun currentConfig() = config()
    internal fun currentModelRev() = modelRev()
    internal fun modelInstalled() = canTranslate()
    internal fun now() = nowMillis()
    internal val translationCache get() = cache
    internal val translationQueue get() = queue
}

/**
 * The blocks of one shown article and their translation state.
 *
 * Extraction and translation are separate: the session exists as soon as
 * the blocks are known, whether or not anything was translated.
 */
class ArticleSession internal constructor(
    private val translator: ArticleTranslator,
    private val urlHash: String,
    private val title: String?,
    private val blocks: List<ArticleBlock>,
    private val languageTag: String?,
) {
    private val _states = MutableStateFlow(blocks.associate { it.id to BlockState.PENDING })
    val states: StateFlow<Map<Int, BlockState>> = _states.asStateFlow()

    private class Piece(val plan: TranslationPlan, val tokens: List<Pair<String, String>>)

    private class Planned(
        val block: ArticleBlock,
        val pieces: List<Piece>,
        val tokens: List<Pair<String, String>>,
        val joiner: String,
    ) {
        /** Shown while generated only when it is one piece without code. */
        val streams get() = pieces.size == 1 && tokens.isEmpty()
    }

    /**
     * One translation run.
     *
     * - Blocks found in the cache are reported at once; no model is loaded for them.
     * - Blocks recorded as failed stay untranslated unless [retryFailed] is set
     *   (the user pressed "retry").
     * - Only the rest is queued, in document order.
     *
     * Every block gets one first update ([BlockUpdate.Done] or
     * [BlockUpdate.Cleared]), so translations of an earlier run that no longer
     * apply (after a style change) disappear.
     *
     * Cancelling the caller cancels this session's requests only.
     *
     * @param cachedOnly show what the cache has and stop: nothing is queued
     *   and no model is loaded. Used when an article is opened, before the
     *   user asks for a translation.
     */
    suspend fun run(
        retryFailed: Boolean,
        cachedOnly: Boolean = false,
        onUpdate: (BlockUpdate) -> Unit,
    ): RunSummary {
        val config = translator.currentConfig()
        val modelRev = translator.currentModelRev()
        val cache = translator.translationCache

        val planned = plan(config, modelRev)
        val results = cache.lookup(planned.flatMap { p -> p.pieces.map { it.plan.key } })
        val states = blocks.associateTo(LinkedHashMap()) { it.id to BlockState.SKIPPED }
        val toRun = mutableListOf<Planned>()
        for (p in planned) {
            val piecesResults = p.pieces.map { results[it.plan.key] }
            when {
                piecesResults.all { it is CacheResult.Hit } -> {
                    states[p.block.id] = BlockState.DONE
                    onUpdate(BlockUpdate.Done(p.block.id, assemble(p, piecesResults.map { (it as CacheResult.Hit).text })))
                }
                piecesResults.any { it is CacheResult.Failed } && !retryFailed -> states[p.block.id] = BlockState.FAILED
                else -> {
                    states[p.block.id] = BlockState.PENDING
                    toRun += p
                }
            }
        }
        val plannedIds = planned.mapTo(HashSet()) { it.block.id }
        for (block in blocks) {
            if (states[block.id] != BlockState.DONE) onUpdate(BlockUpdate.Cleared(block.id))
            if (block.id !in plannedIds) states[block.id] = BlockState.SKIPPED
        }
        _states.value = states.toMap()

        val modelMissing = !cachedOnly && toRun.isNotEmpty() && !translator.modelInstalled()
        if (!cachedOnly && toRun.isNotEmpty() && !modelMissing) translate(toRun, onUpdate)

        val end = _states.value
        return RunSummary(
            done = end.values.count { it == BlockState.DONE },
            failed = end.values.count { it == BlockState.FAILED },
            interrupted = end.values.count { it == BlockState.INTERRUPTED || it == BlockState.PENDING },
            skipped = end.values.count { it == BlockState.SKIPPED },
            modelMissing = modelMissing,
        )
    }

    private suspend fun translate(toRun: List<Planned>, onUpdate: (BlockUpdate) -> Unit) {
        val queue = translator.translationQueue
        val owner = Any()
        try {
            // Queue everything first, in document order, so translations appear top to bottom.
            val submitted = toRun.map { p ->
                var lastPartial: Long? = null
                val onDelta: ((String) -> Unit)? = if (!p.streams) {
                    null
                } else {
                    { text ->
                        // Pass partial text on at most every PARTIAL_INTERVAL_MILLIS.
                        val now = translator.now()
                        val last = lastPartial
                        if ((last == null || now - last >= PARTIAL_INTERVAL_MILLIS) && text.isNotBlank()) {
                            lastPartial = now
                            setState(p.block.id, BlockState.RUNNING)
                            onUpdate(BlockUpdate.Partial(p.block.id, text))
                        }
                    }
                }
                p to p.pieces.map { piece ->
                    queue.submit(
                        plan = piece.plan,
                        owner = owner,
                        priority = Priority.VISIBLE,
                        validate = { CodeProtection.verify(it, piece.tokens) },
                        onDelta = onDelta,
                        persist = false,
                    )
                }
            }
            for ((p, deferred) in submitted) {
                val outcomes = deferred.awaitAll()
                if (outcomes.all { it is Outcome.Success }) {
                    val texts = outcomes.map { (it as Outcome.Success).text }
                    // All pieces or none: a block is stored only as a whole.
                    translator.translationCache.saveSuccessAll(p.pieces.map { it.plan.key }.zip(texts))
                    setState(p.block.id, BlockState.DONE)
                    onUpdate(BlockUpdate.Done(p.block.id, assemble(p, texts)))
                } else {
                    val recorded = outcomes.any { it is Outcome.Failure }
                    setState(p.block.id, if (recorded) BlockState.FAILED else BlockState.INTERRUPTED)
                    onUpdate(BlockUpdate.Cleared(p.block.id))
                }
            }
        } finally {
            withContext(NonCancellable) {
                queue.cancel(owner)
                // Whatever did not finish was interrupted; a partial text must not stay on screen.
                val unfinished = _states.value.filterValues { it == BlockState.PENDING || it == BlockState.RUNNING }
                _states.update { current -> current + unfinished.mapValues { BlockState.INTERRUPTED } }
                unfinished.filterValues { it == BlockState.RUNNING }.keys.forEach { onUpdate(BlockUpdate.Cleared(it)) }
            }
        }
    }

    private fun setState(blockId: Int, state: BlockState) {
        _states.update { it + (blockId to state) }
    }

    /** Joins the pieces and puts the code back. */
    private fun assemble(p: Planned, texts: List<String>): String =
        CodeProtection.restore(texts.joinToString(p.joiner), p.tokens)

    /**
     * Plans every block that should be translated. Purely character-based:
     * the same blocks give the same pieces and keys with or without a model.
     */
    private fun plan(config: TranslationConfig, modelRev: String): List<Planned> {
        val out = mutableListOf<Planned>()
        // A language tag is only a hint for the source language; whether a block
        // is translated is judged from its own text and from the article as a whole
        // (LanguageGate.decideBlock), since the user asked for this article.
        val hint = LanguageGate.modelLanguageCode(languageTag).takeUnless { it == "ja" }
        val prepared = blocks.map { block ->
            val protected = CodeProtection.protect(block.text, block.codes)
            // Judge on the prose alone: code would count as letters.
            protected to protected.placeholders.fold(protected.text) { acc, (token, _) -> acc.replace(token, " ") }
        }
        val articleLanguage = LanguageGate.estimateArticleLanguage(prepared.map { it.second })
        var previous: ArticleBlock? = null
        for ((index, block) in blocks.withIndex()) {
            val context = contextFor(previous)
            previous = block
            val (protected, prose) = prepared[index]
            if (LanguageGate.decideBlock(prose, articleLanguage, protected.placeholders.size) is GateDecision.Skip) continue

            val pieces = TokenBudget.split(protected.text, CodeProtection::placeholderRanges).map { pieceText ->
                val tokens = CodeProtection.tokensIn(pieceText, protected.placeholders)
                val spans = tokens.map { (token, code) -> ProtectedSpan(pieceText.indexOf(token), code) }
                Piece(
                    plan = TranslationPlanner.planDecided(
                        urlHash = urlHash,
                        field = TranslationField.BLOCK,
                        text = pieceText,
                        sourceLang = hint,
                        config = config,
                        modelRev = modelRev,
                        context = context,
                        protectedSpans = spans,
                    ),
                    tokens = tokens,
                )
            }
            if (pieces.isEmpty()) continue
            out += Planned(block, pieces, protected.placeholders, if (config.targetLang == "ja") "" else " ")
        }
        return out
    }

    /**
     * Reference text for the model: the article title and the start of the
     * block before this one. Cut by characters, so it is the same every time,
     * and the same for every piece of a split block.
     */
    private fun contextFor(previous: ArticleBlock?): String? {
        val parts = listOfNotNull(
            title?.trim()?.takeIf { it.isNotEmpty() },
            previous?.text?.take(PREVIOUS_BLOCK_CHARS)?.trim()?.takeIf { it.isNotEmpty() },
        )
            .distinct() // the block after the title would otherwise get the title twice
        if (parts.isEmpty()) return null
        return TokenBudget.truncateContext(parts.joinToString("\n"), TokenBudget.Keep.HEAD)
    }

    private companion object {
        const val PARTIAL_INTERVAL_MILLIS = 50L
        const val PREVIOUS_BLOCK_CHARS = 200
    }
}
