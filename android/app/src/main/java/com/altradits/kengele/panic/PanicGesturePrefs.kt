package com.altradits.kengele.panic

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences-backed [PanicGestureStore] (plain prefs: not sensitive). */
class PrefsPanicGestureStore(private val prefs: SharedPreferences) : PanicGestureStore {
    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        const val KEY_ENABLED = "enabled"
    }
}

/** Opens the gesture store for a context. */
object PanicGesturePrefs {
    private const val FILE = "panic_gesture"

    fun store(context: Context): PanicGestureStore =
        PrefsPanicGestureStore(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE),
        )
}
