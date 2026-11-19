package com.altradits.kengele.sms

import com.altradits.kengele.message.KengeleMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-guardian opt-in gating: off by default, alarms vs shutdown check-ins. */
class SmsProfileTest {
    @Test
    fun offSendsNothing() {
        for (kind in KengeleMessage.Kind.values()) {
            assertEquals("OFF must send nothing ($kind, shutdown)", false, SmsProfile.OFF.sends(kind, true))
            assertEquals("OFF must send nothing ($kind)", false, SmsProfile.OFF.sends(kind, false))
        }
    }

    @Test
    fun alarmOnlySendsAlarmsInBothModes() {
        for (kind in KengeleMessage.Kind.values()) {
            val expected = kind in SmsProfile.ALARM_KINDS
            assertEquals("$kind alarm-only", expected, SmsProfile.ALARM_ONLY.sends(kind, false))
            assertEquals("$kind alarm-only shutdown", expected, SmsProfile.ALARM_ONLY.sends(kind, true))
        }
        assertEquals(
            setOf(
                KengeleMessage.Kind.PANIC,
                KengeleMessage.Kind.DURESS_CHECK_IN,
                KengeleMessage.Kind.GOING_DARK,
                KengeleMessage.Kind.ALL_CLEAR,
            ),
            SmsProfile.ALARM_KINDS,
        )
    }

    @Test
    fun shutdownCheckinsAddsOrdinaryCheckinsOnlyInShutdownMode() {
        for (kind in KengeleMessage.Kind.values()) {
            val expected = kind in SmsProfile.ALARM_KINDS ||
                (kind in SmsProfile.SHUTDOWN_KINDS)
            assertEquals(
                "$kind shutdown-check-ins in shutdown",
                expected,
                SmsProfile.SHUTDOWN_CHECKINS.sends(kind, true),
            )
            assertEquals(
                "$kind shutdown-check-ins outside shutdown",
                kind in SmsProfile.ALARM_KINDS,
                SmsProfile.SHUTDOWN_CHECKINS.sends(kind, false),
            )
        }
    }

    @Test
    fun everyKindIsCoveredExactlyOnce() {
        val all = KengeleMessage.Kind.values().toSet()
        assertTrue(SmsProfile.ALARM_KINDS.intersect(SmsProfile.SHUTDOWN_KINDS).isEmpty())
        assertEquals(all, SmsProfile.ALARM_KINDS + SmsProfile.SHUTDOWN_KINDS)
    }
}
