package io.github.katsuhidem.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class TranslationPlannerTest {
    private val config = TranslationConfig()

    private fun key(
        text: String = "Hello world",
        config: TranslationConfig = this.config,
        modelRev: String = "rev-a",
        languageTag: String? = null,
        context: String? = null,
        spans: List<ProtectedSpan> = emptyList(),
    ): TranslationKey {
        val result = TranslationPlanner.plan(
            urlHash = "u1",
            field = TranslationField.TITLE,
            text = text,
            config = config,
            modelRev = modelRev,
            languageTag = languageTag,
            context = context,
            protectedSpans = spans,
        )
        return assertIs<PlanResult.Planned>(result).plan.key
    }

    @Test
    fun sameInputGivesTheSameKey() {
        assertEquals(key(), key())
        // Surrounding whitespace is not part of the source.
        assertEquals(key("Hello world"), key("  Hello world \n"))
    }

    @Test
    fun everyPartOfTheConditionChangesTheKey() {
        val base = key()
        assertNotEquals(base, key(text = "Hello world!"))
        assertNotEquals(base, key(config = config.copy(style = TranslationStyle.PLAIN)))
        assertNotEquals(base, key(config = config.copy(targetLang = "en")))
        assertNotEquals(base, key(config = config.copy(userGlossary = listOf(GlossaryEntry("world", "世界")))))
        assertNotEquals(base, key(modelRev = "rev-b"))
        assertNotEquals(base, key(languageTag = "en"))
        assertNotEquals(base, key(context = "A greeting."))
    }

    @Test
    fun styleChangeKeepsSourceHashButChangesConfigHash() {
        val plain = key(config = config.copy(style = TranslationStyle.PLAIN))
        val polite = key(config = config.copy(style = TranslationStyle.POLITE))
        assertEquals(plain.sourceHash, polite.sourceHash)
        assertNotEquals(plain.configHash, polite.configHash)
    }

    @Test
    fun protectedSpansArePartOfTheSourceHash() {
        val plainOpen = key("Call open to start")
        val codeOpen = key("Call open to start", spans = listOf(ProtectedSpan(5, "open")))
        val movedCode = key("Call open to start", spans = listOf(ProtectedSpan(6, "open")))
        assertNotEquals(plainOpen.sourceHash, codeOpen.sourceHash)
        assertNotEquals(codeOpen.sourceHash, movedCode.sourceHash)
    }

    @Test
    fun glossaryFieldsCannotBeConfusedWithEachOther() {
        val a = key(config = config.copy(userGlossary = listOf(GlossaryEntry("ab", "c"))))
        val b = key(config = config.copy(userGlossary = listOf(GlossaryEntry("a", "bc"))))
        assertNotEquals(a.configHash, b.configHash)
    }

    @Test
    fun skippedTextHasNoPlan() {
        val result = TranslationPlanner.plan("u1", TranslationField.TITLE, "新機能を公開しました", config, "rev-a")
        assertEquals(PlanResult.Skipped(SkipReason.JAPANESE_TEXT), result)
    }

    @Test
    fun planCarriesThePromptInputs() {
        val result = TranslationPlanner.plan(
            urlHash = "u1",
            field = TranslationField.TITLE,
            text = "Hello world",
            config = config.copy(style = TranslationStyle.POLITE),
            modelRev = "rev-a",
            languageTag = "en-GB",
        )
        val plan = assertIs<PlanResult.Planned>(result).plan
        assertEquals("en", plan.sourceLang)
        assertEquals(
            "<|plamo:tag|>dataset<|plamo:msg|>translation" +
                "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>en<|plamo:msg|>Hello world" +
                "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja<|plamo:key|>form<|plamo:val|>polite<|plamo:msg|>",
            plan.prompt(),
        )
    }
}
