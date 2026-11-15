package io.github.katsuhidem.translation

/**
 * SHA-256 in common code, for cache keys. Keys must come out the same on
 * every platform and without loading a model, so no platform API is used.
 */
internal object Sha256 {
    private val K: IntArray = (
        "428a2f98 71374491 b5c0fbcf e9b5dba5 3956c25b 59f111f1 923f82a4 ab1c5ed5 " +
            "d807aa98 12835b01 243185be 550c7dc3 72be5d74 80deb1fe 9bdc06a7 c19bf174 " +
            "e49b69c1 efbe4786 0fc19dc6 240ca1cc 2de92c6f 4a7484aa 5cb0a9dc 76f988da " +
            "983e5152 a831c66d b00327c8 bf597fc7 c6e00bf3 d5a79147 06ca6351 14292967 " +
            "27b70a85 2e1b2138 4d2c6dfc 53380d13 650a7354 766a0abb 81c2c92e 92722c85 " +
            "a2bfe8a1 a81a664b c24b8b70 c76c51a3 d192e819 d6990624 f40e3585 106aa070 " +
            "19a4c116 1e376c08 2748774c 34b0bcb5 391c0cb3 4ed8aa4a 5b9cca4f 682e6ff3 " +
            "748f82ee 78a5636f 84c87814 8cc70208 90befffa a4506ceb bef9a3f7 c67178f2"
        ).words()

    private val H0: IntArray =
        "6a09e667 bb67ae85 3c6ef372 a54ff53a 510e527f 9b05688c 1f83d9ab 5be0cd19".words()

    private fun String.words(): IntArray = split(' ').map { it.toUInt(16).toInt() }.toIntArray()

    fun hex(text: String): String = hex(text.encodeToByteArray())

    fun hex(input: ByteArray): String {
        val h = H0.copyOf()
        val bitLen = input.size.toLong() * 8
        val padded = ByteArray(((input.size + 9 + 63) / 64) * 64)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLen ushr (8 * i)).toByte()

        val w = IntArray(64)
        for (chunk in padded.indices step 64) {
            for (i in 0 until 16) {
                val o = chunk + i * 4
                w[i] = ((padded[o].toInt() and 0xff) shl 24) or ((padded[o + 1].toInt() and 0xff) shl 16) or
                    ((padded[o + 2].toInt() and 0xff) shl 8) or (padded[o + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return buildString(64) {
            for (v in h) {
                for (shift in 28 downTo 0 step 4) append(HEX[(v ushr shift) and 0xf])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}
