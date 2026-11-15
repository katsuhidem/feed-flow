package io.github.katsuhidem.translation

/** Japanese sentence style of the output. */
enum class TranslationStyle(val formValue: String?) {
    UNSPECIFIED(null),
    PLAIN("plain"),
    POLITE("polite"),
}

/** One glossary pair: [source] is rendered as [target]. */
data class GlossaryEntry(val source: String, val target: String)

/**
 * Builds the PFML3 prompt of PLaMo 3 Translate (model card, "PFML3プロンプト形式").
 *
 * Elements are joined without newlines, in this order: dataset, context,
 * glossary, input, output. The leading BOS is not written here; the
 * tokenizer adds it. The prompt ends with the output element's msg marker
 * and nothing after it.
 *
 * The source language is written only when it is known; the model guesses
 * it otherwise.
 */
object PlamoPromptBuilder {
    /**
     * Version of everything done to the text before inference: this prompt,
     * the protection of code, the split rules and the context cut. Part of
     * the cache key; raise it whenever any of them changes.
     */
    const val PREPROCESS_VERSION = 1

    fun build(
        source: String,
        targetLang: String = "ja",
        sourceLang: String? = null,
        context: String? = null,
        glossary: List<GlossaryEntry> = emptyList(),
        style: TranslationStyle = TranslationStyle.UNSPECIFIED,
    ): String = buildString {
        append("<|plamo:tag|>dataset<|plamo:msg|>translation")
        if (!context.isNullOrBlank()) {
            append("<|plamo:tag|>context<|plamo:msg|>").append(sanitize(context))
        }
        if (glossary.isNotEmpty()) {
            append("<|plamo:tag|>glossary")
            for (entry in glossary) {
                append("<|plamo:index|>").append(sanitize(entry.source))
                append("<|plamo:item|>").append(sanitize(entry.target))
            }
        }
        append("<|plamo:tag|>input")
        if (!sourceLang.isNullOrBlank()) append("<|plamo:key|>lang<|plamo:val|>").append(sanitize(sourceLang))
        append("<|plamo:msg|>").append(sanitize(source))
        append("<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>").append(sanitize(targetLang))
        // The form attribute is defined for Japanese output only.
        if (targetLang == "ja" && style.formValue != null) {
            append("<|plamo:key|>form<|plamo:val|>").append(style.formValue)
        }
        append("<|plamo:msg|>")
    }

    /**
     * Text from feeds must not be read as control tokens. The tokenizer
     * parses special tokens, so a literal "<|plamo:" in an article would end
     * the input early or inject an element. A zero-width space breaks the match.
     */
    internal fun sanitize(text: String): String = text.replace(MARKER, BROKEN_MARKER)

    private const val MARKER = "<|plamo:"
    private const val BROKEN_MARKER = "<|​plamo:"
}
