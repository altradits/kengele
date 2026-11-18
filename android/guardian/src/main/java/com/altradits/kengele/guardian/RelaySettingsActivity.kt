package com.altradits.kengele.guardian

import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.message.nostr.RelayUrl

/**
 * Relay settings: the Nostr relays alarms travel over, and how many of them
 * must accept a message for a send to count. Stored encrypted with the rest
 * of the guardian state.
 */
class RelaySettingsActivity : AppCompatActivity() {
    private val relays = LinkedHashSet<String>()
    private lateinit var list: LinearLayout
    private lateinit var count: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardianState.init(this)
        relays.addAll(GuardianState.relays())

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.settings_title), 20f)
        Ui.addText(column, getString(R.string.transport_note), 14f)

        count = Ui.addText(column, "")
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        column.addView(list)
        val input = Ui.addEdit(column, getString(R.string.relay_hint), InputType.TYPE_CLASS_TEXT)
        Ui.addButton(column, getString(R.string.add)) {
            val norm = RelayUrl.normalize(input.text.toString())
            if (norm.isEmpty()) {
                Toast.makeText(this, R.string.invalid_relay, Toast.LENGTH_LONG).show()
            } else {
                relays.add(norm)
                input.setText("")
            }
            refresh()
        }

        val minInput = Ui.addEdit(column, getString(R.string.min_accepted_hint), InputType.TYPE_CLASS_NUMBER)
        minInput.setText(GuardianState.minAccepted().toString())

        Ui.addButton(column, getString(R.string.save)) {
            val k = minInput.text.toString().trim().toIntOrNull()
            if (k == null || k < 1) {
                Toast.makeText(this, R.string.invalid_min_accepted, Toast.LENGTH_LONG).show()
                return@addButton
            }
            GuardianState.setRelays(relays.toList())
            GuardianState.setMinAccepted(k)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_LONG).show()
            finish()
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
        refresh()
    }

    private fun refresh() {
        count.text = getString(R.string.relays_count, relays.size)
        list.removeAllViews()
        for (url in relays.sorted()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val label = TextView(this)
            label.text = url
            label.textSize = 14f
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(label)
            val remove = android.widget.Button(this)
            remove.text = getString(R.string.remove)
            remove.setOnClickListener {
                relays.remove(url)
                refresh()
            }
            row.addView(remove)
            list.addView(row)
        }
    }
}
