package com.altradits.kengele.nostr

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.AppState
import com.altradits.kengele.R
import com.altradits.kengele.Ui
import com.altradits.kengele.message.nostr.RelayUrl
import kotlin.concurrent.thread

/**
 * Relay settings: the Nostr relays check-ins travel over, how many of
 * them must accept a message for a send to count, and a connection test.
 * Stored encrypted with the rest of the activist state.
 */
class NostrSettingsActivity : AppCompatActivity() {
    private val relays = LinkedHashSet<String>()
    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private lateinit var testResult: TextView
    private lateinit var testButton: Button

    private val tester: RelayTester = SocketRelayTester()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        relays.addAll(AppState.nostr.relays())

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.nostr_title), 20f)
        Ui.addText(column, getString(R.string.nostr_note), 14f)

        count = Ui.addText(column, "")
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        column.addView(list)
        val input = Ui.addEdit(column, getString(R.string.nostr_relay_hint), InputType.TYPE_CLASS_TEXT)
        Ui.addButton(column, getString(R.string.add)) {
            val norm = RelayUrl.normalize(input.text.toString())
            if (norm.isEmpty()) {
                Toast.makeText(this, R.string.nostr_invalid_relay, Toast.LENGTH_LONG).show()
            } else {
                relays.add(norm)
                input.setText("")
            }
            refresh()
        }

        val minInput = Ui.addEdit(column, getString(R.string.nostr_min_accepted_hint), InputType.TYPE_CLASS_NUMBER)
        minInput.setText(AppState.nostr.minAccepted().toString())

        testResult = Ui.addText(column, "")
        testButton = Ui.addButton(column, getString(R.string.nostr_test)) { testConnections() }

        Ui.addButton(column, getString(R.string.save)) {
            val k = minInput.text.toString().trim().toIntOrNull()
            if (k == null || k < 1) {
                Toast.makeText(this, R.string.nostr_invalid_min_accepted, Toast.LENGTH_LONG).show()
                return@addButton
            }
            AppState.nostr.setRelays(relays.toList())
            AppState.nostr.setMinAccepted(k)
            Toast.makeText(this, R.string.nostr_saved, Toast.LENGTH_LONG).show()
            finish()
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
        refresh()
    }

    private fun refresh() {
        count.text = getString(R.string.nostr_relays_count, relays.size)
        list.removeAllViews()
        for (url in relays.sorted()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val label = TextView(this)
            label.text = url
            label.textSize = 14f
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(label)
            val remove = Button(this)
            remove.text = getString(R.string.remove)
            remove.setOnClickListener {
                relays.remove(url)
                refresh()
            }
            row.addView(remove)
            list.addView(row)
        }
    }

    /** Probes every on-screen relay off the UI thread and reports n/m. */
    private fun testConnections() {
        val urls = relays.sorted()
        if (urls.isEmpty()) {
            testResult.text = getString(R.string.nostr_test_none)
            return
        }
        testButton.isEnabled = false
        testResult.text = getString(R.string.nostr_testing)
        thread(isDaemon = true, name = "nostr-test") {
            var reachable = 0
            for (url in urls) {
                try {
                    if (tester.reachable(url, 10_000)) reachable++
                } catch (e: Exception) {
                    // A tester bug counts as unreachable, never a crash.
                }
            }
            val done = reachable
            runOnUiThread {
                testResult.text = getString(R.string.nostr_test_result, done, urls.size)
                testButton.isEnabled = true
            }
        }
    }
}
