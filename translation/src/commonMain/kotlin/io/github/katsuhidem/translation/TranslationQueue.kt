package io.github.katsuhidem.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs translations one at a time.
 *
 * - Requests for what is on screen ([Priority.VISIBLE]) run before batch requests.
 * - Each request has an owner. [cancel] removes one owner; a request shared
 *   with another owner keeps running, so a screen's cancel never removes a
 *   batch request for the same text.
 * - The model is loaded only when there is something to translate, and
 *   unloaded after [idleUnloadMillis] without work. It is never unloaded
 *   while an inference is running: unloading happens in the same loop.
 * - The output budget is proportional to the source's token count, and the
 *   real token count is checked right before inference.
 * - Only a normal finish is stored. A cut-off result and an over-budget
 *   input are recorded as failures; cancellation and engine errors are not
 *   recorded, so they are tried again next time.
 *
 * The queue does not read the cache. Callers submit only what the cache
 * reports as missing (or what the user asked to retry).
 */
class TranslationQueue(
    private val engine: TranslationEngine,
    private val cache: TranslationCache,
    scope: CoroutineScope,
    private val idleUnloadMillis: Long = DEFAULT_IDLE_UNLOAD_MILLIS,
) {
    enum class Priority { VISIBLE, BATCH }

    sealed interface Outcome {
        data class Success(val text: String) : Outcome
        data class Failure(val reason: FailureReason) : Outcome
        data object Cancelled : Outcome

        /** A transient problem (no model, engine error). Not recorded. */
        data class Error(val message: String) : Outcome
    }

    private class Entry(
        val plan: TranslationPlan,
        var priority: Priority,
        val seq: Long,
        val validate: ((String) -> Boolean)?,
        val persist: Boolean,
    ) {
        val owners = mutableSetOf<Any>()
        val listeners = mutableListOf<(String) -> Unit>()
        val result = CompletableDeferred<Outcome>()
        var job: Job? = null
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<TranslationKey, Entry>()
    private var running: Entry? = null
    private var seq = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { workerLoop() }
    }

    /**
     * Adds a request. A request with the same key that is already queued or
     * running is shared: the owner is added to it and the better priority wins.
     *
     * @param validate checks a finished text (for example that inline code
     *   survived); a text that fails is recorded as [FailureReason.CODE_CHECK]
     * @param onDelta called with the text so far while it is generated
     * @param persist false leaves storing a successful result to the caller (article blocks are
     *   stored only when all their pieces succeeded); failures are recorded either way
     */
    suspend fun submit(
        plan: TranslationPlan,
        owner: Any,
        priority: Priority,
        validate: ((String) -> Boolean)? = null,
        onDelta: ((String) -> Unit)? = null,
        persist: Boolean = true,
    ): Deferred<Outcome> = mutex.withLock {
        val entry = entries[plan.key]?.also {
            if (priority < it.priority) it.priority = priority
        } ?: Entry(plan, priority, seq++, validate, persist).also {
            entries[plan.key] = it
            wake.trySend(Unit)
        }
        entry.owners += owner
        if (onDelta != null) entry.listeners += onDelta
        entry.result
    }

    /** Withdraws [owner] from all its requests. Requests left without an owner are dropped or stopped. */
    suspend fun cancel(owner: Any) {
        mutex.withLock {
            val iterator = entries.values.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!entry.owners.remove(owner) || entry.owners.isNotEmpty()) continue
                if (entry === running) {
                    entry.job?.cancel()
                } else {
                    iterator.remove()
                    entry.result.complete(Outcome.Cancelled)
                }
            }
        }
    }

    /** Number of requests queued or running. */
    suspend fun size(): Int = mutex.withLock { entries.size }

    private suspend fun workerLoop() {
        while (true) {
            val next = mutex.withLock {
                entries.values
                    .minWithOrNull(compareBy<Entry>({ it.priority.ordinal }, { it.seq }))
                    ?.also { running = it }
            }
            if (next == null) {
                val woke = withTimeoutOrNull(idleUnloadMillis) { wake.receive() }
                if (woke == null && engine.isLoaded() && mutex.withLock { entries.isEmpty() }) {
                    engine.unload()
                }
                continue
            }
            val outcome = runEntry(next)
            mutex.withLock {
                entries.remove(next.plan.key)
                running = null
            }
            next.result.complete(outcome)
        }
    }

    private suspend fun runEntry(entry: Entry): Outcome = coroutineScope {
        val work = async { execute(entry) }
        mutex.withLock {
            entry.job = work
            // The last owner may have left between being picked and getting here.
            if (entry.owners.isEmpty()) work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // rethrows when the queue itself is being cancelled
            Outcome.Cancelled
        }
    }

    private suspend fun execute(entry: Entry): Outcome {
        val plan = entry.plan
        try {
            engine.ensureLoaded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Error(e.message ?: "model is not available")
        }

        val prompt = plan.prompt()
        val promptTokens = engine.countTokens(prompt)
        // countTokens includes the BOS; the budget is based on the source alone.
        val sourceTokens = (engine.countTokens(plan.source) - 1).coerceAtLeast(1)
        val maxTokens = TokenBudget.maxOutputTokens(sourceTokens)
        if (!TokenBudget.fits(promptTokens, maxTokens)) {
            cache.saveFailure(plan.key, FailureReason.OVER_BUDGET)
            return Outcome.Failure(FailureReason.OVER_BUDGET)
        }

        var outcome: Outcome = Outcome.Error("engine ended without a result")
        engine.translate(prompt, maxTokens).collect { event ->
            when (event) {
                is TranslationEvent.Delta -> entry.listeners.forEach { it(event.text) }
                is TranslationEvent.Completed -> outcome = finish(entry, event.text)
                is TranslationEvent.Truncated -> {
                    cache.saveFailure(plan.key, FailureReason.TRUNCATED)
                    outcome = Outcome.Failure(FailureReason.TRUNCATED)
                }
                TranslationEvent.Cancelled -> outcome = Outcome.Cancelled
                is TranslationEvent.Failed -> outcome = Outcome.Error(event.message)
            }
        }
        return outcome
    }

    private suspend fun finish(entry: Entry, raw: String): Outcome {
        val text = raw.trim()
        if (text.isEmpty()) return Outcome.Error("empty translation")
        if (entry.validate?.invoke(text) == false) {
            cache.saveFailure(entry.plan.key, FailureReason.CODE_CHECK)
            return Outcome.Failure(FailureReason.CODE_CHECK)
        }
        if (entry.persist) cache.saveSuccess(entry.plan.key, text)
        return Outcome.Success(text)
    }

    companion object {
        const val DEFAULT_IDLE_UNLOAD_MILLIS = 60_000L
    }
}
