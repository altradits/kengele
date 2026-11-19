package com.altradits.kengele.mesh

/**
 * Doze-aware scan duty cycle: scan bursts, then sleep, so the phone lasts a
 * protest. Pure and Android-free so unit tests pin the windows; the service
 * feeds it PowerManager.isDeviceIdleMode and its own start time.
 */
object ScanDutyCycle {
    /** Active scan window while the phone is awake. */
    const val NORMAL_SCAN_MS = 30_000L

    /** Rest window while the phone is awake. */
    const val NORMAL_REST_MS = 30_000L

    /** Active scan window while the phone dozes. */
    const val DOZE_SCAN_MS = 30_000L

    /** Rest window while the phone dozes: a 5-minute cycle. */
    const val DOZE_REST_MS = 270_000L

    /** How often the service re-evaluates the cycle. */
    const val TICK_MS = 5_000L

    /** Whether the radio should be scanning [elapsedMs] after service start. */
    fun shouldScan(dozing: Boolean, elapsedMs: Long): Boolean {
        val scan = if (dozing) DOZE_SCAN_MS else NORMAL_SCAN_MS
        val rest = if (dozing) DOZE_REST_MS else NORMAL_REST_MS
        if (elapsedMs < 0) return true
        return (elapsedMs % (scan + rest)) < scan
    }
}
