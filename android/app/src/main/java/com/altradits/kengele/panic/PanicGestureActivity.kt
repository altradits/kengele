package com.altradits.kengele.panic

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.AppState
import com.altradits.kengele.R
import com.altradits.kengele.Ui

/**
 * Panic-gesture settings (threat model docs/threat-model.md, section 7).
 *
 * The gesture is ON by default with the explanation below on this
 * screen; the activist can turn it off. It listens only while a watch
 * window is active, and firing it brings up the same confirmation
 * dialog as the Panic button — nothing is ever sent without a tap.
 */
class PanicGestureActivity : AppCompatActivity() {
    private lateinit var statusLine: TextView
    private lateinit var armedLine: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.panic_gesture_title), 20f)
        Ui.addText(column, getString(R.string.panic_gesture_explainer), 14f)
        statusLine = Ui.addText(column, "", 14f)
        armedLine = Ui.addText(column, "", 14f)
        toggle = Ui.addButton(column, "") { toggle() }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun toggle() {
        val store = PanicGesturePrefs.store(this)
        val nowOn = !store.isEnabled()
        store.setEnabled(nowOn)
        if (nowOn) {
            PanicGestureService.startIfArmed(this)
        } else {
            PanicGestureService.stop(this)
        }
        refresh()
    }

    private fun refresh() {
        val on = PanicGesturePrefs.store(this).isEnabled()
        statusLine.text = getString(
            if (on) R.string.panic_gesture_status_on else R.string.panic_gesture_status_off,
        )
        toggle.text = getString(
            if (on) R.string.panic_gesture_turn_off else R.string.panic_gesture_turn_on,
        )
        armedLine.text = getString(
            if (PanicGestureService.armed(this)) {
                R.string.panic_gesture_armed
            } else if (!on) {
                R.string.panic_gesture_idle_off
            } else {
                R.string.panic_gesture_idle_no_window
            },
        )
    }
}
