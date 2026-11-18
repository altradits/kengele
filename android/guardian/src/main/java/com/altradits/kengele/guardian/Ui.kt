package com.altradits.kengele.guardian

import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** Tiny programmatic-UI helpers shared by the guardian activities. */
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

    fun addCheckBox(parent: LinearLayout, text: CharSequence): CheckBox {
        val view = CheckBox(parent.context)
        view.text = text
        view.layoutParams = params()
        parent.addView(view)
        return view
    }

    fun addRadioGroup(parent: LinearLayout, options: List<CharSequence>): RadioGroup {
        val group = RadioGroup(parent.context)
        group.orientation = LinearLayout.VERTICAL
        for (o in options) {
            val radio = RadioButton(parent.context)
            radio.text = o
            group.addView(radio)
        }
        group.layoutParams = params()
        parent.addView(group)
        return group
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
