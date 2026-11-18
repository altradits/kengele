package com.altradits.kengele

import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.checkin.CheckInManager
import com.altradits.kengele.checkin.RecordingSender

/** Skeleton home screen: setup, check-in and the panic stub. */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val sender = RecordingSender()
    private val manager = CheckInManager(sender)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.app_name), 22f)
        status = Ui.addText(column, "")
        Ui.addText(column, getString(R.string.transport_stub), 13f)
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
        // stub sends from the guarded confirmation above (#15 scope).
        val outcome = manager.panic(
            System.currentTimeMillis() / 1000,
            AppState.nextSeq(),
            AppState.activistPriv(),
            guardians,
        )
        Toast.makeText(this, UiStrings.res(outcome.ui.messageKey), Toast.LENGTH_LONG).show()
    }
}
