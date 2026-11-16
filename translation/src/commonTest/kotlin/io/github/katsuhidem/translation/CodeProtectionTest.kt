package io.github.katsuhidem.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeProtectionTest {
    private val text = "Run npm install first, then run npm install again with --force."
    private val spans = listOf(ProtectedSpan(4, "npm install"), ProtectedSpan(32, "npm install"), ProtectedSpan(55, "--force"))

    @Test
    fun everyOccurrenceGetsItsOwnPlaceholder() {
        val protected = CodeProtection.protect(text, spans)
        assertEquals("Run [[1]] first, then run [[2]] again with [[3]].", protected.text)
        assertEquals(
            listOf("[[1]]" to "npm install", "[[2]]" to "npm install", "[[3]]" to "--force"),
            protected.placeholders,
        )
    }

    @Test
    fun codeComesBackUnchangedAndInTheNewPositions() {
        val protected = CodeProtection.protect(text, spans)
        val translated = "まず[[1]]を実行し、次に[[3]]を付けてもう一度[[2]]を実行します。"
        assertTrue(CodeProtection.verify(translated, protected.placeholders))
        assertEquals(
            "まずnpm installを実行し、次に--forceを付けてもう一度npm installを実行します。",
            CodeProtection.restore(translated, protected.placeholders),
        )
    }

    @Test
    fun missingChangedOrRepeatedPlaceholdersFailVerification() {
        val tokens = CodeProtection.protect(text, spans).placeholders
        assertFalse(CodeProtection.verify("まず[[1]]を実行し、もう一度[[2]]を実行します。", tokens), "missing")
        assertFalse(CodeProtection.verify("[[1]]と[[2]]と[[3]]と[[3]]", tokens), "repeated")
        assertFalse(CodeProtection.verify("[[1]]と[[2]]と[3]", tokens), "changed")
        assertFalse(CodeProtection.verify("[[1]]と[[２]]と[[3]]", tokens), "full-width digit")
    }

    @Test
    fun textWithoutCodeIsLeftAlone() {
        val protected = CodeProtection.protect("Plain text.", emptyList())
        assertEquals("Plain text.", protected.text)
        assertTrue(CodeProtection.verify("ただの文。", protected.placeholders))
        assertEquals("ただの文。", CodeProtection.restore("ただの文。", protected.placeholders))
    }

    @Test
    fun spansThatDoNotMatchTheTextAreIgnored() {
        val protected = CodeProtection.protect("Call open now", listOf(ProtectedSpan(6, "open"), ProtectedSpan(5, "open")))
        assertEquals("Call [[1]] now", protected.text)
        assertEquals(listOf("[[1]]" to "open"), protected.placeholders)
    }

    @Test
    fun placeholdersAvoidNumbersAlreadyInTheText() {
        val source = "See [[2]] and run make."
        val protected = CodeProtection.protect(source, listOf(ProtectedSpan(18, "make")))
        assertEquals("See [[2]] and run [[3]].", protected.text)
        // The literal [[2]] is the article's own text, not a placeholder to verify.
        assertTrue(CodeProtection.verify("[[2]]を見て[[3]]を実行する。", protected.placeholders))
    }

    @Test
    fun splitNeverCutsInsideAPlaceholder() {
        // No spaces or sentence ends: only hard cuts are possible.
        val text = "x".repeat(1198) + "[[1]]" + "y".repeat(1500) + "[[2]]"
        val pieces = TokenBudget.split(text, CodeProtection::placeholderRanges)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.all { TokenBudget.units(it) <= TokenBudget.MAX_BLOCK_UNITS })
        assertEquals(1, pieces.count { "[[1]]" in it })
        assertEquals(1, pieces.count { "[[2]]" in it })
    }
}
