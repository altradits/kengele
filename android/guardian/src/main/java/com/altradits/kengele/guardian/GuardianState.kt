package com.altradits.kengele.guardian

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.altradits.kengele.guardian.confirm.Hex
import com.altradits.kengele.guardian.confirm.SealedKit
import com.altradits.kengele.guardian.confirm.Tally
import com.altradits.kengele.guardian.confirm.TallyStore
import com.altradits.kengele.message.Secp256k1
import com.altradits.kengele.message.nostr.RelayUrl
import java.security.SecureRandom

/**
 * The guardian app's shared state: the enrolled kit (keys only), this
 * phone's guardian key, the sequence counter, the live tally and the
 * published notes. Everything lives in EncryptedSharedPreferences; nothing
 * is ever logged.
 *
 * The tally counts persist across restarts (epochs, votes, clears and the
 * spent/released flags); the sealing key, the alert and the alert key never
 * leave memory. Stored data carries [SCHEMA_VERSION]: anything older runs
 * the migration below, anything newer is ignored rather than crashing.
 */
object GuardianState {
    /** The current prefs schema. Bump when a stored encoding changes. */
    const val SCHEMA_VERSION = 1

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

    fun schemaVersion(): Int = prefs.getInt(SCHEMA_VERSION_KEY, 0)

    fun isEnrolled(): Boolean = prefs.contains(ACTIVIST)

    fun activist(): ByteArray? = prefs.getString(ACTIVIST, null)?.let { Hex.fromHex(it) }

    /**
     * The enrolled guardians in enrolment order: the sealed alert is bound
     * to this exact order. Kits enrolled before ordering existed kept an
     * unordered set; those migrate to sorted order, which opens the kit
     * only when the activist enrolled guardians in sorted order — otherwise
     * the guardian re-enrolls the list in the activist's order.
     */
    fun guardians(): List<ByteArray> = guardianHex().map { Hex.fromHex(it) }

    fun guardianHex(): List<String> {
        prefs.getString(GUARDIANS_ORDERED, null)?.let { joined ->
            val ordered = joined.split(",").filter { it.isNotEmpty() }
            if (ordered.isNotEmpty()) return ordered
        }
        val legacy = (prefs.getStringSet(GUARDIANS, emptySet()) ?: emptySet()).sorted()
        if (legacy.isNotEmpty()) {
            // Lazy upgrade: keep the sorted order from here on.
            prefs.edit().putString(GUARDIANS_ORDERED, legacy.joinToString(",")).apply()
        }
        return legacy
    }

    fun threshold(): Int = prefs.getInt(THRESHOLD, 0)

    fun share(): ByteArray? = prefs.getString(SHARE, null)?.let { Hex.fromHex(it) }

    /** The alert public key journalists verify against, or null when not enrolled. */
    fun alertPub(): ByteArray? = prefs.getString(ALERT_PUB, null)?.let { Hex.fromHex(it) }

    /** The sealed alert ciphertext, or null when not enrolled. */
    fun ciphertext(): ByteArray? = prefs.getString(CIPHERTEXT, null)?.let { Hex.fromHex(it) }

    /**
     * Enrolls a kit. All keys arrive as validated lowercase hex; [ownShareHex]
     * is null when this guardian's share is not enrolled yet, and
     * [alertPubHex]/[ciphertextHex] are null until the sealed alert is
     * enrolled. Re-enrolling clears the tally counts and the published-note
     * record: a new kit starts a new history.
     */
    fun enroll(
        activistHex: String,
        guardianHex: List<String>,
        k: Int,
        ownShareHex: String?,
        alertPubHex: String?,
        ciphertextHex: String?,
    ) {
        val edit = prefs.edit()
            .putString(ACTIVIST, activistHex)
            .putString(GUARDIANS_ORDERED, guardianHex.joinToString(","))
            .putInt(THRESHOLD, k)
            .putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
        edit.remove(GUARDIANS)
        if (ownShareHex == null) edit.remove(SHARE) else edit.putString(SHARE, ownShareHex)
        if (alertPubHex == null) edit.remove(ALERT_PUB) else edit.putString(ALERT_PUB, alertPubHex)
        if (ciphertextHex == null) edit.remove(CIPHERTEXT) else edit.putString(CIPHERTEXT, ciphertextHex)
        edit.remove(TALLY_STATE)
        edit.remove(PUBLISHED_ALERT_EPOCH)
        edit.remove(PUBLISHED_ALERT_ID)
        edit.remove(PUBLISHED_SAFE_EPOCH)
        edit.apply()
        tallyCache?.clearPendingRelease()
        tallyCache = null
    }

    /** The sealed kit for the tally, or null until the sealed alert is enrolled. */
    fun sealedKit(): SealedKit? {
        val alertPubHex = prefs.getString(ALERT_PUB, null) ?: return null
        val ciphertextHex = prefs.getString(CIPHERTEXT, null) ?: return null
        val activistHex = prefs.getString(ACTIVIST, null) ?: return null
        val guardians = guardianHex()
        val k = prefs.getInt(THRESHOLD, 0)
        if (guardians.isEmpty() || k <= 0) return null
        val index = guardians.indexOf(Hex.toHex(ownPub()))
        if (index < 0) return null
        return SealedKit(
            version = 1,
            activistHex = activistHex,
            alertPubHex = alertPubHex,
            guardiansHex = guardians,
            threshold = k,
            index = index,
            ciphertext = Hex.fromHex(ciphertextHex),
        )
    }

    /**
     * The live tally for the enrolled kit, or null when not enrolled. The
     * first build restores the persisted counts; a missing or unreadable
     * snapshot starts fresh.
     */
    fun tally(): Tally? {
        tallyCache?.let { return it }
        if (!isEnrolled()) return null
        val t = Tally(activist()!!, guardians(), ownPub(), threshold(), sealedKit = sealedKit())
        if (schemaVersion() == SCHEMA_VERSION) {
            prefs.getString(TALLY_STATE, null)?.let { text ->
                try {
                    TallyStore.parse(text)?.let { t.restore(it) }
                } catch (e: IllegalArgumentException) {
                    // Corrupt snapshot: start fresh.
                }
            }
        }
        tallyCache = t
        return t
    }

    /** Persists the live tally counts. Call after every handled event. */
    fun saveTally() {
        val t = tallyCache ?: return
        prefs.edit()
            .putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
            .putString(TALLY_STATE, TallyStore.serialize(t.snapshot()))
            .apply()
    }

    /** Next event sequence number. */
    fun nextSeq(): Long {
        val next = prefs.getLong(SEQ, 0) + 1
        prefs.edit().putLong(SEQ, next).apply()
        return next
    }

    fun nowSecs(): Long = System.currentTimeMillis() / 1000

    /** The guardian's Nostr relays, normalized and sorted; empty until configured in Settings. */
    fun relays(): List<String> =
        (prefs.getStringSet(RELAYS, emptySet()) ?: emptySet()).sorted()

    /** Stores [urls] after normalizing; invalid addresses are dropped. */
    fun setRelays(urls: List<String>) {
        prefs.edit().putStringSet(RELAYS, RelayUrl.normalizeAll(urls).toSet()).apply()
    }

    /** How many relays must accept a gift wrap for a send to count as sent. */
    fun minAccepted(): Int = prefs.getInt(MIN_ACCEPTED, 1).coerceAtLeast(1)

    fun setMinAccepted(n: Int) {
        prefs.edit().putInt(MIN_ACCEPTED, n.coerceAtLeast(1)).apply()
    }

    /** Records a published alert note: its alarm epoch and its public note id. */
    fun markAlertPublished(epoch: Long, noteId: String) {
        prefs.edit()
            .putLong(PUBLISHED_ALERT_EPOCH, epoch)
            .putString(PUBLISHED_ALERT_ID, noteId)
            .apply()
    }

    /** The published alert's epoch and note id, or null when unpublished. */
    fun publishedAlert(): Pair<Long, String>? {
        val epoch = prefs.getLong(PUBLISHED_ALERT_EPOCH, -1)
        val id = prefs.getString(PUBLISHED_ALERT_ID, null)
        if (epoch < 0 || id == null) return null
        return Pair(epoch, id)
    }

    /** Records a published found-safe note for its alarm epoch. */
    fun markFoundSafePublished(epoch: Long) {
        prefs.edit().putLong(PUBLISHED_SAFE_EPOCH, epoch).apply()
    }

    /** The alarm epoch whose found-safe note went out, or -1. */
    fun foundSafePublishedEpoch(): Long = prefs.getLong(PUBLISHED_SAFE_EPOCH, -1)

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
    private const val SCHEMA_VERSION_KEY = "schema_version"
    private const val OWN_PRIV = "own_priv"
    private const val ACTIVIST = "activist"
    private const val GUARDIANS = "guardians" // legacy unordered set; see guardians()
    private const val GUARDIANS_ORDERED = "guardians_ordered"
    private const val THRESHOLD = "threshold"
    private const val SHARE = "own_share"
    private const val ALERT_PUB = "alert_pub"
    private const val CIPHERTEXT = "sealed_alert"
    private const val TALLY_STATE = "tally_state"
    private const val SEQ = "seq"
    private const val RELAYS = "relays"
    private const val MIN_ACCEPTED = "min_accepted"
    private const val PUBLISHED_ALERT_EPOCH = "published_alert_epoch"
    private const val PUBLISHED_ALERT_ID = "published_alert_id"
    private const val PUBLISHED_SAFE_EPOCH = "published_safe_epoch"
}
