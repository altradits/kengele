package com.altradits.kengele.guardian.confirm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** The Kotlin combine port rebuilds every secret the Go Split produced. */
class ShamirTest {
    @Test
    fun combinesGoVectors() {
        for (v in ShamirVectors.all) {
            assertEquals("${v.name}: share count", v.parts, v.shares.size)
            for (s in v.shares) assertEquals("${v.name}: share size", 33, s.size)
            // First, last and strided threshold-sized subsets, plus all shares.
            assertArrayEquals("${v.name}: first-k", v.secret, Shamir.combine(v.shares.take(v.threshold)))
            assertArrayEquals("${v.name}: last-k", v.secret, Shamir.combine(v.shares.takeLast(v.threshold)))
            assertArrayEquals("${v.name}: all", v.secret, Shamir.combine(v.shares))
        }
    }

    @Test
    fun combinesEveryPairOfTwoOfThree() {
        val v = ShamirVectors.named("k2-of-n3")
        for (i in v.shares.indices) {
            for (j in i + 1 until v.shares.size) {
                assertArrayEquals("pair $i,$j", v.secret, Shamir.combine(listOf(v.shares[i], v.shares[j])))
            }
        }
    }

    @Test
    fun rejectsBadParts() {
        val v = ShamirVectors.named("k3-of-n5")
        // Fewer than two parts.
        for (bad in listOf(emptyList(), listOf(v.shares[0]))) {
            try {
                Shamir.combine(bad)
                fail("combine($bad) must fail")
            } catch (e: ShamirException) {
                // Expected.
            }
        }
        // Mismatched lengths.
        try {
            Shamir.combine(listOf(v.shares[0], v.shares[1].copyOf(32)))
            fail("mismatched lengths must fail")
        } catch (e: ShamirException) {
            // Expected.
        }
        // Too short.
        try {
            Shamir.combine(listOf(byteArrayOf(1), byteArrayOf(2)))
            fail("short parts must fail")
        } catch (e: ShamirException) {
            // Expected.
        }
        // Same share twice: duplicate x coordinate.
        try {
            Shamir.combine(listOf(v.shares[0], v.shares[0].copyOf()))
            fail("duplicate parts must fail")
        } catch (e: ShamirException) {
            // Expected.
        }
    }
}
