package com.altradits.kengele.guardian.confirm

import com.altradits.kengele.message.MessageException
import com.altradits.kengele.message.Secp256k1

/**
 * Kit enrollment rules, mirroring `internal/confirm` (`checkGuardians`):
 * guardians are stored as public keys only — no names, no numbers, no
 * photos — plus the activist's key, this guardian's share and the threshold
 * k. Android-free so plain JUnit covers it.
 */
object Enrollment {
    const val MIN_GUARDIANS = 2
    const val MAX_GUARDIANS = 10
    const val MIN_THRESHOLD = 2 // 1 would let one guardian publish

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    private val HEX66 = Regex("^[0-9a-fA-F]{66}$")

    /**
     * Validates a public key (activist or guardian): 64 hex characters on the
     * secp256k1 curve. Returns it lowercased. Throws IllegalArgumentException.
     */
    fun checkKey(hexKey: String): String {
        require(HEX64.matches(hexKey)) { "key must be 64 hex characters" }
        try {
            Secp256k1.liftX(Hex.fromHex(hexKey))
        } catch (e: MessageException) {
            throw IllegalArgumentException("key is not on the curve")
        }
        return hexKey.lowercase()
    }

    /** Validates this guardian's share: 66 hex characters (33 bytes). */
    fun checkShare(hexShare: String): ByteArray {
        require(HEX66.matches(hexShare)) { "share must be 66 hex characters" }
        return Hex.fromHex(hexShare)
    }

    /** Validates the whole kit: distinct guardians, none the activist, k in range. */
    fun checkEnrollment(activist: ByteArray, guardians: List<ByteArray>, threshold: Int) {
        require(guardians.size in MIN_GUARDIANS..MAX_GUARDIANS) {
            "guardians must be between $MIN_GUARDIANS and $MAX_GUARDIANS"
        }
        require(threshold in MIN_THRESHOLD..guardians.size) {
            "threshold must be at least $MIN_THRESHOLD and at most the number of guardians"
        }
        val seen = mutableSetOf<String>()
        for (g in guardians) {
            require(g.size == 32) { "guardian key must be 32 bytes" }
            val hex = Hex.toHex(g)
            require(g.any { it != 0.toByte() }) { "guardian key must not be zero" }
            require(!g.contentEquals(activist)) { "a guardian must not be the activist" }
            require(seen.add(hex)) { "guardians must be distinct" }
        }
    }
}
