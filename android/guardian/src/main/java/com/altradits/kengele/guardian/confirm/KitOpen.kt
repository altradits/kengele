package com.altradits.kengele.guardian.confirm

import com.altradits.kengele.message.MessageException
import com.altradits.kengele.message.Secp256k1
import com.altradits.kengele.message.XChaCha20Poly1305
import com.altradits.kengele.message.nostr.NostrException
import com.altradits.kengele.message.nostr.NostrJson
import java.security.MessageDigest

/** Every failure to validate or open a sealed guardian kit. */
class KitException(message: String) : Exception(message)

/**
 * The activist's pre-written public alert. It deliberately has no
 * coordinates: the place comes only from what the activist typed at their
 * last check-in. Mirrors `internal/confirm.Alert`.
 */
data class Alert(
    val name: String,
    val photo: String = "",
    val lawyer: String = "",
    val text: String = "",
)

/**
 * What one guardian holds for one activist, mirroring
 * `internal/confirm.GuardianKit`: the sealed alert plus everything the seal
 * is bound to. On its own it reveals nothing about the alert. Guardians are
 * in enrolment order; [index] is this guardian's position in the list.
 */
data class SealedKit(
    val version: Int,
    val activistHex: String,
    val alertPubHex: String,
    val guardiansHex: List<String>,
    val threshold: Int,
    val index: Int,
    val ciphertext: ByteArray,
)

/** The opened kit: the alert and the key that signs the public notes. */
data class OpenedKit(
    val alert: Alert,
    /** Wipe with [KitOpen.wipe] after use; never log or store it. */
    val alertKey: ByteArray,
)

/**
 * Kotlin port of `internal/confirm` kit opening (`kit.go`): with the sealing
 * key K rebuilt from k shares, the XChaCha20-Poly1305 seal opens to the
 * alert and the alert key, and the key is checked against the published
 * alert public key. Android-free so plain JUnit covers it.
 *
 * Byte compatibility with the Go side is pinned by the shared vectors in
 * `src/test/resources/kit-open-vectors.json`, sealed by running Go's
 * `NewKits`.
 */
object KitOpen {
    const val KIT_VERSION = 1
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 24

    /**
     * Opens [kit] with the rebuilt sealing key [key]. Throws [KitException]
     * when the kit is invalid, the key is wrong, or the opened alert key
     * does not match the kit's alert public key.
     */
    fun open(kit: SealedKit, key: ByteArray): OpenedKit {
        checkKit(kit)
        if (key.size != KEY_SIZE) throw KitException("sealing key must be 32 bytes")
        val nonce = kit.ciphertext.copyOfRange(0, NONCE_SIZE)
        val body = kit.ciphertext.copyOfRange(NONCE_SIZE, kit.ciphertext.size)
        val plain = try {
            XChaCha20Poly1305.open(key, nonce, body, associatedData(kit))
        } catch (e: MessageException) {
            throw KitException("kit does not open with this key")
        }
        try {
            val sealed = try {
                parseSealed(String(plain, Charsets.UTF_8))
            } catch (e: NostrException) {
                throw KitException("kit does not open with this key")
            }
            val keyBytes = try {
                Hex.fromHex(sealed.second)
            } catch (e: IllegalArgumentException) {
                throw KitException("kit does not open with this key")
            }
            if (keyBytes.size != KEY_SIZE) throw KitException("kit does not open with this key")
            val pubHex = try {
                Hex.toHex(Secp256k1.publicKey(keyBytes))
            } catch (e: MessageException) {
                wipe(keyBytes)
                throw KitException("kit does not open with this key")
            }
            if (pubHex != kit.alertPubHex.lowercase()) {
                wipe(keyBytes)
                throw KitException("alert key does not match the kit's alert key")
            }
            return OpenedKit(sealed.first, keyBytes)
        } finally {
            wipe(plain)
        }
    }

    /**
     * Validates a kit the way Go's `parse` does: version, key shapes,
     * guardian rules, threshold, index and ciphertext length.
     */
    fun checkKit(kit: SealedKit) {
        if (kit.version != KIT_VERSION) throw KitException("unknown kit version ${kit.version}")
        val activist = checkKeyHex(kit.activistHex)
        checkKeyHex(kit.alertPubHex)
        val guardians = kit.guardiansHex.map { checkKeyHex(it) }
        try {
            Enrollment.checkEnrollment(activist, guardians, kit.threshold)
        } catch (e: IllegalArgumentException) {
            throw KitException(e.message ?: "invalid kit")
        }
        if (kit.index < 0 || kit.index >= guardians.size) throw KitException("kit index out of range")
        if (kit.ciphertext.size < NONCE_SIZE + 16) throw KitException("kit ciphertext too short")
    }

    /**
     * Binds the ciphertext to everything else in the kit, exactly like Go's
     * `associatedData`: SHA-256 over `kengele/alert-kit/v1`, the version and
     * threshold as big-endian uint32, then the activist, alert and guardian
     * hex keys.
     */
    fun associatedData(kit: SealedKit): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("kengele/alert-kit/v1".toByteArray(Charsets.UTF_8))
        val b = ByteArray(8)
        putU32(b, 0, kit.version.toLong())
        putU32(b, 4, kit.threshold.toLong())
        md.update(b)
        md.update(kit.activistHex.toByteArray(Charsets.UTF_8))
        md.update(kit.alertPubHex.toByteArray(Charsets.UTF_8))
        for (g in kit.guardiansHex) md.update(g.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    /** Zeroes a secret buffer after use. */
    fun wipe(b: ByteArray) {
        b.fill(0)
    }

    private fun parseSealed(text: String): Pair<Alert, String> {
        val obj = NostrJson.parse(text) as? NostrJson.Obj ?: throw NostrException("sealed kit is not an object")
        val alertObj = obj.opt("alert") as? NostrJson.Obj ?: throw NostrException("sealed kit has no alert")
        fun field(key: String): String = when (val v = alertObj.opt(key)) {
            null -> ""
            is NostrJson.Str -> v.v
            else -> throw NostrException("bad sealed alert field $key")
        }
        val alertKey = obj.opt("alert_key") as? NostrJson.Str ?: throw NostrException("sealed kit has no alert key")
        return Pair(
            Alert(name = field("name"), photo = field("photo"), lawyer = field("lawyer"), text = field("text")),
            alertKey.v,
        )
    }

    private fun checkKeyHex(s: String): ByteArray {
        if (!HEX64.matches(s)) throw KitException("key must be 64 hex characters")
        return Hex.fromHex(s)
    }

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFF).toByte()
        b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
}

/**
 * Reports whether [s] looks like a precise location: a decimal coordinate
 * pair or a map link. Typed place names ("Kisumu law courts") are fine.
 * Mirrors `internal/confirm.LooksLikeLocation`; Kengele never publishes a
 * location trail.
 */
fun looksLikeLocation(s: String): Boolean = COORD_PAIR.containsMatchIn(s) || MAP_LINK.containsMatchIn(s)

private val COORD_PAIR = Regex("""-?\d{1,3}\.\d{3,}\s*[,;/ ]\s*-?\d{1,3}\.\d{3,}""")
private val MAP_LINK = Regex(
    """(geo:|maps\.google|google\.[a-z.]+/maps|goo\.gl/maps|maps\.app\.goo\.gl|openstreetmap\.org|osm\.org/go|what3words|w3w\.co|plus\.codes)""",
    RegexOption.IGNORE_CASE,
)
