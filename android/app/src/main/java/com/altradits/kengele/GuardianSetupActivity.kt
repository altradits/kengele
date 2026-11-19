package com.altradits.kengele

import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.guardian.InMemoryGuardianStore

/** Guardian setup: public keys only, never names or numbers. */
class GuardianSetupActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var count: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.guardians_title), 20f)
        Ui.addText(column, getString(R.string.no_names_note), 14f)
        count = Ui.addText(column, "")
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        column.addView(list)
        val input = Ui.addEdit(
            column,
            getString(R.string.guardian_key_hint),
            InputType.TYPE_CLASS_TEXT,
        )
        Ui.addButton(column, getString(R.string.add)) {
            val key = input.text.toString().trim().lowercase()
            try {
                AppState.guardians.add(key)
                input.setText("")
            } catch (e: IllegalArgumentException) {
                Toast.makeText(this, R.string.invalid_key, Toast.LENGTH_LONG).show()
            }
            refresh()
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
        refresh()
    }

    private fun refresh() {
        val keys = AppState.guardians.guardians().map { InMemoryGuardianStore.bytesToHex(it) }
        count.text = getString(R.string.guardians_count, keys.size)
        list.removeAllViews()
        for (key in keys) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val label = TextView(this)
            // Show the ends; the full key stays in encrypted storage.
            label.text = key.take(12) + "…" + key.takeLast(8)
            label.textSize = 14f
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(label)
            val remove = android.widget.Button(this)
            remove.text = getString(R.string.remove)
            remove.setOnClickListener {
                AppState.guardians.remove(key)
                // The number must not linger for a guardian who is gone.
                AppState.sms.remove(key)
                refresh()
            }
            row.addView(remove)
            list.addView(row)
        }
    }
}
