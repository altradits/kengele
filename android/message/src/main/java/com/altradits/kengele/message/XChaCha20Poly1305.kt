package com.altradits.kengele.message

import java.math.BigInteger

/**
 * XChaCha20-Poly1305 (draft-arciszewski-xchacha, as `x/crypto`'s `NewX` used
 * by `internal/confirm`): HChaCha20 derives a subkey from the first 16 nonce
 * bytes, then IETF ChaCha20-Poly1305 (RFC 8439 section 2.8) runs with a
 * 12-byte nonce of four zero bytes plus the last 8 nonce bytes. Hand-rolled
 * over [ChaCha20] so the port needs no crypto provider beyond the JDK; pinned
 * against Go-sealed vectors in `xchacha-vectors.json` and the RFC 8439
 * section A.5 vector.
 */
object XChaCha20Poly1305 {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 24
    const val TAG_SIZE = 16

    /**
     * Seals [plaintext] with [key] and a 24-byte [nonce], authenticating
     * [ad]. Returns ciphertext plus the 16-byte tag.
     */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "XChaCha20-Poly1305 key must be 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "XChaCha20-Poly1305 nonce must be 24 bytes" }
        val subKey = ChaCha20.hChaCha20(key, nonce.copyOfRange(0, 16))
        val nonce12 = ByteArray(12).also { nonce.copyInto(it, 4, 16, 24) }
        return sealIetf(subKey, nonce12, plaintext, ad)
    }

    /**
     * Opens [ciphertextAndTag] (ciphertext plus the 16-byte tag). Throws
     * [MessageException] when the tag does not verify; nothing is returned on
     * failure.
     */
    fun open(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, ad: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "XChaCha20-Poly1305 key must be 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "XChaCha20-Poly1305 nonce must be 24 bytes" }
        if (ciphertextAndTag.size < TAG_SIZE) throw MessageException("ciphertext too short")
        val subKey = ChaCha20.hChaCha20(key, nonce.copyOfRange(0, 16))
        val nonce12 = ByteArray(12).also { nonce.copyInto(it, 4, 16, 24) }
        return openIetf(subKey, nonce12, ciphertextAndTag, ad)
    }

    /** IETF ChaCha20-Poly1305 with an explicit subkey and 12-byte nonce. */
    internal fun sealIetf(subKey: ByteArray, nonce12: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray {
        val polyKey = ChaCha20.blockBytes(subKey, 0, nonce12).copyOfRange(0, 32)
        val ciphertext = ChaCha20.xorFromCounter(subKey, nonce12, 1, plaintext)
        val tag = Poly1305.mac(polyKey, macData(ad, ciphertext))
        return ciphertext + tag
    }

    internal fun openIetf(
        subKey: ByteArray,
        nonce12: ByteArray,
        ciphertextAndTag: ByteArray,
        ad: ByteArray,
    ): ByteArray {
        val split = ciphertextAndTag.size - TAG_SIZE
        val ciphertext = ciphertextAndTag.copyOfRange(0, split)
        val tag = ciphertextAndTag.copyOfRange(split, ciphertextAndTag.size)
        val polyKey = ChaCha20.blockBytes(subKey, 0, nonce12).copyOfRange(0, 32)
        val expect = Poly1305.mac(polyKey, macData(ad, ciphertext))
        if (!constantTimeEquals(tag, expect)) throw MessageException("authentication failed")
        return ChaCha20.xorFromCounter(subKey, nonce12, 1, ciphertext)
    }

    private fun macData(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val out = ByteArray(pad16(ad.size) + pad16(ciphertext.size) + 16)
        var o = 0
        ad.copyInto(out, o)
        o += pad16(ad.size)
        ciphertext.copyInto(out, o)
        o += pad16(ciphertext.size)
        storeLe64(ad.size.toLong(), out, o)
        storeLe64(ciphertext.size.toLong(), out, o + 8)
        return out
    }

    private fun pad16(n: Int): Int = n + ((16 - n % 16) % 16)

    private fun storeLe64(v: Long, b: ByteArray, o: Int) {
        for (i in 0 until 8) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** Poly1305 (RFC 8439 section 2.5) over BigInteger, in plain JDK code. */
    internal object Poly1305 {
        private val P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
        private val R_MASK = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)
        private val MOD128 = BigInteger.ONE.shiftLeft(128)

        fun mac(key32: ByteArray, msg: ByteArray): ByteArray {
            require(key32.size == 32) { "Poly1305 key must be 32 bytes" }
            val r = fromLe(key32.copyOfRange(0, 16)).and(R_MASK)
            val s = fromLe(key32.copyOfRange(16, 32))
            var acc = BigInteger.ZERO
            var off = 0
            while (off < msg.size) {
                val take = minOf(16, msg.size - off)
                val n = fromLe(msg.copyOfRange(off, off + take))
                    .add(BigInteger.ONE.shiftLeft(8 * take))
                acc = acc.add(n).multiply(r).mod(P)
                off += take
            }
            return toLe128(acc.add(s).mod(MOD128))
        }

        private fun fromLe(b: ByteArray): BigInteger {
            var v = BigInteger.ZERO
            for (i in b.indices.reversed()) {
                v = v.shiftLeft(8).add(BigInteger.valueOf((b[i].toInt() and 0xFF).toLong()))
            }
            return v
        }

        private fun toLe128(v: BigInteger): ByteArray {
            var x = v
            return ByteArray(16) {
                val byte = x.and(BigInteger.valueOf(0xFF)).toInt().toByte()
                x = x.shiftRight(8)
                byte
            }
        }
    }
}
