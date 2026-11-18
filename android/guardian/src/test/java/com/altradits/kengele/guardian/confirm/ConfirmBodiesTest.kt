package com.altradits.kengele.guardian.confirm

import com.altradits.kengele.message.KengeleMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Confirm and all-clear bodies: sizes, roundtrips, builds and gating. */
class ConfirmBodiesTest {
    private val activist = TestKeys.testPub("bodies-activist")
    private val share = ShamirVectors.named("k2-of-n3").shares[0]
    private val now = 1_800_000_000L

    @Test
    fun bodiesFitInsideAMessage() {
        assertEquals(70, ConfirmBodies.CONFIRM_BODY_SIZE)
        assertEquals(37, ConfirmBodies.ALL_CLEAR_BODY_SIZE)
        assertTrue(ConfirmBodies.CONFIRM_BODY_SIZE <= KengeleMessage.MAX_BODY_SIZE)
        assertTrue(ConfirmBodies.ALL_CLEAR_BODY_SIZE <= KengeleMessage.MAX_BODY_SIZE)
    }

    @Test
    fun confirmRoundtrip() {
        val body = ConfirmBodies.encodeConfirm(activist, 3, share)
        assertEquals(70, body.size)
        val back = ConfirmBodies.decodeConfirm(body)
        assertArrayEquals(activist, back.activist)
        assertEquals(3, back.epoch)
        assertArrayEquals(share, back.share)
    }

    @Test
    fun allClearRoundtrip() {
        val body = ConfirmBodies.encodeAllClear(activist, 7)
        assertEquals(37, body.size)
        val back = ConfirmBodies.decodeAllClear(body)
        assertArrayEquals(activist, back.activist)
        assertEquals(7, back.epoch)
    }

    @Test
    fun rejectsBadBodies() {
        val goodConfirm = ConfirmBodies.encodeConfirm(activist, 1, share)
        val goodClear = ConfirmBodies.encodeAllClear(activist, 1)
        // Wrong tag, wrong size, epoch 0, bad share size.
        val badConfirms = listOf(
            goodConfirm.copyOf().also { it[0] = 0x02 },
            goodConfirm.copyOf(69),
            ConfirmBodies.encodeConfirm(activist, 1, share).copyOf().also {
                it[33] = 0; it[34] = 0; it[35] = 0; it[36] = 0
            },
        )
        for (b in badConfirms) {
            try {
                ConfirmBodies.decodeConfirm(b)
                fail("decodeConfirm must fail")
            } catch (e: ConfirmException) {
                // Expected.
            }
        }
        try {
            ConfirmBodies.decodeAllClear(goodClear.copyOf().also { it[0] = 0x01 })
            fail("swapped tag must fail")
        } catch (e: ConfirmException) {
            // Expected.
        }
        try {
            ConfirmBodies.decodeAllClear(goodClear.copyOf(36))
            fail("short body must fail")
        } catch (e: ConfirmException) {
            // Expected.
        }
        try {
            ConfirmBodies.encodeConfirm(activist, 0, share)
            fail("epoch 0 must fail")
        } catch (e: ConfirmException) {
            // Expected.
        }
        try {
            ConfirmBodies.encodeAllClear(activist, 0)
            fail("epoch 0 must fail")
        } catch (e: ConfirmException) {
            // Expected.
        }
        try {
            ConfirmBodies.encodeConfirm(activist, 1, ByteArray(32))
            fail("short share must fail")
        } catch (e: ConfirmException) {
            // Expected.
        }
    }

    @Test
    fun guardianAllClearNeedsVoiceOrVideo() {
        // No method, wrong method, zero or future call time: all refused.
        for (v in listOf(
            ConfirmBodies.Verification("", now),
            ConfirmBodies.Verification("sms", now),
            ConfirmBodies.Verification("voice", 0),
            ConfirmBodies.Verification("video", now + KengeleMessage.MAX_CLOCK_SKEW_SECS + 1),
        )) {
            try {
                ConfirmBodies.guardianAllClearEvent(activist, 1, 1, now, v)
                fail("unverified all-clear ($v) must fail")
            } catch (e: ConfirmException) {
                // Expected.
            }
        }
        for (method in listOf("voice", "video")) {
            val e = ConfirmBodies.guardianAllClearEvent(
                activist, 2, 9, now, ConfirmBodies.Verification(method, now),
            )
            assertEquals(KengeleMessage.Kind.ALL_CLEAR, e.kind)
            val back = ConfirmBodies.decodeAllClear(e.body)
            assertArrayEquals(activist, back.activist)
            assertEquals(2, back.epoch)
        }
    }

    @Test
    fun confirmAndClearSurviveSealAndOpen() {
        // Built events seal to 304-char text and open back with intact bodies.
        val senderPriv = TestKeys.testPriv("bodies-sender")
        val senderPub = TestKeys.testPub("bodies-sender")
        val recipientPriv = TestKeys.testPriv("bodies-recipient")
        val recipientPub = TestKeys.testPub("bodies-recipient")

        val confirm = ConfirmBodies.confirmEvent(activist, 4, share, 11, now)
        val confirmText = KengeleMessage.encodeText(
            KengeleMessage.seal(confirm, senderPriv, recipientPub),
        )
        assertEquals(KengeleMessage.TEXT_SIZE, confirmText.length)
        val openedConfirm = KengeleMessage.open(
            KengeleMessage.decodeText(confirmText), recipientPriv, listOf(senderPub), now,
        )
        assertEquals(KengeleMessage.Kind.GUARDIAN_CONFIRM, openedConfirm.kind)
        assertArrayEquals(senderPub, openedConfirm.signer)
        val confirmBody = ConfirmBodies.decodeConfirm(openedConfirm.body)
        assertEquals(4, confirmBody.epoch)
        assertArrayEquals(share, confirmBody.share)

        val clear = ConfirmBodies.guardianAllClearEvent(
            activist, 4, 12, now, ConfirmBodies.Verification("video", now),
        )
        val clearText = KengeleMessage.encodeText(
            KengeleMessage.seal(clear, senderPriv, recipientPub),
        )
        assertEquals(KengeleMessage.TEXT_SIZE, clearText.length)
        val openedClear = KengeleMessage.open(
            KengeleMessage.decodeText(clearText), recipientPriv, listOf(senderPub), now,
        )
        assertEquals(KengeleMessage.Kind.ALL_CLEAR, openedClear.kind)
        assertEquals(4, ConfirmBodies.decodeAllClear(openedClear.body).epoch)
    }
}
