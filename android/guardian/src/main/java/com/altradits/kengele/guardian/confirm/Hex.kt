package com.altradits.kengele.guardian.confirm

/** Tiny hex helpers. Guardians are keys only, shown and typed as hex. */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun toHex(b: ByteArray): String {
        val chars = CharArray(b.size * 2)
        for (i in b.indices) {
            chars[i * 2] = DIGITS[(b[i].toInt() ushr 4) and 0xF]
            chars[i * 2 + 1] = DIGITS[b[i].toInt() and 0xF]
        }
        return String(chars)
    }

    /** Strictly parses even-length lowercase-or-upper hex. */
    fun fromHex(s: String): ByteArray {
        require(s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "not hex"
        }
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Short display form: first 12 … last 8 characters. */
    fun short(hex: String): String =
        if (hex.length <= 24) hex else hex.take(12) + "…" + hex.takeLast(8)
}
