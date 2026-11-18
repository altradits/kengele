package com.altradits.kengele

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.altradits.kengele.guardian.GuardianStore
import com.altradits.kengele.message.Secp256k1
import com.altradits.kengele.pin.PinStore
import com.altradits.kengele.sms.SmsStore
import com.altradits.kengele.store.EncryptedGuardianStore
import com.altradits.kengele.store.EncryptedPinStore
import com.altradits.kengele.store.EncryptedPrefs
import com.altradits.kengele.store.EncryptedSmsStore
import com.altradits.kengele.watch.WatchWindow
import java.security.SecureRandom

/**
 * The skeleton's shared state: encrypted stores, the activist's key, the
 * sequence counter and the watch window. Everything sensitive lives in
 * EncryptedSharedPreferences; nothing is ever logged.
 */
object AppState {
    private lateinit var prefs: SharedPreferences
    lateinit var guardians: GuardianStore
        private set
    lateinit var pins: PinStore
        private set
    lateinit var sms: SmsStore
        private set

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = EncryptedPrefs.open(context.applicationContext)
        guardians = EncryptedGuardianStore(prefs)
        pins = EncryptedPinStore(prefs)
        sms = EncryptedSmsStore(prefs)
    }

    /** The activist's signing key, generated once and kept encrypted. */
    fun activistPriv(): ByteArray = activistPriv(PrefsKeys(prefs), ::newPrivKey)

    /**
     * Reinstall rule, Android-free so unit tests cover it: a stored key
     * without its seq counter (key restored without prefs) is dropped and
     * replaced with a fresh key, so the activist re-enrolls and the old
     * watch fails safe to alarm; every fresh key resets the seq counter so
     * guardians never mistake a restarted seq for replays (threat model
     * replay row).
     */
    internal fun activistPriv(store: KeySeqStore, generate: () -> ByteArray): ByteArray {
        store.loadPriv()?.let { if (store.hasSeq()) return it else store.clearPriv() }
        val fresh = generate()
        store.storePriv(fresh)
        store.resetSeq()
        return fresh
    }

    /** Minimal key/counter store behind the reinstall rule. */
    internal interface KeySeqStore {
        fun loadPriv(): ByteArray?
        fun storePriv(fresh: ByteArray)
        fun clearPriv()
        fun hasSeq(): Boolean
        fun resetSeq()
    }

    private fun newPrivKey(): ByteArray {
        val random = SecureRandom()
        while (true) {
            val cand = ByteArray(32).also(random::nextBytes)
            try {
                Secp256k1.publicKey(cand)
            } catch (e: Exception) {
                continue
            }
            return cand
        }
    }

    private class PrefsKeys(private val prefs: SharedPreferences) : KeySeqStore {
        override fun loadPriv(): ByteArray? =
            prefs.getString(PRIV, null)?.let { AppState.unb64(it) }

        override fun storePriv(fresh: ByteArray) {
            prefs.edit().putString(PRIV, AppState.b64(fresh)).apply()
        }

        override fun clearPriv() {
            prefs.edit().remove(PRIV).apply()
        }

        override fun hasSeq(): Boolean = prefs.contains(SEQ)

        override fun resetSeq() {
            prefs.edit().remove(SEQ).apply()
        }
    }

    /** Next event sequence number. Receivers drop replays by (signer, seq). */
    fun nextSeq(): Long {
        val next = prefs.getLong(SEQ, 0) + 1
        prefs.edit().putLong(SEQ, next).apply()
        return next
    }

    fun saveWindow(window: WatchWindow) {
        prefs.edit()
            .putLong(W_START, window.startSecs)
            .putLong(W_END, window.endSecs)
            .putLong(W_INTERVAL, window.intervalSecs)
            .putLong(W_GRACE, window.graceSecs)
            .putLong(W_REMINDER, window.reminderSecs)
            .apply()
    }

    fun window(): WatchWindow? {
        if (!prefs.contains(W_START)) return null
        return WatchWindow(
            prefs.getLong(W_START, 0),
            prefs.getLong(W_END, 0),
            prefs.getLong(W_INTERVAL, 0),
            prefs.getLong(W_GRACE, 0),
            prefs.getLong(W_REMINDER, 0),
        )
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private const val PRIV = "activist_priv"
    private const val SEQ = "seq"
    private const val W_START = "window_start"
    private const val W_END = "window_end"
    private const val W_INTERVAL = "window_interval"
    private const val W_GRACE = "window_grace"
    private const val W_REMINDER = "window_reminder"
}
