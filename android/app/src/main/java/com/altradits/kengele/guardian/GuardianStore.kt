package com.altradits.kengele.guardian

import com.altradits.kengele.message.MessageException
import com.altradits.kengele.message.Secp256k1

/**
 * Guardians, stored as public keys only: no names, no phone numbers, no
 * photos (threat model docs/threat-model.md, sections 4.3 and 8: a seized
 * phone must not name the guardians). There is deliberately no name field
 * anywhere in this API.
 */
interface GuardianStore {
    /** Stored guardians as raw 32-byte x-only public keys. */
    fun guardians(): List<ByteArray>

    /**
     * Adds a guardian by x-only public key hex (64 characters). The key must
     * be on the secp256k1 curve. At most [MAX_GUARDIANS] guardians are kept
     * (design: 3 to 5). Throws IllegalArgumentException otherwise.
     */
    fun add(hexKey: String)

    fun remove(hexKey: String): Boolean

    fun clear()

    companion object {
        const val MAX_GUARDIANS = 5
    }
}

/** In-memory [GuardianStore] for tests; the app uses encrypted storage. */
class InMemoryGuardianStore : GuardianStore {
    private val keys = LinkedHashSet<String>()

    override fun guardians(): List<ByteArray> = keys.map { hexToBytes(it) }

    override fun add(hexKey: String) {
        val norm = checkKey(hexKey)
        require(!keys.contains(norm)) { "guardian is already added" }
        require(keys.size < GuardianStore.MAX_GUARDIANS) {
            "at most ${GuardianStore.MAX_GUARDIANS} guardians"
        }
        keys.add(norm)
    }

    override fun remove(hexKey: String): Boolean = keys.remove(hexKey.lowercase())

    override fun clear() = keys.clear()

    companion object {
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

        /**
         * Validates a guardian key and returns it lowercased. Throws
         * IllegalArgumentException when it is not 64 hex characters or not
         * on the curve.
         */
        fun checkKey(hexKey: String): String {
            require(HEX64.matches(hexKey)) { "guardian key must be 64 hex characters" }
            try {
                Secp256k1.liftX(hexToBytes(hexKey))
            } catch (e: MessageException) {
                throw IllegalArgumentException("guardian key is not on the curve")
            }
            return hexKey.lowercase()
        }

        fun hexToBytes(s: String): ByteArray {
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return out
        }

        fun bytesToHex(b: ByteArray): String {
            val chars = CharArray(b.size * 2)
            val digits = "0123456789abcdef"
            for (i in b.indices) {
                chars[i * 2] = digits[(b[i].toInt() ushr 4) and 0xF]
                chars[i * 2 + 1] = digits[b[i].toInt() and 0xF]
            }
            return String(chars)
        }
    }
}
