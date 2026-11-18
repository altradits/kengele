package com.altradits.kengele.message

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** ChaCha20 against the Go-generated raw vector. */
class ChaCha20Test {
    @Test
    fun matchesGoVector() {
        val out = ChaCha20.xor(
            Vectors.hex("chacha_key_hex"),
            Vectors.hex("chacha_nonce_hex"),
            Vectors.hex("chacha_plain_hex"),
        )
        assertEquals(Vectors.str("chacha_cipher_hex"), Vectors.bytesToHex(out))
    }

    @Test
    fun roundTripAcrossBlocks() {
        val key = Vectors.hex("chacha_key_hex")
        val nonce = Vectors.hex("chacha_nonce_hex")
        val data = ByteArray(200) { i -> (i * 7 + 1).toByte() } // spans 4 blocks
        val cipher = ChaCha20.xor(key, nonce, data)
        assertArrayEquals(data, ChaCha20.xor(key, nonce, cipher))
        assertEquals(0, ChaCha20.xor(key, nonce, ByteArray(0)).size)
    }
}
