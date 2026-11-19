package com.altradits.kengele

import android.os.Bundle
import android.text.InputType
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Sets the everyday PIN and the duress PIN (hashes only are stored). */
class PinSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val column = Ui.column(this)
        Ui.addText(column, getString(R.string.pins_title), 20f)
        Ui.addText(column, getString(R.string.duress_explainer), 14f)
        val real = Ui.addEdit(
            column,
            getString(R.string.real_pin),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        val duress = Ui.addEdit(
            column,
            getString(R.string.duress_pin),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        Ui.addButton(column, getString(R.string.save)) {
            try {
                AppState.pins.setPins(real.text.toString(), duress.text.toString())
            } catch (e: IllegalArgumentException) {
                Toast.makeText(this, R.string.pins_invalid, Toast.LENGTH_LONG).show()
                return@addButton
            }
            Toast.makeText(this, R.string.pins_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }
}
