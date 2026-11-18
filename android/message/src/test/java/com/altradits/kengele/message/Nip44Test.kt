package com.altradits.kengele.message

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** NIP-44 v2 against the spec's own vector plus the Go-pinned key schedule. */
class Nip44Test {
    @Test
    fun conversationKeyMatchesVector() {
        val ck = Nip44.conversationKey(Vectors.hex("activist_priv_hex"), Vectors.hex("guardian_pub_hex"))
        assertEquals(Vectors.str("conv_key_hex"), Vectors.bytesToHex(ck))
        // ECDH is symmetric: the guardian derives the same key.
        val reverse = Nip44.conversationKey(Vectors.hex("guardian_priv_hex"), Vectors.hex("activist_pub_hex"))
        assertArrayEquals(ck, reverse)
    }

    @Test
    fun messageKeysMatchVector() {
        val keys = Nip44.messageKeys(Vectors.hex("conv_key_hex"), Vectors.hex("nonce_hex"))
        assertEquals(Vectors.str("msg_cc20_key_hex"), Vectors.bytesToHex(keys.chachaKey))
        assertEquals(Vectors.str("msg_cc20_nonce_hex"), Vectors.bytesToHex(keys.chachaNonce))
        assertEquals(Vectors.str("msg_hmac_key_hex"), Vectors.bytesToHex(keys.hmacKey))
    }

    @Test
    fun officialSpecVector() {
        // nip44.vectors.json v2.valid.encrypt_decrypt[0], via the Go package.
        val ck = Vectors.hex("nip44_conv_hex")
        val nonce = Vectors.hex("nip44_nonce_hex")
        val payload = Nip44.encrypt("a".toByteArray(Charsets.UTF_8), ck, nonce)
        assertEquals(Vectors.str("nip44_payload"), Base64.getEncoder().encodeToString(payload))
        assertArrayEquals(
            "a".toByteArray(Charsets.UTF_8),
            Nip44.decrypt(Base64.getDecoder().decode(Vectors.str("nip44_payload")), ck),
        )
    }

    @Test
    fun calcPaddingBoundaries() {
        assertEquals(32, Nip44.calcPadding(1))
        assertEquals(32, Nip44.calcPadding(32))
        assertEquals(64, Nip44.calcPadding(33))
        assertEquals(160, Nip44.calcPadding(160))
        assertEquals(192, Nip44.calcPadding(162))
        assertEquals(320, Nip44.calcPadding(257))
    }

    @Test
    fun roundTripAndTamperFails() {
        val ck = Vectors.hex("conv_key_hex")
        val nonce = Vectors.hex("nonce_hex")
        val plain = "Kamau alifika Kisumu".toByteArray(Charsets.UTF_8)
        val payload = Nip44.encrypt(plain, ck, nonce)
        assertArrayEquals(plain, Nip44.decrypt(payload, ck))
        // Flip a ciphertext byte: the MAC must fail.
        val tampered = payload.copyOf().also { it[50] = (it[50].toInt() xor 1).toByte() }
        try {
            Nip44.decrypt(tampered, ck)
            fail("tampered payload must not decrypt")
        } catch (e: MessageException) {
            // Expected.
        }
        // Wrong key must fail too.
        val other = Vectors.testPriv("other-channel")
        val otherCk = Nip44.conversationKey(other, Vectors.hex("guardian_pub_hex"))
        try {
            Nip44.decrypt(payload, otherCk)
            fail("wrong key must not decrypt")
        } catch (e: MessageException) {
            // Expected.
        }
    }
}
