package io.github.katsuhidem.translation

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun matchesReferenceVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        // Longer than one 64-byte block.
        assertEquals(
            "2816597888e4a0d3a36b82b83316ab32680eb8f00f8cd3b904d681246d285a0e",
            Sha256.hex("a".repeat(100)),
        )
        assertEquals(
            "696c7f4f778907e77331420ee37dce88470b9d9b0cd66b9169a4e9bf48dee4ce",
            Sha256.hex("会議は来週の月曜日に変更されました。"),
        )
    }
}
