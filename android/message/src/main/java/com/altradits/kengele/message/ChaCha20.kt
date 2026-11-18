package com.altradits.kengele.message

/**
 * IETF ChaCha20 (RFC 8439, section 2) with a 12-byte nonce and a 32-bit
 * initial counter of 0, matching x/crypto's NewUnauthenticatedCipher used by
 * the Go side. Hand-rolled so the port needs no crypto provider beyond the
 * JDK; pinned against Go-generated vectors in vectors.json.
 */
internal object ChaCha20 {
    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    private fun loadLe(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun storeLe(v: Int, b: ByteArray, o: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte()
        b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun block(key: ByteArray, counter: Int, nonce: ByteArray, out: ByteArray, outOff: Int) {
        val s = IntArray(16)
        s[0] = 0x61707865
        s[1] = 0x3320646e
        s[2] = 0x79622d32
        s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = loadLe(key, i * 4)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = loadLe(nonce, i * 4)
        val w = s.copyOf()
        repeat(10) {
            quarterRound(w, 0, 4, 8, 12)
            quarterRound(w, 1, 5, 9, 13)
            quarterRound(w, 2, 6, 10, 14)
            quarterRound(w, 3, 7, 11, 15)
            quarterRound(w, 0, 5, 10, 15)
            quarterRound(w, 1, 6, 11, 12)
            quarterRound(w, 2, 7, 8, 13)
            quarterRound(w, 3, 4, 9, 14)
        }
        for (i in 0 until 16) storeLe(w[i] + s[i], out, outOff + i * 4)
    }

    /**
     * Encrypts/decrypts [data] in place semantics (returns a new array):
     * keystream with counter starting at 0, XORed over the input.
     */
    fun xor(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }
        val out = ByteArray(data.size)
        val ks = ByteArray(64)
        var counter = 0
        var off = 0
        while (off < data.size) {
            block(key, counter, nonce, ks, 0)
            val take = minOf(64, data.size - off)
            for (i in 0 until take) out[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            off += take
            counter++
        }
        return out
    }
}
