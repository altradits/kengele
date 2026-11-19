package com.altradits.kengele.sms

import android.os.Handler
import android.os.Looper

/** [SmsScheduler] over a Handler: retries live only while the process does. */
class SystemSmsScheduler(looper: Looper = Looper.getMainLooper()) : SmsScheduler {
    private val handler = Handler(looper)

    override fun schedule(delayMillis: Long, task: () -> Unit): SmsTaskHandle {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMillis)
        return SmsTaskHandle { handler.removeCallbacks(runnable) }
    }
}
