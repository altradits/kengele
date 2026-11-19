package com.altradits.kengele

import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.checkin.CheckInManager
import com.altradits.kengele.guardian.InMemoryGuardianStore
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

/** Skeleton home screen: setup, check-in and panic. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var transport: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.app_name), 22f)
        status = Ui.addText(column, "")
        transport = Ui.addText(column, "", 13f)
        Ui.addButton(column, getString(R.string.menu_guardians)) {
            startActivity(Intent(this, GuardianSetupActivity::class.java))
        }
        Ui.addButton(column, getString(R.string.menu_window)) {
            startActivity(Intent(this, WatchWindowActivity::class.java))
        }
        Ui.addButton(column, getString(R.string.menu_pins)) {
            startActivity(Intent(this, PinSetupActivity::class.java))
        }
        Ui.addButton(column, getString(R.string.menu_checkin)) {
            startActivity(Intent(this, PinEntryActivity::class.java))
        }
        Ui.addButton(column, getString(R.string.menu_panic)) {
            confirmPanic()
        }
        Ui.addButton(column, getString(R.string.menu_mesh)) {
            startActivity(Intent(this, com.altradits.kengele.mesh.MeshStatusActivity::class.java))
        }
        Ui.addButton(column, getString(R.string.sms_menu)) {
            startActivity(Intent(this, com.altradits.kengele.sms.SmsSettingsActivity::class.java))
        }
        Ui.addButton(column, getString(R.string.nostr_menu)) {
            startActivity(Intent(this, com.altradits.kengele.nostr.NostrSettingsActivity::class.java))
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val window = AppState.window()
        val windowText = if (window == null) {
            getString(R.string.window_none)
        } else {
            getString(R.string.status_window_set, (window.endSecs - window.startSecs) / 60)
        }
        status.text = getString(
            R.string.status_line,
            AppState.guardians.guardians().size,
            windowText,
        )
        val relays = AppState.nostr.relays()
        val base = if (relays.isEmpty()) {
            getString(R.string.nostr_transport_off)
        } else {
            getString(R.string.nostr_transport_on, relays.size, AppState.nostr.minAccepted())
        }
        transport.text = if (ShutdownModes.shared.shutdownMode) {
            base + "\n" + getString(R.string.sms_shutdown_banner)
        } else {
            base
        }
    }

    private fun confirmPanic() {
        AlertDialog.Builder(this)
            .setTitle(R.string.panic_confirm_title)
            .setMessage(R.string.panic_confirm_message)
            .setPositiveButton(R.string.yes) { _, _ -> sendPanic() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun sendPanic() {
        val guardians = AppState.guardians.guardians()
        if (guardians.isEmpty()) {
            Toast.makeText(this, R.string.need_setup, Toast.LENGTH_LONG).show()
            return
        }
        // Deliberate-gesture trigger (5 power presses) still to come; this
        // sends from the guarded confirmation above (#15 scope).
        val guardianKeys = guardians.map { InMemoryGuardianStore.bytesToHex(it) }
        val batchKind = SmsBatchKind.forPanic()
        val directory = StoreSmsDirectory(AppState.sms) { guardianKeys }
        // Panic goes out over BOTH transports: Nostr when relays are
        // configured, plus the encrypted SMS fallback (docs/sms-transport.md).
        // Panic is an alarm kind, so it goes out under both SMS opt-in
        // profiles, with the same batch-kind gating as the check-in path.
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
                    runOnUiThread {
                        val rendered = NostrStatusStrings.render(sendStatus)
                        Toast.makeText(this, getString(rendered.res, *rendered.args), Toast.LENGTH_LONG).show()
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
                    // Panic is an alarm kind so it sends in both modes
                    // either way; the live signal keeps this path honest.
                    shutdownMode = { ShutdownModes.shared.shutdownMode },
                    onNotice = { notice ->
                        Toast.makeText(this, SmsNoticeStrings.res(notice), Toast.LENGTH_LONG).show()
                    },
                ),
            ),
        )
        val outcome = manager.panic(
            System.currentTimeMillis() / 1000,
            AppState.nextSeq(),
            AppState.activistPriv(),
            guardians,
        )
        Toast.makeText(this, UiStrings.res(outcome.ui.messageKey), Toast.LENGTH_LONG).show()
    }
}
