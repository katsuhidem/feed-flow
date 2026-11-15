package io.github.katsuhidem.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenBudgetTest {
    @Test
    fun unitsCountCjkAsThree() {
        assertEquals(5, TokenBudget.units("hello"))
        assertEquals(9, TokenBudget.units("日本語"))
        assertEquals(4, TokenBudget.units("a日"))
    }

    @Test
    fun outputBudgetIsProportionalWithAFloor() {
        assertEquals(64, TokenBudget.maxOutputTokens(5))
        assertEquals(300, TokenBudget.maxOutputTokens(100))
    }

    @Test
    fun fitsChecksPromptPlusBudgetAgainstContext() {
        assertTrue(TokenBudget.fits(promptTokens = 96, maxOutputTokens = 4000))
        assertTrue(!TokenBudget.fits(promptTokens = 97, maxOutputTokens = 4000))
    }

    @Test
    fun shortTextIsOneBlock() {
        assertEquals(listOf("A short title."), TokenBudget.split("  A short title.  "))
        assertEquals(emptyList(), TokenBudget.split("   "))
    }

    @Test
    fun longTextIsCutAtSentenceEnds() {
        val sentence = "This sentence is exactly forty chars long. " // 43 with the space
        val text = sentence.repeat(60).trim()
        val blocks = TokenBudget.split(text)
        assertTrue(blocks.size > 1)
        blocks.forEach { block ->
            assertTrue(TokenBudget.units(block) <= TokenBudget.MAX_BLOCK_UNITS)
            assertTrue(block.endsWith("long."), "block must end at a sentence end: ...${block.takeLast(20)}")
        }
        assertEquals(text, blocks.joinToString(" "))
    }

    @Test
    fun decimalPointsAreNotSentenceEnds() {
        val text = ("word ".repeat(235) + "version 3.14 is out").trim()
        val blocks = TokenBudget.split(text)
        assertTrue(blocks.none { it.endsWith("3.") })
    }

    @Test
    fun textWithoutSentenceEndsIsCutAtWhitespace() {
        val text = "word ".repeat(600).trim()
        val blocks = TokenBudget.split(text)
        assertTrue(blocks.size > 1)
        blocks.forEach { block ->
            assertTrue(TokenBudget.units(block) <= TokenBudget.MAX_BLOCK_UNITS)
            assertTrue(block.split(' ').all { it == "word" }, "words must stay whole")
        }
    }

    @Test
    fun textWithoutAnyBreakIsStillSplit() {
        val text = "x".repeat(5000)
        val blocks = TokenBudget.split(text)
        assertEquals(text, blocks.joinToString(""))
        blocks.forEach { assertTrue(TokenBudget.units(it) <= TokenBudget.MAX_BLOCK_UNITS) }
    }

    @Test
    fun japaneseTextIsCutAtFullStops() {
        val text = "これは長い文章の一部です。".repeat(60)
        val blocks = TokenBudget.split(text)
        assertTrue(blocks.size > 1)
        blocks.forEach { block ->
            assertTrue(TokenBudget.units(block) <= TokenBudget.MAX_BLOCK_UNITS)
            assertTrue(block.endsWith("。"))
        }
        assertEquals(text, blocks.joinToString(""))
    }

    @Test
    fun surrogatePairsAreNotCutInHalf() {
        val text = "😀".repeat(1500)
        val blocks = TokenBudget.split(text)
        assertEquals(text, blocks.joinToString(""))
        blocks.forEach { block ->
            assertTrue(!block.first().isLowSurrogate() && !block.last().isHighSurrogate())
        }
    }

    @Test
    fun splitIsDeterministic() {
        val text = "Some sentence here. Another one follows! And a third? ".repeat(80)
        assertEquals(TokenBudget.split(text), TokenBudget.split(text))
    }

    @Test
    fun contextKeepsTheRequestedEnd() {
        val text = (1..400).joinToString(" ") { "w$it" }
        val tail = TokenBudget.truncateContext(text, TokenBudget.Keep.TAIL)
        val head = TokenBudget.truncateContext(text, TokenBudget.Keep.HEAD)
        assertTrue(TokenBudget.units(tail) <= TokenBudget.MAX_CONTEXT_UNITS)
        assertTrue(TokenBudget.units(head) <= TokenBudget.MAX_CONTEXT_UNITS)
        assertTrue(tail.endsWith("w400"))
        assertTrue(head.startsWith("w1 w2"))
        assertEquals("short", TokenBudget.truncateContext(" short "))
    }
}
