package com.altradits.kengele.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

/**
 * Receives one sent-result per SMS part (manifest-registered, not
 * exported) and forwards it to [SmsDeliveryHubs.shared]. Declared in
 * AndroidManifest.xml next to the SMS permission comment.
 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_SENT) return
        val id = intent.getLongExtra(EXTRA_REQUEST_ID, -1L)
        if (id < 0) return
        SmsDeliveryHubs.shared.deliver(id, mapResult(resultCode))
    }

    companion object {
        const val ACTION_SMS_SENT = "com.altradits.kengele.sms.SMS_SENT"
        const val EXTRA_REQUEST_ID = "request_id"

        /** Android sent-result codes to the seam (spec section 2 table). */
        fun mapResult(code: Int): SendResult = when (code) {
            Activity.RESULT_OK -> SendResult.OK
            SmsManager.RESULT_ERROR_NO_SERVICE -> SendResult.NO_SERVICE
            SmsManager.RESULT_ERROR_RADIO_OFF -> SendResult.RADIO_OFF
            else -> SendResult.FAILURE
        }
    }
}
