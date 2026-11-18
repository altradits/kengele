package com.altradits.kengele.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** PIN setup/verify, plus the duress UI-equality rule. */
class PinTest {
    @Test
    fun verifyRealDuressWrong() {
        val store = InMemoryPinStore()
        assertFalse(store.hasPins())
        assertEquals(PinResult.WRONG, store.verify("1234"))
        store.setPins("1234", "5678")
        assertTrue(store.hasPins())
        assertEquals(PinResult.REAL, store.verify("1234"))
        assertEquals(PinResult.DURESS, store.verify("5678"))
        assertEquals(PinResult.WRONG, store.verify("0000"))
        assertEquals(PinResult.WRONG, store.verify(""))
    }

    @Test
    fun rejectsShortLongAndEqualPins() {
        val store = InMemoryPinStore()
        for ((real, duress) in listOf(
            "123" to "5678", // too short
            "1234" to "678", // too short
            "1".repeat(33) to "5678", // too long
            "1234" to "1234", // must differ
        )) {
            try {
                store.setPins(real, duress)
                fail("setPins($real, $duress) must fail")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
        assertFalse(store.hasPins())
    }

    @Test
    fun duressShowsIdenticalNormalUi() {
        // The whole UI state object — not just one field — must be equal:
        // nothing on the phone tells the PINs apart (threat model 4.1).
        val real = PinStore.uiState(PinResult.REAL)
        val duress = PinStore.uiState(PinResult.DURESS)
        assertEquals(real, duress)
        assertTrue(real.success)
        assertEquals("checked_in", real.messageKey)
        assertNotEquals(real, PinStore.uiState(PinResult.WRONG))
    }
}
