package com.altradits.kengele

import android.os.Bundle
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

/**
 * The check-in result screen. It renders only the (success, message key)
 * pair, which is identical for the real and the duress PIN — the Activity
 * never learns which PIN was entered.
 */
class CheckInResultActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val success = intent.getBooleanExtra(EXTRA_SUCCESS, false)
        val key = intent.getStringExtra(EXTRA_MESSAGE_KEY) ?: "wrong_pin"

        val column = Ui.column(this)
        Ui.addText(column, getString(if (success) R.string.result_ok else R.string.result_try_again), 20f)
        Ui.addText(column, getString(UiStrings.res(key)), 18f)
        Ui.addButton(column, getString(R.string.done)) { finish() }
        val scroll = ScrollView(this)
        scroll.addView(column)
        setContentView(scroll)
    }

    companion object {
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE_KEY = "message_key"
    }
}
