package com.altradits.kengele.guardian

import com.altradits.kengele.message.Vectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Guardians are keys only: no names anywhere in the API or storage. */
class GuardianStoreTest {
    @Test
    fun addListRemoveKeysOnly() {
        val store = InMemoryGuardianStore()
        val key = Vectors.str("guardian_pub_hex")
        store.add(key)
        assertEquals(listOf(key), store.guardians().map { InMemoryGuardianStore.bytesToHex(it) })
        assertTrue(store.remove(key))
        assertTrue(store.guardians().isEmpty())
        assertFalse(store.remove(key))
    }

    @Test
    fun normalisesCase() {
        val store = InMemoryGuardianStore()
        store.add(Vectors.str("guardian_pub_hex").uppercase())
        assertEquals(
            listOf(Vectors.str("guardian_pub_hex")),
            store.guardians().map { InMemoryGuardianStore.bytesToHex(it) },
        )
    }

    @Test
    fun rejectsBadKeysDuplicatesAndOverflow() {
        val store = InMemoryGuardianStore()
        // Not 64 hex characters.
        for (bad in listOf("", "ab12", "zz" + "00".repeat(31), Vectors.str("guardian_pub_hex") + "00")) {
            try {
                store.add(bad)
                fail("add($bad) must fail")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
        // 64 hex characters but off the curve.
        try {
            store.add("ff".repeat(32))
            fail("off-curve key must fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        // Duplicates.
        store.add(Vectors.str("guardian_pub_hex"))
        try {
            store.add(Vectors.str("guardian_pub_hex"))
            fail("duplicate must fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        // At most 5 (design: 3 to 5 guardians).
        for (i in 2..5) store.add(Vectors.testGuardianHex("guardian-$i"))
        assertEquals(5, store.guardians().size)
        try {
            store.add(Vectors.testGuardianHex("guardian-6"))
            fail("6th guardian must fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        store.clear()
        assertTrue(store.guardians().isEmpty())
    }
}
