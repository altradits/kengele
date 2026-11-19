package com.altradits.kengele.guardian

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Guardian home: threshold progress plus the four actions. */
class GuardianMainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var publish: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardianState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.app_name), 20f)
        Ui.addText(column, getString(R.string.transport_note), 14f)
        Ui.addText(column, getString(R.string.no_names_note), 14f)
        status = Ui.addText(column, "")
        progress = Ui.addText(column, "")
        Ui.addButton(column, getString(R.string.menu_enroll)) {
            startActivity(Intent(this, EnrollActivity::class.java))
        }
        Ui.addButton(column, getString(R.string.menu_inbox)) {
            startActivity(Intent(this, InboxActivity::class.java))
        }
        Ui.addButton(column, getString(R.string.menu_confirm)) {
            startActivity(Intent(this, ConfirmActivity::class.java))
        }
        Ui.addButton(column, getString(R.string.menu_all_clear)) {
            startActivity(Intent(this, AllClearActivity::class.java))
        }
        publish = Ui.addButton(column, getString(R.string.menu_publish)) {
            startActivity(Intent(this, ReleaseActivity::class.java))
        }
        Ui.addButton(column, getString(R.string.menu_settings)) {
            startActivity(Intent(this, RelaySettingsActivity::class.java))
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val t = GuardianState.tally()
        if (t == null) {
            status.text = getString(R.string.status_not_enrolled)
            progress.text = ""
            publish.visibility = View.GONE
            return
        }
        publish.visibility = if (t.hasPendingRelease()) View.VISIBLE else View.GONE
        val now = GuardianState.nowSecs()
        val c = t.confirmStatus(now)
        val s = t.clearStatus(now)
        status.text = getString(R.string.status_line, c.epoch, c.confirms, c.needed, s.guardianClears)
        progress.text = when {
            c.released -> getString(R.string.status_released, c.confirms)
            s.activistSaysSafe -> getString(R.string.status_activist_says_safe)
            else -> ""
        }
    }
}
