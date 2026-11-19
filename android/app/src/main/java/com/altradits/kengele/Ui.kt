package com.altradits.kengele

import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Maps stable logic message keys to EN/SW string resources. */
object UiStrings {
    fun res(key: String): Int = when (key) {
        "checked_in" -> R.string.checked_in
        "wrong_pin" -> R.string.wrong_pin
        "panic_sent" -> R.string.panic_sent
        "window_needs_start_end" -> R.string.window_needs_start_end
        "window_end_after_start" -> R.string.window_end_after_start
        "window_interval_positive" -> R.string.window_interval_positive
        "window_grace_reminder_negative" -> R.string.window_grace_reminder_negative
        "window_reminder_shorter" -> R.string.window_reminder_shorter
        else -> R.string.unknown_error
    }
}

/** Tiny programmatic-UI helpers shared by the skeleton activities. */
object Ui {
    fun column(context: Context): LinearLayout {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        layout.setPadding(pad, pad, pad, pad)
        return layout
    }

    fun addText(parent: LinearLayout, text: CharSequence, sizeSp: Float = 16f): TextView {
        val view = TextView(parent.context)
        view.text = text
        view.textSize = sizeSp
        view.layoutParams = params()
        parent.addView(view)
        return view
    }

    fun addEdit(parent: LinearLayout, hint: CharSequence, inputType: Int): EditText {
        val view = EditText(parent.context)
        view.hint = hint
        view.inputType = inputType
        view.layoutParams = params()
        parent.addView(view)
        return view
    }

    fun addButton(parent: LinearLayout, text: CharSequence, onClick: () -> Unit): Button {
        val view = Button(parent.context)
        view.text = text
        view.layoutParams = params()
        view.setOnClickListener { onClick() }
        parent.addView(view)
        return view
    }

    private fun params(): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.bottomMargin = 12
        return params
    }
}
