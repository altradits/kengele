package com.altradits.kengele.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 180-second backstop: after the OS kills [MeshService], this alarm
 * restarts the radio while the watch window is still active. It does
 * nothing when no window is active, and an explicit user stop cancels
 * the alarm outright.
 */
class MeshRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MeshService.startIfWindowActive(context.applicationContext)
    }
}
