package io.github.katsuhidem.translation

import io.github.katsuhidem.llama.LlamaBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * [TranslationEngine] on llama.cpp, CPU only.
 *
 * Native calls block, so they run on one worker thread. That thread also
 * keeps them in order: a call that was cancelled finishes in native code
 * before the next call (or an unload) starts.
 *
 * @param modelFile the model to load, or null when none is installed
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlamaCppEngine(
    private val modelFile: () -> File?,
    private val threads: Int = DEFAULT_THREADS,
    private val worker: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : TranslationEngine {
    private val callIds = AtomicLong(0)

    @Volatile
    private var loaded = false

    override fun isLoaded(): Boolean = loaded

    override suspend fun ensureLoaded() = withContext(worker) {
        if (loaded) return@withContext
        val file = modelFile() ?: throw IllegalStateException("translation model is not installed")
        if (!LlamaBridge.load(file.absolutePath, TokenBudget.N_CTX, threads)) {
            throw IllegalStateException("translation model could not be loaded")
        }
        loaded = true
    }

    override suspend fun countTokens(text: String): Int = withContext(worker) {
        LlamaBridge.countTokens(text).also { check(it >= 0) { "model is not loaded" } }
    }

    override fun translate(prompt: String, maxTokens: Int): Flow<TranslationEvent> = callbackFlow {
        val callId = callIds.incrementAndGet()
        launch(worker) {
            val result = LlamaBridge.translate(callId, prompt, maxTokens) { text ->
                trySend(TranslationEvent.Delta(text))
            }
            send(
                when (result.finish) {
                    LlamaBridge.Finish.STOP -> TranslationEvent.Completed(result.text)
                    LlamaBridge.Finish.MAX_TOKENS -> TranslationEvent.Truncated(result.text)
                    LlamaBridge.Finish.CANCELLED -> TranslationEvent.Cancelled
                    LlamaBridge.Finish.ERROR -> TranslationEvent.Failed(result.error ?: "inference failed")
                },
            )
            close()
        }
        // Runs when the collector goes away (and, harmlessly, after a normal end).
        awaitClose { LlamaBridge.cancel(callId) }
    }.conflate() // a slow collector skips intermediate deltas; the last event is always kept

    override suspend fun unload() = withContext(worker) {
        if (!loaded) return@withContext
        LlamaBridge.unload()
        loaded = false
    }

    companion object {
        /** Measured in Phase 0c with four threads. */
        const val DEFAULT_THREADS = 4
    }
}
