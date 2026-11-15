package io.github.katsuhidem.llama

/**
 * Kotlin side of llama_bridge.cpp. All calls except [cancel] block and
 * share one lock in native code; call them from a worker thread.
 */
object LlamaBridge {
    enum class Finish { STOP, MAX_TOKENS, CANCELLED, ERROR }

    class Result(val finish: Finish, val text: String, val error: String?)

    private val loadLibrary by lazy { System.loadLibrary("llamabridge") }

    /** Loads the model at [path]. Returns false when the file cannot be loaded. */
    fun load(path: String, nCtx: Int, nThreads: Int): Boolean {
        loadLibrary
        return nativeLoad(path, nCtx, nThreads)
    }

    fun isLoaded(): Boolean {
        loadLibrary
        return nativeIsLoaded()
    }

    /** Tokens of [text] including the leading BOS, or -1 when no model is loaded. */
    fun countTokens(text: String): Int = nativeCountTokens(text.encodeToByteArray())

    /**
     * Runs one translation and returns when it ends. [onText] gets the
     * text so far. [callId] identifies the call for [cancel]; use a new
     * value for every call.
     */
    fun translate(callId: Long, prompt: String, maxTokens: Int, onText: (String) -> Unit): Result {
        var result: Result? = null
        nativeTranslate(
            callId,
            prompt.encodeToByteArray(),
            maxTokens,
            object : Callback {
                override fun onPartial(utf8: ByteArray) = onText(utf8.decodeToString())

                override fun onFinish(reason: Int, utf8: ByteArray, error: String?) {
                    result = Result(Finish.entries[reason], utf8.decodeToString(), error)
                }
            },
        )
        return result ?: Result(Finish.ERROR, "", "no result from native code")
    }

    /** Stops the call with [callId], whether it is running or has not started yet. Any thread. */
    fun cancel(callId: Long) {
        loadLibrary
        nativeCancel(callId)
    }

    fun unload() = nativeUnload()

    interface Callback {
        fun onPartial(utf8: ByteArray)
        fun onFinish(reason: Int, utf8: ByteArray, error: String?)
    }

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Boolean
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeCountTokens(text: ByteArray): Int
    private external fun nativeTranslate(callId: Long, prompt: ByteArray, maxTokens: Int, cb: Callback)
    private external fun nativeCancel(callId: Long)
    private external fun nativeUnload()
}
