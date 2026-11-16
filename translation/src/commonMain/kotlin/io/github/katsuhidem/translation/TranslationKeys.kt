package io.github.katsuhidem.translation

enum class TranslationField(val dbValue: String) {
    TITLE("title"),
    SUBTITLE("subtitle"),
    BLOCK("block"),
}

/** User-level translation settings. Everything here is part of the cache key. */
data class TranslationConfig(
    val targetLang: String = "ja",
    val style: TranslationStyle = TranslationStyle.UNSPECIFIED,
    /** The user's dictionary only. Words added per block to protect code are not part of this. */
    val userGlossary: List<GlossaryEntry> = emptyList(),
)

/** Inline code (or another span that must come out unchanged) inside a source text. */
data class ProtectedSpan(val start: Int, val text: String)

data class TranslationKey(
    val urlHash: String,
    val field: TranslationField,
    val sourceHash: String,
    val contextHash: String,
    val configHash: String,
    val modelRev: String,
)

/** What to translate, with the key under which the result is stored. */
data class TranslationPlan(
    val key: TranslationKey,
    val source: String,
    val sourceLang: String?,
    val context: String?,
    val config: TranslationConfig,
    /** Glossary sent to the model: the user's dictionary plus any per-block protection words. */
    val glossary: List<GlossaryEntry> = config.userGlossary,
) {
    fun prompt(): String = PlamoPromptBuilder.build(
        source = source,
        targetLang = config.targetLang,
        sourceLang = sourceLang,
        context = context,
        glossary = glossary,
        style = config.style,
    )
}

/**
 * The only place that turns an article text into a cache key. Both the
 * lookup side and the save side call [plan], so the two can never disagree
 * on the source language or any other part of the key.
 */
object TranslationPlanner {

    /**
     * Returns the plan for [text], or the reason it is not translated.
     * No model and no tokenizer are needed.
     *
     * @param context reference text passed to the model, already cut with [TokenBudget.truncateContext]
     */
    fun plan(
        urlHash: String,
        field: TranslationField,
        text: String?,
        config: TranslationConfig,
        modelRev: String,
        feedMode: FeedTranslationMode = FeedTranslationMode.AUTO,
        feedLanguage: FeedLanguage? = null,
        languageTag: String? = null,
        context: String? = null,
        protectedSpans: List<ProtectedSpan> = emptyList(),
    ): PlanResult {
        val decision = LanguageGate.decide(text, feedMode, feedLanguage, languageTag)
        if (decision is GateDecision.Skip) return PlanResult.Skipped(decision.reason)
        val sourceLang = (decision as GateDecision.Translate).sourceLang
        return PlanResult.Planned(
            planDecided(urlHash, field, text.orEmpty(), sourceLang, config, modelRev, context, protectedSpans),
        )
    }

    /**
     * The plan for a text that is already known to need translation, with
     * its source language decided by the caller. Used for the pieces of an
     * article block: the language is judged once for the block, and a piece
     * holding little more than a code placeholder must not be skipped.
     */
    fun planDecided(
        urlHash: String,
        field: TranslationField,
        text: String,
        sourceLang: String?,
        config: TranslationConfig,
        modelRev: String,
        context: String? = null,
        protectedSpans: List<ProtectedSpan> = emptyList(),
    ): TranslationPlan {
        val source = text.trim()
        val ctx = context?.trim()?.takeIf { it.isNotEmpty() }
        val key = TranslationKey(
            urlHash = urlHash,
            field = field,
            sourceHash = sourceHash(source, protectedSpans),
            contextHash = Sha256.hex(ctx.orEmpty()),
            configHash = configHash(config, sourceLang),
            modelRev = modelRev,
        )
        return TranslationPlan(key, source, sourceLang, ctx, config)
    }

    /**
     * Hash of the text together with the protected spans (position, string
     * and order). The text alone cannot tell `<code>open</code>` from a plain
     * "open", so an article whose markup alone was fixed would get an old translation.
     */
    internal fun sourceHash(source: String, protectedSpans: List<ProtectedSpan>): String {
        val sb = StringBuilder()
        field(sb, source)
        for (span in protectedSpans) {
            field(sb, span.start.toString())
            field(sb, span.text)
        }
        return Sha256.hex(sb.toString())
    }

    internal fun configHash(config: TranslationConfig, sourceLang: String?): String {
        val sb = StringBuilder()
        field(sb, "v${PlamoPromptBuilder.PREPROCESS_VERSION}")
        field(sb, sourceLang.orEmpty())
        field(sb, config.targetLang)
        field(sb, config.style.name)
        for (entry in config.userGlossary) {
            field(sb, entry.source)
            field(sb, entry.target)
        }
        return Sha256.hex(sb.toString())
    }

    /** Length-prefixed, so ("ab", "c") and ("a", "bc") hash differently. */
    private fun field(sb: StringBuilder, value: String) {
        sb.append(value.length).append(':').append(value).append('|')
    }
}

sealed interface PlanResult {
    data class Planned(val plan: TranslationPlan) : PlanResult
    data class Skipped(val reason: SkipReason) : PlanResult
}
