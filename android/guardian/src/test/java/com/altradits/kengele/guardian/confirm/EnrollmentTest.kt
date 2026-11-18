package com.altradits.kengele.guardian.confirm

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Kit enrollment: keys only, distinct guardians, k of at least 2. */
class EnrollmentTest {
    private val activist = TestKeys.testPub("enroll-activist")
    private fun guardian(i: Int) = TestKeys.testPub("enroll-guardian-$i")

    @Test
    fun acceptsValidKeysAndThresholds() {
        val hex = Hex.toHex(guardian(0))
        assertEquals(hex, Enrollment.checkKey(hex))
        assertEquals(hex, Enrollment.checkKey(hex.uppercase()))
        Enrollment.checkEnrollment(activist, listOf(guardian(0), guardian(1)), 2)
        val ten = (0 until 10).map { guardian(it) }
        Enrollment.checkEnrollment(activist, ten, 10)
        Enrollment.checkEnrollment(activist, ten, 2)
    }

    @Test
    fun rejectsBadKeys() {
        val hex = Hex.toHex(guardian(0))
        for (bad in listOf("", "ab12", "zz" + "00".repeat(31), hex + "00", hex.dropLast(2))) {
            try {
                Enrollment.checkKey(bad)
                fail("checkKey($bad) must fail")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
        // 64 hex characters but off the curve.
        try {
            Enrollment.checkKey("ff".repeat(32))
            fail("off-curve key must fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun rejectsBadShares() {
        val share = Hex.toHex(ShamirVectors.named("k2-of-n3").shares[0])
        assertEquals(66, share.length)
        Enrollment.checkShare(share)
        for (bad in listOf("", share.dropLast(2), share + "00", "zz" + share.drop(2))) {
            try {
                Enrollment.checkShare(bad)
                fail("checkShare($bad) must fail")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun rejectsBadKits() {
        val two = listOf(guardian(0), guardian(1))
        // Threshold 1 would let one guardian publish; k above n is impossible.
        for (k in listOf(0, 1, 3)) {
            try {
                Enrollment.checkEnrollment(activist, two, k)
                fail("threshold $k of 2 must fail")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
        // Fewer than 2 or more than 10 guardians.
        try {
            Enrollment.checkEnrollment(activist, listOf(guardian(0)), 2)
            fail("1 guardian must fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        try {
            Enrollment.checkEnrollment(activist, (0 until 11).map { guardian(it) }, 2)
            fail("11 guardians must fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        // Duplicates, the activist as guardian, and the zero key.
        try {
            Enrollment.checkEnrollment(activist, listOf(guardian(0), guardian(0)), 2)
            fail("duplicate guardians must fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        try {
            Enrollment.checkEnrollment(activist, listOf(activist, guardian(0)), 2)
            fail("activist as guardian must fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        try {
            Enrollment.checkEnrollment(activist, listOf(ByteArray(32), guardian(0)), 2)
            fail("zero key must fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }
}
