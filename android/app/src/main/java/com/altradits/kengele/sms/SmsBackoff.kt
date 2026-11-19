package com.altradits.kengele.sms

/** Retry backoff (docs/sms-transport.md, section 2): 30 s, 1 m, 2 m, 5 m, then every 5 m. */
object SmsBackoff {
    private val DELAYS_MILLIS = longArrayOf(30_000, 60_000, 120_000, 300_000)

    /** Delay before the retry after [failureCount] consecutive failures (1-based). */
    fun delayMillis(failureCount: Int): Long =
        DELAYS_MILLIS[minOf(maxOf(failureCount, 1) - 1, DELAYS_MILLIS.size - 1)]
}
