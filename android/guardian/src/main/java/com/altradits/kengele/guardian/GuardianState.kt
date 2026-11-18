package com.altradits.kengele.guardian

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.altradits.kengele.guardian.confirm.Hex
import com.altradits.kengele.guardian.confirm.Tally
import com.altradits.kengele.message.Secp256k1
import java.security.SecureRandom

/**
 * The guardian app's shared state: the enrolled kit (keys only), this
 * phone's guardian key, the sequence counter and the live tally. Everything
 * lives in EncryptedSharedPreferences; nothing is ever logged.
 *
 * The tally is in memory: counts restart with the app. Persisting handled
 * events across restarts is a follow-up; until then a guardian re-pastes any
 * events they still hold after a restart.
 */
object GuardianState {
    private lateinit var prefs: SharedPreferences

    @Volatile
    private var tallyCache: Tally? = null

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** This phone's guardian signing key, generated once and kept encrypted. */
    fun ownPriv(): ByteArray {
        prefs.getString(OWN_PRIV, null)?.let { return unb64(it) }
        val fresh = newPrivKey()
        prefs.edit().putString(OWN_PRIV, b64(fresh)).apply()
        return fresh
    }

    fun ownPub(): ByteArray = Secp256k1.publicKey(ownPriv())

    fun isEnrolled(): Boolean = prefs.contains(ACTIVIST)

    fun activist(): ByteArray? = prefs.getString(ACTIVIST, null)?.let { Hex.fromHex(it) }

    fun guardians(): List<ByteArray> =
        (prefs.getStringSet(GUARDIANS, emptySet()) ?: emptySet()).sorted().map { Hex.fromHex(it) }

    fun threshold(): Int = prefs.getInt(THRESHOLD, 0)

    fun share(): ByteArray? = prefs.getString(SHARE, null)?.let { Hex.fromHex(it) }

    /**
     * Enrolls a kit. All keys arrive as validated lowercase hex; [ownShareHex]
     * is null when this guardian's share is not enrolled yet.
     */
    fun enroll(activistHex: String, guardianHex: List<String>, k: Int, ownShareHex: String?) {
        val edit = prefs.edit()
            .putString(ACTIVIST, activistHex)
            .putStringSet(GUARDIANS, guardianHex.toSet())
            .putInt(THRESHOLD, k)
        if (ownShareHex == null) edit.remove(SHARE) else edit.putString(SHARE, ownShareHex)
        edit.apply()
        tallyCache = null
    }

    /** The live tally for the enrolled kit, or null when not enrolled. */
    fun tally(): Tally? {
        tallyCache?.let { return it }
        if (!isEnrolled()) return null
        val t = Tally(activist()!!, guardians(), ownPub(), threshold())
        tallyCache = t
        return t
    }

    /** Next event sequence number. */
    fun nextSeq(): Long {
        val next = prefs.getLong(SEQ, 0) + 1
        prefs.edit().putLong(SEQ, next).apply()
        return next
    }

    fun nowSecs(): Long = System.currentTimeMillis() / 1000

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

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private const val FILE = "guardian_secrets"
    private const val OWN_PRIV = "own_priv"
    private const val ACTIVIST = "activist"
    private const val GUARDIANS = "guardians"
    private const val THRESHOLD = "threshold"
    private const val SHARE = "own_share"
    private const val SEQ = "seq"
}
