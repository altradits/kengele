package com.altradits.kengele.mesh

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.altradits.kengele.AppState
import com.altradits.kengele.R

/**
 * Foreground service (`connectedDevice` type) that runs the mesh radio
 * while a watch window is active. A 180-second AlarmManager backstop
 * re-arms scanning after the OS kills the service, and the scan leg
 * follows the [ScanDutyCycle] so Doze still gets sleep.
 */
class MeshService : Service() {
    companion object {
        /** Re-arm backstop: the radio is restarted at most this long after a kill. */
        const val REARM_SECS = 180L
        private const val NOTIF_ID = 41
        private const val CHANNEL_ID = "mesh_carry"
        private const val REARM_REQUEST = 42

        /**
         * Whether the mesh should be running: a watch window is saved and
         * has not ended yet.
         */
        fun windowActive(context: Context): Boolean {
            AppState.init(context.applicationContext)
            val window = AppState.window() ?: return false
            return System.currentTimeMillis() / 1000 < window.endSecs
        }

        /** Starts the radio when a window is active, and arms the backstop. */
        fun startIfWindowActive(context: Context) {
            if (!windowActive(context)) return
            try {
                context.startForegroundService(Intent(context, MeshService::class.java))
            } catch (e: SecurityException) {
                // Background-start refused on this release: the next re-arm
                // or a foreground tap retries.
            } catch (e: IllegalStateException) {
                // Same: not allowed from the background right now.
            }
            scheduleRearm(context)
        }

        /** Stops the radio and cancels the backstop (explicit user stop). */
        fun stop(context: Context) {
            cancelRearm(context)
            context.stopService(Intent(context, MeshService::class.java))
        }

        /** Arms the 180-second re-arm backstop (inexact: needs no exact-alarm permission). */
        fun scheduleRearm(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REARM_REQUEST,
                Intent(context, MeshRearmReceiver::class.java),
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
                Intent(context, MeshRearmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.cancel(pending)
        }
    }

    private var radio: MeshRadio? = null
    private var tickHandler: Handler? = null
    private var startElapsed: Long = 0

    private val tick = object : Runnable {
        override fun run() {
            applyDutyCycle()
            tickHandler?.postDelayed(this, ScanDutyCycle.TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startElapsed = SystemClock.elapsedRealtime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!windowActive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.mesh_service_name))
            .setContentText(getString(R.string.mesh_service_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        try {
            // ServiceCompat ignores the type below API 29; the constant is
            // inlined so referencing it is safe on all supported releases.
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                if (Build.VERSION.SDK_INT >= 29) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
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
        if (radio == null) radio = MeshRadio(this)
        if (radio?.start() != true) {
            // Bluetooth off, missing permission or no LE: nothing to carry.
            MeshBoard.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (tickHandler == null) {
            tickHandler = Handler(Looper.getMainLooper())
            tickHandler?.post(tick)
        }
        scheduleRearm(this)
        return START_STICKY
    }

    override fun onDestroy() {
        tickHandler?.removeCallbacks(tick)
        tickHandler = null
        radio?.stop()
        radio = null
        MeshBoard.running = false
        MeshBoard.advertising = false
        MeshBoard.scanning = false
        MeshBoard.links = 0
        // Re-arm even on destroy: if the OS killed us (not the user), the
        // backstop restarts the radio within 180 seconds while the window
        // is still active. An explicit user stop cancels the alarm first.
        scheduleRearm(this)
        super.onDestroy()
    }

    private fun applyDutyCycle() {
        val radio = radio ?: return
        val power = getSystemService(PowerManager::class.java)
        val dozing = power?.isDeviceIdleMode == true
        val elapsed = SystemClock.elapsedRealtime() - startElapsed
        radio.setScanning(ScanDutyCycle.shouldScan(dozing, elapsed))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mesh_service_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
