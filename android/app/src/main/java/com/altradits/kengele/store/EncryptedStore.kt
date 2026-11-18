package com.altradits.kengele.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.altradits.kengele.guardian.GuardianStore
import com.altradits.kengele.guardian.InMemoryGuardianStore
import com.altradits.kengele.pin.InMemoryPinStore
import com.altradits.kengele.pin.PinResult
import com.altradits.kengele.pin.PinStore
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Encrypted on-device storage. Guardian keys, PIN hashes and the activist's
 * key live in EncryptedSharedPreferences (AES-256); nothing security
 * sensitive is ever stored or logged in plaintext (threat model
 * docs/threat-model.md, sections 2 and 4.3).
 */
object EncryptedPrefs {
    private const val FILE = "kengele_secrets"

    fun open(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

/** [GuardianStore] backed by encrypted preferences. Keys only, never names. */
class EncryptedGuardianStore(private val prefs: SharedPreferences) : GuardianStore {
    override fun guardians(): List<ByteArray> =
        stored().map { InMemoryGuardianStore.hexToBytes(it) }

    override fun add(hexKey: String) {
        val norm = InMemoryGuardianStore.checkKey(hexKey)
        val keys = stored().toMutableSet()
        require(!keys.contains(norm)) { "guardian is already added" }
        require(keys.size < GuardianStore.MAX_GUARDIANS) {
            "at most ${GuardianStore.MAX_GUARDIANS} guardians"
        }
        keys.add(norm)
        prefs.edit().putStringSet(KEYS, keys).apply()
    }

    override fun remove(hexKey: String): Boolean {
        val keys = stored().toMutableSet()
        val removed = keys.remove(hexKey.lowercase())
        if (removed) prefs.edit().putStringSet(KEYS, keys).apply()
        return removed
    }

    override fun clear() {
        prefs.edit().remove(KEYS).apply()
    }

    private fun stored(): Set<String> = prefs.getStringSet(KEYS, emptySet()) ?: emptySet()

    companion object {
        private const val KEYS = "guardian_keys"
    }
}

/** [PinStore] backed by encrypted preferences. Hashes only, never PINs. */
class EncryptedPinStore(private val prefs: SharedPreferences) : PinStore {
    override fun hasPins(): Boolean = prefs.contains(SALT)

    override fun setPins(realPin: String, duressPin: String) {
        InMemoryPinStore.checkPin(realPin)
        InMemoryPinStore.checkPin(duressPin)
        require(realPin != duressPin) { "real and duress PINs must differ" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(SALT, b64(salt))
            .putString(REAL, b64(InMemoryPinStore.hashPin(salt, realPin)))
            .putString(DURESS, b64(InMemoryPinStore.hashPin(salt, duressPin)))
            .apply()
    }

    override fun verify(pin: String): PinResult {
        val salt = unb64(prefs.getString(SALT, null) ?: return PinResult.WRONG)
        val h = InMemoryPinStore.hashPin(salt, pin)
        val real = unb64(prefs.getString(REAL, null) ?: return PinResult.WRONG)
        val duress = unb64(prefs.getString(DURESS, null) ?: return PinResult.WRONG)
        return when {
            MessageDigest.isEqual(h, real) -> PinResult.REAL
            MessageDigest.isEqual(h, duress) -> PinResult.DURESS
            else -> PinResult.WRONG
        }
    }

    companion object {
        private const val SALT = "pin_salt"
        private const val REAL = "pin_real_hash"
        private const val DURESS = "pin_duress_hash"

        private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    }
}
