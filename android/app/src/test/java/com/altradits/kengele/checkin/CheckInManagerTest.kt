package com.altradits.kengele.checkin

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.MessageException
import com.altradits.kengele.message.Vectors
import com.altradits.kengele.pin.PinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Check-in/duress/panic kind routing, with the duress UI-equality test. */
class CheckInManagerTest {
    private val activistPriv = Vectors.hex("activist_priv_hex")
    private val guardianPriv = Vectors.hex("guardian_priv_hex")
    private val activistPub = Vectors.hex("activist_pub_hex")
    private val guardianPub = Vectors.hex("guardian_pub_hex")
    private val created = Vectors.num("event_created_unix")
    private val now = created + 3600

    private fun openAsGuardian(text: String): KengeleMessage.Event =
        KengeleMessage.open(
            KengeleMessage.decodeText(text), guardianPriv, listOf(activistPub), now,
        )

    @Test
    fun realCheckInSealsToEveryGuardian() {
        val secondPriv = Vectors.testPriv("second-guardian")
        val secondPub = KengeleMessage.publicKey(secondPriv)
        val sender = RecordingSender()
        val outcome = CheckInManager(sender).checkIn(
            PinResult.REAL, created, 3, "Kisumu".toByteArray(Charsets.UTF_8),
            activistPriv, listOf(guardianPub, secondPub),
        )
        assertEquals(KengeleMessage.Kind.CHECK_IN, outcome.kind)
        assertTrue(outcome.ui.success)
        assertEquals(2, outcome.texts.size)
        assertEquals(listOf(outcome.texts), sender.batches)
        for (text in outcome.texts) assertEquals(KengeleMessage.TEXT_SIZE, text.length)
        // Each guardian opens their own copy.
        val first = openAsGuardian(outcome.texts[0])
        assertEquals(KengeleMessage.Kind.CHECK_IN, first.kind)
        assertEquals("Kisumu", String(first.body, Charsets.UTF_8))
        assertEquals(3L, first.seq)
        val second = KengeleMessage.open(
            KengeleMessage.decodeText(outcome.texts[1]), secondPriv, listOf(activistPub), now,
        )
        assertEquals(KengeleMessage.Kind.CHECK_IN, second.kind)
    }

    @Test
    fun duressShowsIdenticalUiButSendsDuress() {
        val realOutcome = CheckInManager(RecordingSender()).checkIn(
            PinResult.REAL, created, 3, "Kisumu".toByteArray(Charsets.UTF_8),
            activistPriv, listOf(guardianPub),
        )
        val duressOutcome = CheckInManager(RecordingSender()).checkIn(
            PinResult.DURESS, created, 3, "Kisumu".toByteArray(Charsets.UTF_8),
            activistPriv, listOf(guardianPub),
        )
        // Identical screen...
        assertEquals(realOutcome.ui, duressOutcome.ui)
        assertTrue(duressOutcome.ui.success)
        // ...identical shape on the wire...
        assertEquals(realOutcome.texts.size, duressOutcome.texts.size)
        for (i in realOutcome.texts.indices) {
            assertEquals(realOutcome.texts[i].length, duressOutcome.texts[i].length)
        }
        // ...but the guardians read a duress check-in.
        assertEquals(KengeleMessage.Kind.DURESS_CHECK_IN, duressOutcome.kind)
        val opened = openAsGuardian(duressOutcome.texts[0])
        assertEquals(KengeleMessage.Kind.DURESS_CHECK_IN, opened.kind)
        assertEquals("Kisumu", String(opened.body, Charsets.UTF_8))
    }

    @Test
    fun wrongPinSendsNothing() {
        val sender = RecordingSender()
        val outcome = CheckInManager(sender).checkIn(
            PinResult.WRONG, created, 3, "Kisumu".toByteArray(Charsets.UTF_8),
            activistPriv, listOf(guardianPub),
        )
        assertNull(outcome.kind)
        assertTrue(outcome.texts.isEmpty())
        assertTrue(sender.batches.isEmpty())
        assertEquals("wrong_pin", outcome.ui.messageKey)
    }

    @Test
    fun panicSendsEmptyPanic() {
        val sender = RecordingSender()
        val outcome = CheckInManager(sender).panic(created, 4, activistPriv, listOf(guardianPub))
        assertEquals(KengeleMessage.Kind.PANIC, outcome.kind)
        assertEquals("panic_sent", outcome.ui.messageKey)
        assertEquals(1, outcome.texts.size)
        val opened = openAsGuardian(outcome.texts[0])
        assertEquals(KengeleMessage.Kind.PANIC, opened.kind)
        assertEquals(0, opened.body.size)
        assertEquals(4L, opened.seq)
    }

    @Test
    fun rejectsMissingGuardiansAndLongPlace() {
        val manager = CheckInManager(RecordingSender())
        try {
            manager.checkIn(PinResult.REAL, created, 3, ByteArray(0), activistPriv, emptyList())
            fail("check-in without guardians must fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        try {
            manager.panic(created, 3, activistPriv, emptyList())
            fail("panic without guardians must fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        try {
            manager.checkIn(
                PinResult.REAL, created, 3,
                ByteArray(KengeleMessage.MAX_BODY_SIZE + 1), activistPriv, listOf(guardianPub),
            )
            fail("over-long place must fail")
        } catch (e: MessageException) {
            // Expected.
        }
    }
}
