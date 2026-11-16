package io.github.katsuhidem.translation

/** Per-feed setting chosen by the user. */
enum class FeedTranslationMode(val dbValue: String) {
    AUTO("auto"),
    ON("on"),
    OFF("off"),
    ;

    companion object {
        fun fromDb(value: String?): FeedTranslationMode = entries.firstOrNull { it.dbValue == value } ?: AUTO
    }
}

/** Language of a feed as estimated from its recent titles. Stored once decided. */
enum class FeedLanguage(val dbValue: String) {
    JAPANESE("ja"),
    OTHER("other"),
    ;

    companion object {
        fun fromDb(value: String?): FeedLanguage? = entries.firstOrNull { it.dbValue == value }
    }
}

enum class SkipReason { EMPTY, FEED_OFF, JAPANESE_FEED, JAPANESE_TAG, JAPANESE_TEXT, UNDETERMINED, TOO_SHORT }

sealed interface GateDecision {
    /** Translate; [sourceLang] is a model language code, or null to let the model guess. */
    data class Translate(val sourceLang: String?) : GateDecision
    data class Skip(val reason: SkipReason) : GateDecision
}

/**
 * Decides whether a text is translated into Japanese. Looked at in this
 * order: the feed's setting, the feed's estimated language, the article's
 * language tag, then the script of the text.
 *
 * - A feed estimated as Japanese is never translated, including titles
 *   without kana such as "2026年11月期 決算短信".
 * - In other feeds only text with kana counts as Japanese. Text in Han
 *   characters alone (a Chinese title) is not treated as Japanese.
 * - Text with almost no letters cannot be judged and is not translated.
 */
object LanguageGate {
    private const val MIN_LETTERS = 2
    private const val MIN_TITLES_FOR_ESTIMATE = 5
    private const val JAPANESE_FEED_RATIO = 0.6

    /** Blocks below this size are "very short" (a name, "FAQ", a single Han character) and are left alone. */
    private const val MIN_BLOCK_UNITS = 4

    /** In a Japanese article only foreign text of about a sentence or more is translated, not labels and names. */
    private const val MIN_FOREIGN_UNITS_IN_JAPANESE_ARTICLE = 30

    /** What one inline code span adds to the size of a block. */
    private const val CODE_SPAN_UNITS = 4

    /** An article with less text than this is not judged as a whole. */
    private const val MIN_ARTICLE_UNITS = 200
    private const val JAPANESE_ARTICLE_RATIO = 0.6

    fun decide(
        text: String?,
        feedMode: FeedTranslationMode = FeedTranslationMode.AUTO,
        feedLanguage: FeedLanguage? = null,
        languageTag: String? = null,
    ): GateDecision {
        if (text.isNullOrBlank()) return GateDecision.Skip(SkipReason.EMPTY)
        if (feedMode == FeedTranslationMode.OFF) return GateDecision.Skip(SkipReason.FEED_OFF)
        if (text.count { it.isLetter() } < MIN_LETTERS) return GateDecision.Skip(SkipReason.UNDETERMINED)

        val tagLang = modelLanguageCode(languageTag)
        if (feedMode == FeedTranslationMode.ON) {
            return GateDecision.Translate(tagLang.takeUnless { it == "ja" })
        }
        if (feedLanguage == FeedLanguage.JAPANESE) return GateDecision.Skip(SkipReason.JAPANESE_FEED)
        if (tagLang == "ja") return GateDecision.Skip(SkipReason.JAPANESE_TAG)
        if (tagLang != null) return GateDecision.Translate(tagLang)
        if (containsKana(text)) return GateDecision.Skip(SkipReason.JAPANESE_TEXT)
        return GateDecision.Translate(sourceLang = null)
    }

    /**
     * Decides whether one block of an article's text is translated.
     *
     * The user asked for this article, so neither the feed nor the language
     * tag is looked at; the block's own text decides, together with the
     * language of the article as a whole ([articleLanguage], from
     * [estimateArticleLanguage]):
     *
     * - A block with kana is Japanese.
     * - In a Japanese article, a block with Han characters but no kana
     *   (a heading such as "同時実行数") is Japanese too, and other text is
     *   translated only when it is about a sentence long, so author names
     *   and labels such as "Discussion" stay as they are.
     * - Elsewhere only very short blocks are left alone.
     *
     * Sizes are counted in [TokenBudget.units] over letters and digits, so
     * the result does not depend on a model.
     *
     * @param prose the block's text with inline code taken out
     * @param codeSpans number of inline code spans taken out
     */
    fun decideBlock(prose: String, articleLanguage: FeedLanguage?, codeSpans: Int = 0): GateDecision {
        if (prose.count { it.isLetter() } < MIN_LETTERS) return GateDecision.Skip(SkipReason.UNDETERMINED)
        if (containsKana(prose)) return GateDecision.Skip(SkipReason.JAPANESE_TEXT)
        val units = TokenBudget.units(prose.filter { it.isLetterOrDigit() }) + codeSpans * CODE_SPAN_UNITS
        if (articleLanguage == FeedLanguage.JAPANESE) {
            if (containsHan(prose)) return GateDecision.Skip(SkipReason.JAPANESE_TEXT)
            if (units < MIN_FOREIGN_UNITS_IN_JAPANESE_ARTICLE) return GateDecision.Skip(SkipReason.TOO_SHORT)
        } else if (units < MIN_BLOCK_UNITS) {
            return GateDecision.Skip(SkipReason.TOO_SHORT)
        }
        return GateDecision.Translate(sourceLang = null)
    }

    /**
     * Estimates whether an article is written in Japanese, from the text of
     * its blocks (inline code taken out). Blocks are weighed by their size,
     * not counted: a Japanese article may have many short lines in Latin
     * letters (names, labels, list items of commands) next to a few long
     * Japanese paragraphs. Returns null for an article with too little text.
     */
    fun estimateArticleLanguage(blocks: List<String>): FeedLanguage? {
        var total = 0
        var withKana = 0
        for (block in blocks) {
            if (block.count { it.isLetter() } < MIN_LETTERS) continue
            val units = TokenBudget.units(block.filter { it.isLetterOrDigit() })
            total += units
            if (containsKana(block)) withKana += units
        }
        if (total < MIN_ARTICLE_UNITS) return null
        return if (withKana >= total * JAPANESE_ARTICLE_RATIO) FeedLanguage.JAPANESE else FeedLanguage.OTHER
    }

    /**
     * Estimates a feed's language from its recent titles. Returns null while
     * there are too few titles to decide; the caller stores the first
     * non-null result and does not ask again.
     */
    fun estimateFeedLanguage(recentTitles: List<String>): FeedLanguage? {
        val judged = recentTitles.filter { title -> title.count { it.isLetter() } >= MIN_LETTERS }
        if (judged.size < MIN_TITLES_FOR_ESTIMATE) return null
        val withKana = judged.count { containsKana(it) }
        return if (withKana >= judged.size * JAPANESE_FEED_RATIO) FeedLanguage.JAPANESE else FeedLanguage.OTHER
    }

    fun containsKana(text: String): Boolean = text.any { isKana(it) }

    fun containsHan(text: String): Boolean = text.any { isHan(it) }

    private fun isHan(c: Char): Boolean = when (c.code) {
        // CJK unified ideographs, extension A, compatibility ideographs, and the iteration mark.
        in 0x4E00..0x9FFF, in 0x3400..0x4DBF, in 0xF900..0xFAFF, 0x3005 -> true
        else -> false
    }

    private fun isKana(c: Char): Boolean = when (c.code) {
        // Hiragana, except the combining marks and unused code points at the edges.
        in 0x3041..0x3096, in 0x309D..0x309F -> true
        // Katakana, except U+30FB (middle dot) and U+30FC (long vowel mark), which also appear in Chinese text.
        in 0x30A1..0x30FA, in 0x30FD..0x30FF -> true
        // Half-width katakana.
        in 0xFF66..0xFF6F, in 0xFF71..0xFF9D -> true
        else -> false
    }

    /**
     * Maps a BCP 47 tag from a feed (such as "en-US" or "zh-TW") to a
     * language code the model knows, or null when it is absent or unknown.
     */
    fun modelLanguageCode(languageTag: String?): String? {
        val tag = languageTag?.trim()?.lowercase()?.replace('_', '-').orEmpty()
        if (tag.isEmpty()) return null
        val parts = tag.split('-')
        val primary = parts[0]
        if (primary == "zh") {
            val traditional = parts.drop(1).any { it in TRADITIONAL_MARKERS }
            return if (traditional) "zh-hant" else "zh-hans"
        }
        return primary.takeIf { it in MODEL_LANGUAGES }
    }

    private val TRADITIONAL_MARKERS = setOf("hant", "tw", "hk", "mo")

    /** Language codes listed in the PLaMo 3 Translate model card (Chinese is handled above). */
    private val MODEL_LANGUAGES = setOf(
        "ar", "az", "be", "bg", "bn", "ca", "ceb", "de", "en", "es", "et", "eu", "fa", "fr", "gu", "he",
        "hi", "hr", "hu", "hy", "id", "it", "ja", "jv", "ka", "kk", "km", "ko", "lt", "lv", "ml", "mr",
        "ms", "my", "ne", "nl", "pa", "pl", "ru", "si", "sk", "sl", "sr", "sw", "ta", "te", "th", "tl",
        "ur", "uz", "vi",
    )
}
