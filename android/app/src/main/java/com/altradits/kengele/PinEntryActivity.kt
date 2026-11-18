package com.altradits.kengele

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.checkin.CheckInManager
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.pin.PinResult
import com.altradits.kengele.sms.DisabledSmsDirectory
import com.altradits.kengele.sms.SmsNotice
import com.altradits.kengele.sms.SmsSender
import com.altradits.kengele.sms.SystemSmsGateway
import com.altradits.kengele.sms.SystemSmsScheduler

/** PIN entry + optional typed place, then the identical result screen. */
class PinEntryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.checkin_title), 20f)
        val pin = Ui.addEdit(
            column,
            getString(R.string.enter_pin),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        val place = Ui.addEdit(
            column,
            getString(R.string.place_hint),
            InputType.TYPE_CLASS_TEXT,
        )
        Ui.addButton(column, getString(R.string.checkin_button)) {
            val guardians = AppState.guardians.guardians()
            if (guardians.isEmpty() || !AppState.pins.hasPins()) {
                Toast.makeText(this, R.string.need_setup, Toast.LENGTH_LONG).show()
                return@addButton
            }
            val placeBytes = place.text.toString().trim().toByteArray(Charsets.UTF_8)
            if (placeBytes.size > KengeleMessage.MAX_BODY_SIZE) {
                Toast.makeText(this, R.string.place_too_long, Toast.LENGTH_LONG).show()
                return@addButton
            }
            // Same call for both PINs; the result screen cannot tell them apart.
            val pinResult = AppState.pins.verify(pin.text.toString())
            val batchKind = when (pinResult) {
                PinResult.REAL -> KengeleMessage.Kind.CHECK_IN
                PinResult.DURESS -> KengeleMessage.Kind.DURESS_CHECK_IN
                // Unused: CheckInManager sends nothing for a wrong PIN.
                PinResult.WRONG -> KengeleMessage.Kind.CHECK_IN
            }
            // Encrypted SMS fallback (docs/sms-transport.md). Only this
            // check-in path is wired; panic (MainActivity) stays stubbed
            // until its wiring lands.
            val manager = CheckInManager(
                SmsSender(
                    // TODO: per-guardian number/profile store + opt-in UI;
                    // SMS stays off for everyone until then (spec default).
                    directory = DisabledSmsDirectory,
                    gateway = SystemSmsGateway(applicationContext),
                    scheduler = SystemSmsScheduler(),
                    kindOfBatch = { batchKind },
                    // TODO: shutdown-mode signal; ordinary check-ins stay
                    // Nostr-only until it lands.
                    shutdownMode = { false },
                    onNotice = { notice ->
                        Toast.makeText(this, smsNoticeText(notice), Toast.LENGTH_LONG).show()
                    },
                ),
            )
            val outcome = manager.checkIn(
                pinResult,
                System.currentTimeMillis() / 1000,
                AppState.nextSeq(),
                placeBytes,
                AppState.activistPriv(),
                guardians,
            )
            val intent = Intent(this, CheckInResultActivity::class.java)
            intent.putExtra(CheckInResultActivity.EXTRA_SUCCESS, outcome.ui.success)
            intent.putExtra(CheckInResultActivity.EXTRA_MESSAGE_KEY, outcome.ui.messageKey)
            startActivity(intent)
            finish()
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    private fun smsNoticeText(notice: SmsNotice): String = when (notice) {
        SmsNotice.SEND_FAILED_QUEUED -> getString(R.string.sms_failed_queued)
        SmsNotice.NO_PERMISSION -> getString(R.string.sms_no_permission)
        SmsNotice.INVALID_TEXT_DROPPED -> getString(R.string.sms_invalid_dropped)
    }
}
