package com.altradits.kengele.panic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 180-second backstop: after the OS kills [PanicGestureService], this
 * alarm restarts the gesture listener while still armed (watch window
 * active and switch on). It does nothing when disarmed, and an explicit
 * user stop cancels the alarm outright.
 */
class PanicRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        PanicGestureService.startIfArmed(context.applicationContext)
    }
}
