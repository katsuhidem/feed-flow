package io.github.katsuhidem.translation

/**
 * Sizes and splits text by character rules, never with the tokenizer, so
 * the result (and with it the cache key) is the same whether or not a model
 * is loaded.
 *
 * A "unit" approximates tokens from characters. Measured in Phase 0c on the
 * 0.8B model: English about 6.6 characters per token, Japanese about 2.4.
 * Code, URLs and Chinese are denser. One Latin character counts as 1 unit
 * and one CJK character as 3, and the limits leave room for the dense
 * cases. The exact token count is still checked right before inference
 * ([fits]); an input over the limit there is a recorded failure, not a
 * re-split.
 *
 * Changing any number or rule here changes what gets translated: raise
 * [PlamoPromptBuilder.PREPROCESS_VERSION] when you do.
 */
object TokenBudget {
    /** Context size the engine is created with. */
    const val N_CTX = 4096

    /** Upper size of one block to translate. */
    const val MAX_BLOCK_UNITS = 1200

    /** Upper size of the reference context passed along. */
    const val MAX_CONTEXT_UNITS = 400

    private const val MIN_OUTPUT_TOKENS = 64
    private const val OUTPUT_TOKENS_PER_SOURCE_TOKEN = 3
    private const val CJK_UNITS = 3
    private const val SURROGATE_UNITS = 2
    private const val CJK_START = 0x2E80

    fun units(text: String): Int = text.sumOf { unitsOf(it) }

    private fun unitsOf(c: Char): Int = when {
        c.isSurrogate() -> SURROGATE_UNITS
        c.code >= CJK_START -> CJK_UNITS
        else -> 1
    }

    /** Output budget in tokens, proportional to the source so a runaway repeat stops early. */
    fun maxOutputTokens(sourceTokens: Int): Int =
        maxOf(MIN_OUTPUT_TOKENS, OUTPUT_TOKENS_PER_SOURCE_TOKEN * sourceTokens)

    /** The check done with real token counts right before inference. */
    fun fits(promptTokens: Int, maxOutputTokens: Int): Boolean = promptTokens + maxOutputTokens <= N_CTX

    /** Which end of an over-long context to keep. */
    enum class Keep { HEAD, TAIL }

    /** Cuts the context to [MAX_CONTEXT_UNITS], at a whitespace when one is near. */
    fun truncateContext(text: String, keep: Keep = Keep.TAIL): String {
        val t = text.trim()
        if (units(t) <= MAX_CONTEXT_UNITS) return t
        return when (keep) {
            Keep.HEAD -> takeUnits(t, MAX_CONTEXT_UNITS).trimEnd()
            Keep.TAIL -> takeUnits(t.reversed(), MAX_CONTEXT_UNITS).reversed().trimStart()
        }
    }

    private fun takeUnits(text: String, limit: Int): String {
        var used = 0
        var end = 0
        var lastSpace = -1
        while (end < text.length) {
            val u = unitsOf(text[end])
            if (used + u > limit) break
            if (text[end].isWhitespace()) lastSpace = end
            used += u
            end++
        }
        // Do not cut inside a surrogate pair.
        if (end < text.length && end > 0 && text[end - 1].isHighSurrogate()) end--
        if (end < text.length && end > 0 && text[end].isHighSurrogate() && text[end - 1].isLowSurrogate()) end--
        // Prefer a whitespace in the last quarter, so words stay whole.
        return if (lastSpace > end * 3 / 4) text.substring(0, lastSpace) else text.substring(0, end)
    }

    /**
     * Splits [text] into blocks of at most [MAX_BLOCK_UNITS]. Cut points are
     * tried in this order: the last sentence end within the limit, the last
     * whitespace, then the limit itself, so any text can be split.
     */
    fun split(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (units(t) <= MAX_BLOCK_UNITS) return listOf(t)

        val out = mutableListOf<String>()
        var start = 0
        while (start < t.length) {
            var used = 0
            var i = start
            var lastSentence = -1
            var lastSpace = -1
            while (i < t.length) {
                val c = t[i]
                val u = unitsOf(c)
                if (used + u > MAX_BLOCK_UNITS) break
                used += u
                i++
                if (isSentenceEnd(t, i - 1)) lastSentence = i
                if (c.isWhitespace()) lastSpace = i
            }
            var cut = when {
                i >= t.length -> t.length
                lastSentence > start -> lastSentence
                lastSpace > start -> lastSpace
                else -> i
            }
            if (cut < t.length && cut > start + 1 && t[cut - 1].isHighSurrogate()) cut--
            if (cut <= start) cut = minOf(t.length, start + 1) // always make progress
            val piece = t.substring(start, cut).trim()
            if (piece.isNotEmpty()) out += piece
            start = cut
        }
        return out
    }

    /** True when the character at [index] ends a sentence. */
    private fun isSentenceEnd(text: String, index: Int): Boolean {
        val c = text[index]
        if (c == '。' || c == '!' || c == '?') return true
        if (c == '.' || c == '!' || c == '?') {
            // "3.14" and "v1.2" are not sentence ends: require whitespace or the end after it.
            return index + 1 >= text.length || text[index + 1].isWhitespace()
        }
        return c == '\n'
    }
}
