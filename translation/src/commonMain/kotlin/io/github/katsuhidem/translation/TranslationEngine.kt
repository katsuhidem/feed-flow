package io.github.katsuhidem.translation

import kotlinx.coroutines.flow.Flow

/** Events of one translation. Exactly one of the terminal events ends the flow. */
sealed interface TranslationEvent {
    /** Progress: the translation so far. */
    data class Delta(val text: String) : TranslationEvent

    /** Finished at a stop token. The only result that is stored. */
    data class Completed(val text: String) : TranslationEvent

    /** Stopped at the output budget; the text is incomplete. */
    data class Truncated(val text: String) : TranslationEvent
    data object Cancelled : TranslationEvent
    data class Failed(val message: String) : TranslationEvent
}

/**
 * A translation engine. The first implementation runs llama.cpp on the
 * device; a remote engine can implement the same interface later.
 *
 * Calls are made one at a time by [TranslationQueue].
 */
interface TranslationEngine {
    fun isLoaded(): Boolean

    /** Loads the model if it is not loaded. Throws when no model is available. */
    suspend fun ensureLoaded()

    /** Token count of [text] as the model will see it, including the leading BOS. */
    suspend fun countTokens(text: String): Int

    /**
     * Translates [prompt] (see [PlamoPromptBuilder]), generating at most
     * [maxTokens]. Each call starts from a clean state. Cancelling the
     * collection stops the inference.
     */
    fun translate(prompt: String, maxTokens: Int): Flow<TranslationEvent>

    suspend fun unload()
}
