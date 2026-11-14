package io.github.katsuhidem.tlspike

/** Kotlin side of translator_jni.cpp. Not thread-safe except [cancel]. */
object Translator {
    init {
        System.loadLibrary("tlspike")
    }

    enum class Finish { STOP, MAX_TOKENS, CANCELLED, ERROR }

    class Result(
        val finish: Finish,
        val promptTokens: Int,
        val tokens: IntArray,
        val text: String,
        val error: String?,
    )

    fun interface OnPartial {
        fun onPartial(nTokens: Int, text: String)
    }

    fun load(path: String, nCtx: Int, nThreads: Int): Boolean = nativeLoad(path, nCtx, nThreads)

    /** Tokens of the prompt including the single BOS. */
    fun countTokens(prompt: String): Int = nativeCountTokens(prompt)

    fun translate(prompt: String, maxTokens: Int, partial: OnPartial? = null): Result {
        var result: Result? = null
        nativeResetCancel()
        nativeTranslate(
            prompt,
            maxTokens,
            object : NativeCallback {
                override fun onPartial(nTokens: Int, utf8: ByteArray) {
                    partial?.onPartial(nTokens, String(utf8, Charsets.UTF_8))
                }

                override fun onFinish(reason: Int, nPrompt: Int, tokens: IntArray, utf8: ByteArray, error: String?) {
                    result = Result(Finish.entries[reason], nPrompt, tokens, String(utf8, Charsets.UTF_8), error)
                }
            },
        )
        return result ?: Result(Finish.ERROR, 0, IntArray(0), "", "no result")
    }

    fun cancel() = nativeCancel()

    fun unload() = nativeUnload()

    interface NativeCallback {
        fun onPartial(nTokens: Int, utf8: ByteArray)
        fun onFinish(reason: Int, nPrompt: Int, tokens: IntArray, utf8: ByteArray, error: String?)
    }

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Boolean
    private external fun nativeCountTokens(prompt: String): Int
    private external fun nativeTranslate(prompt: String, maxTokens: Int, cb: NativeCallback)
    private external fun nativeCancel()
    private external fun nativeResetCancel()
    private external fun nativeUnload()
}

/** PFML3 prompt from the model card. Elements are joined without newlines. */
object Prompt {
    fun build(
        source: String,
        sourceLang: String? = null,
        glossary: List<Pair<String, String>> = emptyList(),
        plain: Boolean = false,
    ): String = buildString {
        append("<|plamo:tag|>dataset<|plamo:msg|>translation")
        if (glossary.isNotEmpty()) {
            append("<|plamo:tag|>glossary")
            for ((src, dst) in glossary) append("<|plamo:index|>").append(src).append("<|plamo:item|>").append(dst)
        }
        append("<|plamo:tag|>input")
        if (sourceLang != null) append("<|plamo:key|>lang<|plamo:val|>").append(sourceLang)
        append("<|plamo:msg|>").append(source)
        append("<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja")
        if (plain) append("<|plamo:key|>form<|plamo:val|>plain")
        append("<|plamo:msg|>")
    }
}
