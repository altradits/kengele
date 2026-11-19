package com.altradits.kengele.sms

/** Handle to a scheduled retry. */
fun interface SmsTaskHandle {
    fun cancel()
}

/** Android-free seam for retry timing (a Handler on device, manual in tests). */
interface SmsScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): SmsTaskHandle
}
