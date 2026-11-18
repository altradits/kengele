package com.altradits.kengele.sms

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.pin.PinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch-kind mapping shared by the check-in path (PinEntryActivity) and
 * the panic path (MainActivity): the same SmsSender gating sees the same
 * kinds on both.
 */
class SmsBatchKindTest {
    @Test
    fun checkInMapsRealDuressAndWrong() {
        assertEquals(KengeleMessage.Kind.CHECK_IN, SmsBatchKind.forCheckIn(PinResult.REAL))
        assertEquals(KengeleMessage.Kind.DURESS_CHECK_IN, SmsBatchKind.forCheckIn(PinResult.DURESS))
        // Unused — CheckInManager sends nothing for a wrong PIN — but valid.
        assertEquals(KengeleMessage.Kind.CHECK_IN, SmsBatchKind.forCheckIn(PinResult.WRONG))
    }

    @Test
    fun panicIsAnAlarmKind() {
        assertEquals(KengeleMessage.Kind.PANIC, SmsBatchKind.forPanic())
        assertTrue(SmsProfile.ALARM_KINDS.contains(SmsBatchKind.forPanic()))
    }

    @Test
    fun panicSendsUnderBothProfilesInBothModes() {
        for (profile in listOf(SmsProfile.ALARM_ONLY, SmsProfile.SHUTDOWN_CHECKINS)) {
            assertTrue("$profile", profile.sends(SmsBatchKind.forPanic(), false))
            assertTrue("$profile shutdown", profile.sends(SmsBatchKind.forPanic(), true))
        }
        assertFalse(SmsProfile.OFF.sends(SmsBatchKind.forPanic(), true))
    }

    @Test
    fun checkInKindsKeepTheirGatingOnBothPaths() {
        // Ordinary check-ins: shutdown check-ins only, in shutdown mode.
        val ordinary = SmsBatchKind.forCheckIn(PinResult.REAL)
        assertFalse(SmsProfile.ALARM_ONLY.sends(ordinary, false))
        assertFalse(SmsProfile.ALARM_ONLY.sends(ordinary, true))
        assertFalse(SmsProfile.SHUTDOWN_CHECKINS.sends(ordinary, false))
        assertTrue(SmsProfile.SHUTDOWN_CHECKINS.sends(ordinary, true))
        // Duress check-ins are alarms: always, on both profiles.
        val duress = SmsBatchKind.forCheckIn(PinResult.DURESS)
        for (profile in listOf(SmsProfile.ALARM_ONLY, SmsProfile.SHUTDOWN_CHECKINS)) {
            assertTrue("$profile", profile.sends(duress, false))
            assertTrue("$profile shutdown", profile.sends(duress, true))
        }
    }
}
