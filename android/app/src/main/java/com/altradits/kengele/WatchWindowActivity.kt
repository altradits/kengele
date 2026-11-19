package com.altradits.kengele

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.altradits.kengele.watch.WatchWindow

/** Watch-window setup: start, end and check-in rhythm, kept encrypted. */
class WatchWindowActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.window_title), 20f)
        val startIn = addMinutes(column, getString(R.string.window_start_in), "0")
        val duration = addMinutes(column, getString(R.string.window_duration), "360")
        val interval = addMinutes(column, getString(R.string.window_interval), "90")
        val grace = addMinutes(column, getString(R.string.window_grace), "10")
        val reminder = addMinutes(column, getString(R.string.window_reminder), "5")
        Ui.addButton(column, getString(R.string.save)) {
            val numbers = listOf(startIn, duration, interval, grace, reminder)
                .map { it.text.toString().trim().toLongOrNull() }
            if (numbers.any { it == null }) {
                Toast.makeText(this, R.string.window_needs_start_end, Toast.LENGTH_LONG).show()
                return@addButton
            }
            val (startInMin, durationMin, intervalMin, graceMin, reminderMin) = numbers.map { it!! }
            val now = System.currentTimeMillis() / 1000
            val window = WatchWindow(
                startSecs = now + startInMin * 60,
                endSecs = now + startInMin * 60 + durationMin * 60,
                intervalSecs = intervalMin * 60,
                graceSecs = graceMin * 60,
                reminderSecs = reminderMin * 60,
            )
            val error = window.validate()
            if (error != null) {
                Toast.makeText(this, UiStrings.res(error), Toast.LENGTH_LONG).show()
                return@addButton
            }
            AppState.saveWindow(window)
            // A fresh window arms the mesh radio; the service itself checks
            // permissions and Bluetooth before starting.
            com.altradits.kengele.mesh.MeshService.startIfWindowActive(this)
            // ... and the five-press gesture listener (no-op if switched off).
            com.altradits.kengele.panic.PanicGestureService.startIfArmed(this)
            Toast.makeText(this, R.string.window_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    private fun addMinutes(
        parent: android.widget.LinearLayout,
        label: CharSequence,
        preset: String,
    ): EditText {
        Ui.addText(parent, label, 14f)
        val edit = Ui.addEdit(parent, label, InputType.TYPE_CLASS_NUMBER)
        edit.setText(preset)
        return edit
    }
}
