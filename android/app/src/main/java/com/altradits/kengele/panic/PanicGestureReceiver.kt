package com.altradits.kengele.panic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.altradits.kengele.MainActivity
import com.altradits.kengele.R

/**
 * Thin receiver for the power-button gesture: `ACTION_SCREEN_ON` and
 * `ACTION_SCREEN_OFF` need no permission, but the OS only delivers them
 * to a dynamically registered receiver, so this class is deliberately
 * NOT declared in the manifest — [PanicGestureService] registers it
 * while the watch window is active. Counting lives in [PanicGesture].
 */
class PanicGestureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_SCREEN_OFF) return
        val app = context.applicationContext
        val fired = synchronized(PanicGestureHolder) {
            PanicGestureDispatch.onToggle(
                PanicGesturePrefs.store(app),
                PanicGestureHolder.detector,
                SystemClock.elapsedRealtime(),
            )
        }
        if (fired) bringUpPanicConfirmation(app)
    }
}

/** Process-wide detector; the receiver is the only writer. */
internal object PanicGestureHolder {
    val detector = PanicGesture()
}

/**
 * Routes a fired gesture to the same guarded confirmation dialog as the
 * Panic button ([MainActivity] `EXTRA_PANIC_CONFIRM`): nothing is ever
 * sent without the activist tapping Yes.
 *
 * Honest limits: on API 29+ the OS usually blocks `startActivity` from
 * the background, so the confirmation may not come to the front while
 * the phone is locked — the high-priority notification is the fallback
 * path (tap it to confirm or cancel). On API 33+ the notification needs
 * the system notification permission, which Kengele does not request;
 * without it `notify()` is a silent no-op and the foreground launch is
 * the only path.
 */
internal fun bringUpPanicConfirmation(context: Context) {
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_PANIC_CONFIRM, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: RuntimeException) {
        // Background launch refused on this release: the notification
        // below is the fallback.
    }
    try {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    context.getString(R.string.panic_gesture_alert_title),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val pending = PendingIntent.getActivity(
            context,
            ALERT_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.panic_gesture_alert_title))
            .setContentText(context.getString(R.string.panic_gesture_alert_text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(ALERT_NOTIF_ID, notification)
    } catch (e: RuntimeException) {
        // Notifications unavailable (or permission off on API 33+): the
        // foreground launch above is the only path.
    }
}

private const val ALERT_CHANNEL_ID = "panic_gesture_alert"
private const val ALERT_NOTIF_ID = 43
private const val ALERT_REQUEST = 44
