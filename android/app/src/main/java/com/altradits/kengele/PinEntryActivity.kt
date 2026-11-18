package com.altradits.kengele

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.checkin.CheckInManager
import com.altradits.kengele.guardian.InMemoryGuardianStore
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.sms.SmsBatchKind
import com.altradits.kengele.sms.SmsNoticeStrings
import com.altradits.kengele.sms.SmsSender
import com.altradits.kengele.sms.StoreSmsDirectory
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
            val batchKind = SmsBatchKind.forCheckIn(pinResult)
            // Encrypted SMS fallback (docs/sms-transport.md): the same
            // store-backed directory and batch-kind gating as the panic
            // path (MainActivity). Off by default until the activist opts
            // each guardian in.
            val guardianKeys = guardians.map { InMemoryGuardianStore.bytesToHex(it) }
            val manager = CheckInManager(
                SmsSender(
                    directory = StoreSmsDirectory(AppState.sms) { guardianKeys },
                    gateway = SystemSmsGateway(applicationContext),
                    scheduler = SystemSmsScheduler(),
                    kindOfBatch = { batchKind },
                    // TODO: shutdown-mode signal; ordinary check-ins stay
                    // Nostr-only until it lands.
                    shutdownMode = { false },
                    onNotice = { notice ->
                        Toast.makeText(this, SmsNoticeStrings.res(notice), Toast.LENGTH_LONG).show()
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
}
