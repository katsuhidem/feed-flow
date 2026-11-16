package io.github.katsuhidem.translation

/** A source text whose inline code was replaced by placeholders. */
class ProtectedText(
    /** The text sent to the model: each code span is a placeholder such as `[[3]]`. */
    val text: String,
    /** Placeholder token to the code it stands for, in order of appearance. */
    val placeholders: List<Pair<String, String>>,
)

/**
 * Keeps inline code out of the model's hands.
 *
 * Each occurrence of a code span becomes its own placeholder, also when the
 * same code appears twice. After translation every placeholder must be
 * present exactly once; only then is the code put back, byte for byte. A
 * translation that lost, changed or repeated a placeholder is rejected, so
 * altered code is never shown or stored.
 *
 * In Phase 0c placeholders and a glossary both kept code intact on the test
 * sentences, but real articles showed code being translated when nothing
 * protected it. Placeholders give a guarantee that can be checked;
 * a glossary only asks the model.
 */
object CodeProtection {
    private val PLACEHOLDER = Regex("""\[\[(\d+)]]""")

    /**
     * Replaces [spans] in [text] with placeholders. Spans that do not match
     * the text (wrong offset or overlapping) are ignored.
     */
    fun protect(text: String, spans: List<ProtectedSpan>): ProtectedText {
        val valid = mutableListOf<ProtectedSpan>()
        var end = 0
        for (span in spans.sortedBy { it.start }) {
            val fits = span.start >= end && span.text.isNotEmpty() &&
                text.regionMatches(span.start, span.text, 0, span.text.length)
            if (fits) {
                valid += span
                end = span.start + span.text.length
            }
        }
        if (valid.isEmpty()) return ProtectedText(text, emptyList())

        // Do not collide with text that already looks like a placeholder (wiki links, footnotes).
        var next = (PLACEHOLDER.findAll(text).maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } ?: 0) + 1
        val sb = StringBuilder()
        val placeholders = mutableListOf<Pair<String, String>>()
        var pos = 0
        for (span in valid) {
            sb.append(text, pos, span.start)
            val token = "[[${next++}]]"
            sb.append(token)
            placeholders += token to span.text
            pos = span.start + span.text.length
        }
        sb.append(text, pos, text.length)
        return ProtectedText(sb.toString(), placeholders)
    }

    /** Ranges of placeholders in [text]; a split must not cut inside one. */
    fun placeholderRanges(text: String): List<IntRange> = PLACEHOLDER.findAll(text).map { it.range }.toList()

    /** The placeholder tokens that occur in [text], in order. */
    fun tokensIn(text: String, placeholders: List<Pair<String, String>>): List<Pair<String, String>> =
        placeholders.filter { text.contains(it.first) }

    /** True when every one of [tokens] occurs exactly once in [translated]. */
    fun verify(translated: String, tokens: List<Pair<String, String>>): Boolean =
        tokens.all { (token, _) -> occurrences(translated, token) == 1 }

    /** Puts the code back. Call only after [verify] passed. */
    fun restore(translated: String, tokens: List<Pair<String, String>>): String {
        var out = translated
        for ((token, code) in tokens) out = out.replace(token, code)
        return out
    }

    private fun occurrences(text: String, token: String): Int {
        var count = 0
        var index = text.indexOf(token)
        while (index >= 0) {
            count++
            index = text.indexOf(token, index + token.length)
        }
        return count
    }
}
