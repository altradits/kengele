package com.altradits.kengele.message

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pinned interop with `internal/message`: the golden vector, the layout
 * sizes, and every strict-decoding rejection the Go side enforces.
 */
class KengeleMessageTest {
    private val activistPriv = Vectors.hex("activist_priv_hex")
    private val guardianPriv = Vectors.hex("guardian_priv_hex")
    private val activistPub = Vectors.hex("activist_pub_hex")
    private val guardianPub = Vectors.hex("guardian_pub_hex")
    private val created = Vectors.num("event_created_unix")
    private val now = created + 3600

    @Test
    fun constantsMatchVectors() {
        assertEquals(1, Vectors.num("format_version").toInt())
        assertEquals(KengeleMessage.WIRE_SIZE, Vectors.num("wire_size").toInt())
        assertEquals(KengeleMessage.TEXT_SIZE, Vectors.num("text_size").toInt())
        assertEquals(KengeleMessage.MAX_BODY_SIZE, Vectors.num("max_body_size").toInt())
        assertEquals(KengeleMessage.MAX_SMS_CHARS, Vectors.num("max_sms_chars").toInt())
        val kinds = mapOf(
            "kind_check_in" to KengeleMessage.Kind.CHECK_IN,
            "kind_duress" to KengeleMessage.Kind.DURESS_CHECK_IN,
            "kind_panic" to KengeleMessage.Kind.PANIC,
            "kind_missed" to KengeleMessage.Kind.MISSED,
            "kind_confirm" to KengeleMessage.Kind.GUARDIAN_CONFIRM,
            "kind_all_clear" to KengeleMessage.Kind.ALL_CLEAR,
            "kind_going_dark" to KengeleMessage.Kind.GOING_DARK,
        )
        for ((key, kind) in kinds) assertEquals(key, Vectors.num(key).toInt(), kind.value)
    }

    @Test
    fun goldenVectorSealsToPinnedText() {
        val inner = Vectors.hex("inner_hex")
        val wire = KengeleMessage.sealInner(inner, activistPriv, guardianPub, Vectors.hex("nonce_hex"))
        assertEquals(Vectors.str("golden_text"), KengeleMessage.encodeText(wire))
    }

    @Test
    fun goldenVectorOpensToPinnedEvent() {
        val wire = KengeleMessage.decodeText(Vectors.str("golden_text"))
        val ev = KengeleMessage.open(wire, guardianPriv, listOf(activistPub), now)
        assertEquals(KengeleMessage.Kind.CHECK_IN, ev.kind)
        assertEquals(created, ev.createdAtSecs)
        assertEquals(1L, ev.seq)
        assertEquals("Kisumu", String(ev.body, Charsets.UTF_8))
        assertArrayEquals(activistPub, ev.signer)
    }

    @Test
    fun signedHashMatchesVector() {
        val inner = Vectors.hex("inner_hex")
        val h = KengeleMessage.signedHash(inner.copyOfRange(0, KengeleMessage.SIGNED_SIZE))
        assertEquals(Vectors.str("signed_hash_hex"), Vectors.bytesToHex(h))
        assertTrue(Secp256k1.schnorrVerify(activistPub, h, inner.copyOfRange(96, 160)))
    }

    @Test
    fun roundTripEveryKind() {
        val bodies = listOf(
            ByteArray(0),
            "Kenyatta Ave, Kisumu".toByteArray(Charsets.UTF_8),
            ByteArray(KengeleMessage.MAX_BODY_SIZE) { 0xFF.toByte() },
            "Mahakama ya Milimani — saa nane".toByteArray(Charsets.UTF_8),
        )
        for (kind in KengeleMessage.Kind.values()) {
            for (body in bodies) {
                val ev = KengeleMessage.Event(kind, created, 42, body)
                val wire = KengeleMessage.seal(ev, activistPriv, guardianPub)
                val back = KengeleMessage.decodeText(KengeleMessage.encodeText(wire))
                val got = KengeleMessage.open(back, guardianPriv, listOf(activistPub), now)
                assertEquals(kind, got.kind)
                assertEquals(42L, got.seq)
                assertEquals(created, got.createdAtSecs)
                assertArrayEquals(body, got.body)
                assertArrayEquals(activistPub, got.signer)
            }
        }
    }

    @Test
    fun everyKindHasTheSameLength() {
        val bodies = listOf(ByteArray(0), ByteArray(KengeleMessage.MAX_BODY_SIZE) { 0x79 })
        val normal = KengeleMessage.seal(
            KengeleMessage.Event(KengeleMessage.Kind.CHECK_IN, created, 1, "x".toByteArray()),
            activistPriv, guardianPub,
        )
        for (kind in KengeleMessage.Kind.values()) {
            for (body in bodies) {
                val w = KengeleMessage.seal(
                    KengeleMessage.Event(kind, created, 1, body), activistPriv, guardianPub,
                )
                assertEquals("wire size for $kind", normal.size, w.size)
                assertEquals(
                    "text size for $kind",
                    KengeleMessage.encodeText(normal).length,
                    KengeleMessage.encodeText(w).length,
                )
            }
        }
    }

    @Test
    fun textFormFitsTwoSmsAndGsm7() {
        val gsm7 = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
        for (kind in KengeleMessage.Kind.values()) {
            val w = KengeleMessage.seal(
                KengeleMessage.Event(kind, created, 1, ByteArray(KengeleMessage.MAX_BODY_SIZE) { 0x78 }),
                activistPriv, guardianPub,
            )
            val text = KengeleMessage.encodeText(w)
            assertEquals(KengeleMessage.TEXT_SIZE, text.length)
            assertTrue(text.length <= KengeleMessage.MAX_SMS_CHARS)
            for (c in text) assertTrue("char $c in GSM-7", gsm7.contains(c))
        }
    }

    private fun expectMessageError(block: () -> Unit): String {
        try {
            block()
        } catch (e: MessageException) {
            return e.message ?: ""
        }
        fail("expected MessageException")
        error("unreachable")
    }

    /** Re-signs a hand-edited inner so the test reaches field checks, not the signature. */
    private fun resign(inner: ByteArray): ByteArray {
        val out = inner.copyOf()
        val sig = Secp256k1.schnorrSign(
            activistPriv,
            KengeleMessage.signedHash(out.copyOfRange(0, KengeleMessage.SIGNED_SIZE)),
        )
        sig.copyInto(out, KengeleMessage.SIGNED_SIZE)
        return out
    }

    private fun sealInnerDefault(inner: ByteArray): ByteArray =
        KengeleMessage.sealInner(inner, activistPriv, guardianPub)

    @Test
    fun rejectsBadSignature() {
        val inner = Vectors.hex("inner_hex").copyOf()
        inner[inner.size - 1] = (inner[inner.size - 1].toInt() xor 1).toByte()
        val msg = expectMessageError {
            KengeleMessage.open(sealInnerDefault(inner), guardianPriv, listOf(activistPub), now)
        }
        assertTrue(msg, msg.contains("signature"))
    }

    @Test
    fun rejectsGuardianForgingActivist() {
        // Guardian 1 shares the conversation key, so the ciphertext decrypts;
        // the activist's signature check is what must stop the forgery.
        val forged = KengeleMessage.sign(
            KengeleMessage.Event(KengeleMessage.Kind.ALL_CLEAR, created, 1), guardianPriv,
        )
        val msg = expectMessageError {
            KengeleMessage.open(sealInnerDefault(forged), guardianPriv, listOf(activistPub), now)
        }
        assertTrue(msg, msg.contains("signature"))
    }

    @Test
    fun rejectsWrongRecipientAndUnknownSender() {
        val wire = KengeleMessage.seal(
            KengeleMessage.Event(KengeleMessage.Kind.CHECK_IN, created, 1), activistPriv, guardianPub,
        )
        val strangerPriv = Vectors.testPriv("stranger")
        val strangerPub = KengeleMessage.publicKey(strangerPriv)
        expectMessageError { KengeleMessage.open(wire, strangerPriv, listOf(activistPub), now) }
        expectMessageError { KengeleMessage.open(wire, guardianPriv, listOf(strangerPub), now) }
    }

    @Test
    fun rejectsAlteredText() {
        val text = KengeleMessage.encodeText(
            KengeleMessage.seal(
                KengeleMessage.Event(KengeleMessage.Kind.CHECK_IN, created, 1), activistPriv, guardianPub,
            ),
        ).toCharArray()
        text[100] = if (text[100] == 'A') 'B' else 'A'
        val wire = KengeleMessage.decodeText(String(text))
        expectMessageError { KengeleMessage.open(wire, guardianPriv, listOf(activistPub), now) }
    }

    @Test
    fun rejectsTruncatedTextPaddingCharAndWhitespace() {
        val good = Vectors.str("golden_text")
        expectMessageError { KengeleMessage.decodeText(good.dropLast(4)) }
        expectMessageError { KengeleMessage.decodeText(good.dropLast(1) + "=") }
        expectMessageError { KengeleMessage.decodeText(good + "\n") }
        expectMessageError { KengeleMessage.decodeText(" " + good.dropLast(1)) }
    }

    @Test
    fun rejectsUnknownVersionAndShortWire() {
        val good = KengeleMessage.decodeText(Vectors.str("golden_text"))
        val wrongVersion = good.copyOf().also { it[0] = 0x02 }
        expectMessageError { KengeleMessage.open(wrongVersion, guardianPriv, listOf(activistPub), now) }
        expectMessageError {
            KengeleMessage.open(good.copyOf(good.size - 1), guardianPriv, listOf(activistPub), now)
        }
    }

    @Test
    fun rejectsFutureTimestamp() {
        val wire = KengeleMessage.seal(
            KengeleMessage.Event(
                KengeleMessage.Kind.CHECK_IN,
                now + KengeleMessage.MAX_CLOCK_SKEW_SECS + 60, 1,
            ),
            activistPriv, guardianPub,
        )
        expectMessageError { KengeleMessage.open(wire, guardianPriv, listOf(activistPub), now) }
    }

    @Test
    fun rejectsNonZeroPadding() {
        val inner = KengeleMessage.sign(
            KengeleMessage.Event(KengeleMessage.Kind.CHECK_IN, created, 1, "a".toByteArray()),
            activistPriv,
        )
        inner[12] = 'z'.code.toByte()
        val msg = expectMessageError {
            KengeleMessage.open(sealInnerDefault(resign(inner)), guardianPriv, listOf(activistPub), now)
        }
        assertTrue(msg, msg.contains("padding"))
    }

    @Test
    fun rejectsUnknownKind() {
        val inner = KengeleMessage.sign(
            KengeleMessage.Event(KengeleMessage.Kind.CHECK_IN, created, 1), activistPriv,
        )
        inner[0] = 99.toByte()
        val msg = expectMessageError {
            KengeleMessage.open(sealInnerDefault(resign(inner)), guardianPriv, listOf(activistPub), now)
        }
        assertTrue(msg, msg.contains("kind"))
    }

    @Test
    fun rejectsBadSealInputs() {
        val big = ByteArray(KengeleMessage.MAX_BODY_SIZE + 1)
        expectMessageError {
            KengeleMessage.seal(
                KengeleMessage.Event(KengeleMessage.Kind.CHECK_IN, created, 1, big),
                activistPriv, guardianPub,
            )
        }
        expectMessageError {
            KengeleMessage.seal(
                KengeleMessage.Event(KengeleMessage.Kind.CHECK_IN, 0, 1), activistPriv, guardianPub,
            )
        }
    }
}
