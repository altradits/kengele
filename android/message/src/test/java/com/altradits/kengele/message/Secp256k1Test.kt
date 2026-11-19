package com.altradits.kengele.message

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** secp256k1 + BIP-340 against the Go-pinned keys and signature. */
class Secp256k1Test {
    private val activistPriv = Vectors.hex("activist_priv_hex")
    private val activistPub = Vectors.hex("activist_pub_hex")

    @Test
    fun publicKeysMatchVectors() {
        assertEquals(Vectors.str("activist_pub_hex"), Vectors.bytesToHex(Secp256k1.publicKey(activistPriv)))
        assertEquals(
            Vectors.str("guardian_pub_hex"),
            Vectors.bytesToHex(Secp256k1.publicKey(Vectors.hex("guardian_priv_hex"))),
        )
    }

    @Test
    fun verifiesGoSignature() {
        assertTrue(
            Secp256k1.schnorrVerify(activistPub, Vectors.hex("signed_hash_hex"), Vectors.hex("signature_hex")),
        )
    }

    @Test
    fun signVerifyRoundTrip() {
        val msg = Vectors.hex("signed_hash_hex")
        val sig = Secp256k1.schnorrSign(activistPriv, msg)
        assertTrue(Secp256k1.schnorrVerify(activistPub, msg, sig))
        // Fixed aux is deterministic; random aux differs but still verifies.
        val aux = ByteArray(32) { 0x07 }
        assertArrayEquals(
            Secp256k1.schnorrSign(activistPriv, msg, aux),
            Secp256k1.schnorrSign(activistPriv, msg, aux),
        )
    }

    @Test
    fun verifyRejectsWrongKeyMessageAndBitFlip() {
        val msg = Vectors.hex("signed_hash_hex")
        val sig = Vectors.hex("signature_hex").copyOf()
        assertFalse(Secp256k1.schnorrVerify(Vectors.hex("guardian_pub_hex"), msg, sig))
        val other = msg.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(Secp256k1.schnorrVerify(activistPub, other, sig))
        sig[sig.size - 1] = (sig[sig.size - 1].toInt() xor 1).toByte()
        assertFalse(Secp256k1.schnorrVerify(activistPub, msg, sig))
    }

    @Test
    fun liftXAcceptsCurveRejectsTheRest() {
        // Known-good keys lift.
        Secp256k1.liftX(activistPub)
        // Out of range or the wrong size must fail.
        for (bad in listOf(ByteArray(32) { 0xFF.toByte() }, ByteArray(31), ByteArray(33))) {
            try {
                Secp256k1.liftX(bad)
                fail("liftX must reject ${Vectors.bytesToHex(bad)}")
            } catch (e: MessageException) {
                // Expected.
            }
        }
        // About half of small x values are non-residues; find one deterministically.
        var rejected = 0
        for (i in 1..50) {
            val x = ByteArray(32).also { it[31] = i.toByte() }
            try {
                Secp256k1.liftX(x)
            } catch (e: MessageException) {
                rejected++
            }
        }
        assertTrue("expected some non-residues, got $rejected", rejected > 0)
    }

    @Test
    fun rejectsBadPrivateKeys() {
        for (bad in listOf(ByteArray(32), ByteArray(31), ByteArray(32) { 0xFF.toByte() })) {
            try {
                Secp256k1.publicKey(bad)
                fail("must reject private key ${Vectors.bytesToHex(bad)}")
            } catch (e: MessageException) {
                // Expected (zero, short, or >= curve order).
            }
        }
    }
}
