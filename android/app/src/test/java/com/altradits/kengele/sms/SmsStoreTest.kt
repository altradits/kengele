package com.altradits.kengele.sms

import com.altradits.kengele.message.Vectors
import com.altradits.kengele.store.EncryptedSmsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Per-guardian SMS opt-in store: off by default, roundtrips, number and
 * key validation, and the encrypted record format. All keys are made-up
 * test keys; all numbers are in the fictional 555-01XX range.
 */
class SmsStoreTest {
    private val keyA = Vectors.testGuardianHex("sms-store-a")
    private val keyB = Vectors.testGuardianHex("sms-store-b")

    @Test
    fun offByDefault() {
        val store = InMemorySmsStore()
        assertNull(store.get(keyA))
        assertNull(store.get(keyB))
        assertFalse(store.remove(keyA))
    }

    @Test
    fun roundtripsBothProfiles() {
        val store = InMemorySmsStore()
        store.set(keyA, NUMBER_A, SmsProfile.ALARM_ONLY)
        store.set(keyB, NUMBER_B, SmsProfile.SHUTDOWN_CHECKINS)
        assertEquals(SmsEntry(NUMBER_A, SmsProfile.ALARM_ONLY), store.get(keyA))
        assertEquals(SmsEntry(NUMBER_B, SmsProfile.SHUTDOWN_CHECKINS), store.get(keyB))
    }

    @Test
    fun settingOffRemovesTheEntry() {
        val store = InMemorySmsStore()
        store.set(keyA, NUMBER_A, SmsProfile.ALARM_ONLY)
        // The number is ignored: opting out leaves nothing behind.
        store.set(keyA, "", SmsProfile.OFF)
        assertNull(store.get(keyA))
        // Setting OFF with no entry is a no-op, not an error.
        store.set(keyA, "", SmsProfile.OFF)
        assertNull(store.get(keyA))
    }

    @Test
    fun normalisesKeysAndNumbers() {
        val store = InMemorySmsStore()
        store.set(keyA.uppercase(), "  +1 555-010-0001 ", SmsProfile.ALARM_ONLY)
        assertEquals(SmsEntry("+15550100001", SmsProfile.ALARM_ONLY), store.get(keyA))
        store.set(keyB, "(555) 010 0002", SmsProfile.ALARM_ONLY)
        assertEquals(SmsEntry("5550100002", SmsProfile.ALARM_ONLY), store.get(keyB))
    }

    @Test
    fun rejectsBadNumbersAndKeepsTheOldEntry() {
        val store = InMemorySmsStore()
        store.set(keyA, NUMBER_A, SmsProfile.ALARM_ONLY)
        for (bad in listOf("", "   ", "123456", "1".repeat(16), "abc", "++15550100001", "+-555-01", "555-01XX")) {
            try {
                store.set(keyA, bad, SmsProfile.ALARM_ONLY)
                fail("set($bad) must fail")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
            try {
                store.set(keyB, bad, SmsProfile.SHUTDOWN_CHECKINS)
                fail("set($bad) must fail")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
        // Failed writes change nothing.
        assertEquals(SmsEntry(NUMBER_A, SmsProfile.ALARM_ONLY), store.get(keyA))
        assertNull(store.get(keyB))
    }

    @Test
    fun rejectsBadKeysOnSetButReadsFailSafe() {
        val store = InMemorySmsStore()
        for (bad in listOf("", "ab12", "zz" + "00".repeat(31), keyA + "00")) {
            try {
                store.set(bad, NUMBER_A, SmsProfile.ALARM_ONLY)
                fail("set($bad) must fail")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
            assertNull(store.get(bad))
            assertFalse(store.remove(bad))
        }
    }

    @Test
    fun removeAndClear() {
        val store = InMemorySmsStore()
        store.set(keyA, NUMBER_A, SmsProfile.ALARM_ONLY)
        store.set(keyB, NUMBER_B, SmsProfile.SHUTDOWN_CHECKINS)
        assertTrue(store.remove(keyA))
        assertNull(store.get(keyA))
        assertFalse(store.remove(keyA))
        store.clear()
        assertNull(store.get(keyB))
    }

    @Test
    fun encryptedRecordRoundtrips() {
        val entry = SmsEntry(NUMBER_A, SmsProfile.SHUTDOWN_CHECKINS)
        val record = EncryptedSmsStore.encodeRecord(keyA, entry)
        assertEquals(keyA to entry, EncryptedSmsStore.parseRecord(record))
    }

    @Test
    fun encryptedRecordSkipsCorruptEntries() {
        val good = EncryptedSmsStore.encodeRecord(keyA, SmsEntry(NUMBER_A, SmsProfile.ALARM_ONLY))
        assertEquals(keyA to SmsEntry(NUMBER_A, SmsProfile.ALARM_ONLY), EncryptedSmsStore.parseRecord(good))
        for (bad in listOf(
            "",
            "not-a-record",
            "$keyA:ALARM_ONLY",
            "$keyA:ALARM_ONLY:$NUMBER_A:extra",
            "zz${"00".repeat(31)}:ALARM_ONLY:$NUMBER_A",
            "$keyA:BEAM_ME_UP:$NUMBER_A",
            "$keyA:OFF:$NUMBER_A",
            "$keyA:ALARM_ONLY:bogus!",
            "$keyA:ALARM_ONLY:123",
        )) {
            assertNull("parse($bad) must fail safe", EncryptedSmsStore.parseRecord(bad))
        }
    }

    companion object {
        // Fictional 555-01XX range, never real numbers.
        const val NUMBER_A = "+15550100001"
        const val NUMBER_B = "+15550100002"
    }
}
