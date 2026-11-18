package com.altradits.kengele.sms

import org.junit.Assert.assertEquals
import org.junit.Test

/** Retry backoff: 30 s, 1 m, 2 m, 5 m, then every 5 m. */
class SmsBackoffTest {
    @Test
    fun schedule() {
        assertEquals(30_000, SmsBackoff.delayMillis(1))
        assertEquals(60_000, SmsBackoff.delayMillis(2))
        assertEquals(120_000, SmsBackoff.delayMillis(3))
        assertEquals(300_000, SmsBackoff.delayMillis(4))
        assertEquals(300_000, SmsBackoff.delayMillis(5))
        assertEquals(300_000, SmsBackoff.delayMillis(100))
    }

    @Test
    fun clampsNonPositiveCounts() {
        assertEquals(30_000, SmsBackoff.delayMillis(0))
        assertEquals(30_000, SmsBackoff.delayMillis(-3))
    }
}
