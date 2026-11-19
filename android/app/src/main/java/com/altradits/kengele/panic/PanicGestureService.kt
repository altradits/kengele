package com.altradits.kengele.panic

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.altradits.kengele.AppState
import com.altradits.kengele.R

/**
 * Foreground service (`specialUse` type) that keeps the five-press
 * gesture receiver registered while the gesture is armed: a watch
 * window is saved and unexpired AND the activist left the gesture on.
 * Same lifetime pattern as the mesh radio ([com.altradits.kengele.mesh.MeshService]):
 * explicit starts from [com.altradits.kengele.MainActivity] and
 * [com.altradits.kengele.WatchWindowActivity], a 180-second
 * AlarmManager backstop ([PanicRearmReceiver]) after the OS kills the
 * service, and `START_STICKY` for the restart.
 *
 * Honest battery/Doze limits (threat model section 6: state plainly
 * what Kengele cannot do):
 *
 * - The receiver is event-driven (screen toggles only), so the steady
 *   cost is one low-priority notification and a kept-alive process, not
 *   polling. It still counts against battery like any foreground
 *   service, and only runs inside the watch window for that reason.
 * - After an OS kill the re-arm alarm is inexact
 *   (`setAndAllowWhileIdle`, no exact-alarm permission): normally
 *   within [REARM_SECS], but deep Doze batches alarms, so the gesture
 *   can be deaf for minutes until the maintenance window.
 * - A reboot arms nothing until the activist opens Kengele (no boot
 *   receiver, by design: fewer permissions, fewer surprises).
 * - Aggressive OEM task killers may stop the service and its alarm
 *   together; the settings screen then reports "not listening" and the
 *   panic button remains the fallback.
 */
class PanicGestureService : Service() {
    companion object {
        /** Re-arm backstop: the listener restarts at most this long after a kill. */
        const val REARM_SECS = 180L
        private const val NOTIF_ID = 45
        private const val CHANNEL_ID = "panic_gesture"
        private const val REARM_REQUEST = 46

        /**
         * Whether the gesture should be listening: an unexpired watch
         * window is saved and the gesture switch is on.
         */
        fun armed(context: Context): Boolean {
            if (!PanicGesturePrefs.store(context.applicationContext).isEnabled()) return false
            AppState.init(context.applicationContext)
            val window = AppState.window() ?: return false
            return System.currentTimeMillis() / 1000 < window.endSecs
        }

        /** Starts listening when armed, and arms the re-arm backstop. */
        fun startIfArmed(context: Context) {
            if (!armed(context)) return
            try {
                context.startForegroundService(Intent(context, PanicGestureService::class.java))
            } catch (e: SecurityException) {
                // Background-start refused on this release: the next
                // re-arm or a foreground tap retries.
            } catch (e: IllegalStateException) {
                // Same: not allowed from the background right now.
            }
            scheduleRearm(context)
        }

        /** Stops listening and cancels the backstop (explicit user stop). */
        fun stop(context: Context) {
            cancelRearm(context)
            context.stopService(Intent(context, PanicGestureService::class.java))
        }

        /** Arms the 180-second re-arm backstop (inexact: needs no exact-alarm permission). */
        fun scheduleRearm(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REARM_REQUEST,
                Intent(context, PanicRearmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                alarm.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + REARM_SECS * 1000,
                    pending,
                )
            } catch (e: SecurityException) {
                // No alarm: the service still runs until the OS kills it.
            }
        }

        private fun cancelRearm(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REARM_REQUEST,
                Intent(context, PanicRearmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.cancel(pending)
        }
    }

    private var receiver: PanicGestureReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!armed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.panic_gesture_service_name))
            .setContentText(getString(R.string.panic_gesture_service_text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                if (Build.VERSION.SDK_INT >= 34) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (e: SecurityException) {
            // Foreground start refused (background restrictions, missing
            // permission): fail silent; the backstop retries.
            stopSelf()
            return START_NOT_STICKY
        }
        if (receiver == null) {
            val registered = PanicGestureReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(registered, filter)
            receiver = registered
        }
        scheduleRearm(this)
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Already unregistered; nothing to do.
            }
            receiver = null
        }
        // Re-arm even on destroy: if the OS killed us (not the user), the
        // backstop restarts the listener within 180 seconds while still
        // armed. An explicit user stop cancels the alarm first, and the
        // re-arm chain ends itself once disarmed.
        scheduleRearm(this)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.panic_gesture_service_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
