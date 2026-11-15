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

enum class SkipReason { EMPTY, FEED_OFF, JAPANESE_FEED, JAPANESE_TAG, JAPANESE_TEXT, UNDETERMINED }

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
