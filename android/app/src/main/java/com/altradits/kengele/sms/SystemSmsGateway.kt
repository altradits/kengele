package com.altradits.kengele.sms

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SmsGateway] over the real SmsManager (docs/sms-transport.md, section 2):
 * `divideMessage`, then one `sendMultipartTextMessage` per text, with no
 * prefix, no visible part numbers and no delivery reports. Each part
 * reports back through [SmsSentReceiver] into [SmsDeliveryHubs.shared].
 */
class SystemSmsGateway(private val appContext: Context) : SmsGateway {
    override fun divide(text: String): List<String> =
        smsManager().divideMessage(text) ?: listOf(text)

    override fun send(requestId: Long, number: String, parts: List<String>) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            repeat(parts.size) { SmsDeliveryHubs.shared.deliver(requestId, SendResult.NO_PERMISSION) }
            return
        }
        val sentIntents = parts.map { pendingSent(requestId) }
        try {
            smsManager().sendMultipartTextMessage(number, null, ArrayList(parts), ArrayList(sentIntents), null)
        } catch (e: Exception) {
            // Bad number/parts, radio gone mid-call, SecurityException, ...
            repeat(parts.size) { SmsDeliveryHubs.shared.deliver(requestId, SendResult.FAILURE) }
        }
    }

    private fun pendingSent(requestId: Long): PendingIntent {
        val intent = Intent(SmsSentReceiver.ACTION_SMS_SENT)
            .setPackage(appContext.packageName)
            .putExtra(SmsSentReceiver.EXTRA_REQUEST_ID, requestId)
        // Unique request code per part; FLAG_IMMUTABLE is required on Android 12+.
        return PendingIntent.getBroadcast(
            appContext,
            nextRequestCode.getAndIncrement(),
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    companion object {
        private val nextRequestCode = AtomicInteger(1)
    }
}
