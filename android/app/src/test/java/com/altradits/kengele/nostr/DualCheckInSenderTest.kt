package com.altradits.kengele.nostr

import com.altradits.kengele.checkin.RecordingSender
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.sms.InMemorySmsDirectory
import com.altradits.kengele.sms.SmsProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dual-send routing: Nostr-only, SMS-only, both, neither — plus the
 * nothing-configured gate ([smsWillSend]) matching the SMS profile rules.
 * Phone numbers are in the fictional 555-01XX range.
 */
class DualCheckInSenderTest {
    private val texts = listOf("TEXT-A-304-chars", "TEXT-B-304-chars")

    @Test
    fun bothTransportsGetTheSameBatch() {
        val nostr = RecordingSender()
        val sms = RecordingSender()
        DualCheckInSender(nostr = nostr, sms = sms).send(texts)
        assertEquals(listOf(texts), nostr.batches)
        assertEquals(listOf(texts), sms.batches)
    }

    @Test
    fun nostrOnlyWhenSmsSideAbsent() {
        val nostr = RecordingSender()
        DualCheckInSender(nostr = nostr, sms = null).send(texts)
        assertEquals(listOf(texts), nostr.batches)
    }

    @Test
    fun smsOnlyWhenNostrSideAbsent() {
        val sms = RecordingSender()
        DualCheckInSender(nostr = null, sms = sms).send(texts)
        assertEquals(listOf(texts), sms.batches)
    }

    @Test
    fun neitherSideIsANoop() {
        // Nothing configured: no send, no crash.
        DualCheckInSender(nostr = null, sms = null).send(texts)
    }

    private fun directory(vararg profiles: SmsProfile?): InMemorySmsDirectory {
        val dir = InMemorySmsDirectory()
        profiles.forEachIndexed { i, profile ->
            if (profile != null) dir.set(i, "+1555010000$i", profile)
        }
        return dir
    }

    @Test
    fun smsWillSendMatchesProfileGating() {
        val panic = KengeleMessage.Kind.PANIC
        val checkIn = KengeleMessage.Kind.CHECK_IN
        // Off everywhere, or nobody listed: ordinary check-ins stay
        // Nostr-only, and panics have no SMS leg either.
        assertFalse(smsWillSend(directory(), 1, panic, false))
        assertFalse(smsWillSend(directory(SmsProfile.OFF), 1, panic, false))
        assertFalse(smsWillSend(directory(SmsProfile.OFF), 1, checkIn, false))
        // Alarm-only: panics go, ordinary check-ins never do.
        assertTrue(smsWillSend(directory(SmsProfile.ALARM_ONLY), 1, panic, false))
        assertFalse(smsWillSend(directory(SmsProfile.ALARM_ONLY), 1, checkIn, false))
        assertFalse(smsWillSend(directory(SmsProfile.ALARM_ONLY), 1, checkIn, true))
        // Shutdown check-ins: ordinary check-ins only in shutdown mode.
        assertTrue(smsWillSend(directory(SmsProfile.SHUTDOWN_CHECKINS), 1, panic, false))
        assertFalse(smsWillSend(directory(SmsProfile.SHUTDOWN_CHECKINS), 1, checkIn, false))
        assertTrue(smsWillSend(directory(SmsProfile.SHUTDOWN_CHECKINS), 1, checkIn, true))
        // One opted-in guardian is enough; entries past the batch are ignored.
        assertTrue(smsWillSend(directory(null, SmsProfile.ALARM_ONLY), 2, panic, false))
        assertFalse(smsWillSend(directory(null, SmsProfile.ALARM_ONLY), 1, panic, false))
        // Duress is an alarm kind: it always has an SMS leg when opted in.
        assertTrue(smsWillSend(directory(SmsProfile.ALARM_ONLY), 1, KengeleMessage.Kind.DURESS_CHECK_IN, false))
    }
}
