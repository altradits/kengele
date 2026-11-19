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
import com.altradits.kengele.nostr.DualCheckInSender
import com.altradits.kengele.nostr.NostrCheckInSender
import com.altradits.kengele.nostr.NostrStatusStrings
import com.altradits.kengele.nostr.ShutdownModes
import com.altradits.kengele.nostr.smsWillSend
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
            val directory = StoreSmsDirectory(AppState.sms) { guardianKeys }
            // Check-ins go out over BOTH transports: Nostr when relays are
            // configured, plus the encrypted SMS fallback where a guardian
            // opted in. Ordinary check-ins outside shutdown stay Nostr-only
            // because SmsSender skips them per profile.
            val relays = AppState.nostr.relays()
            val nostrSender = if (relays.isEmpty()) {
                null
            } else {
                NostrCheckInSender(
                    ownPriv = AppState.activistPriv(),
                    recipients = guardians,
                    relays = relays,
                    minAccepted = AppState.nostr.minAccepted(),
                    onStatus = { sendStatus ->
                        // This screen finishes right after sending, so the
                        // status toast uses the application context.
                        val app = applicationContext
                        runOnUiThread {
                            val rendered = NostrStatusStrings.render(sendStatus)
                            Toast.makeText(app, getString(rendered.res, *rendered.args), Toast.LENGTH_LONG).show()
                        }
                    },
                    reachability = ShutdownModes.shared,
                )
            }
            if (nostrSender == null && !smsWillSend(directory, guardianKeys.size, batchKind, ShutdownModes.shared.shutdownMode)) {
                Toast.makeText(this, R.string.nostr_nothing_configured, Toast.LENGTH_LONG).show()
            }
            val manager = CheckInManager(
                DualCheckInSender(
                    nostr = nostrSender,
                    sms = SmsSender(
                        directory = directory,
                        gateway = SystemSmsGateway(applicationContext),
                        scheduler = SystemSmsScheduler(),
                        kindOfBatch = { batchKind },
                        // Live shutdown signal: ordinary check-ins go by
                        // SMS too while Nostr is unreachable, for
                        // guardians on the Shutdown-check-ins profile.
                        shutdownMode = { ShutdownModes.shared.shutdownMode },
                        onNotice = { notice ->
                            Toast.makeText(this, SmsNoticeStrings.res(notice), Toast.LENGTH_LONG).show()
                        },
                    ),
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
