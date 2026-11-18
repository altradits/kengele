package com.altradits.kengele.pin

import java.security.MessageDigest
import java.security.SecureRandom

/** Which PIN the activist entered. */
enum class PinResult {
    REAL,
    DURESS,
    WRONG,
}

/**
 * Exactly what the check-in screen shows. The whole object must be identical
 * for the real and the duress PIN: a captor watching the screen, or reading
 * it afterwards, must not be able to tell them apart (threat model
 * docs/threat-model.md, section 4.1: "Both PINs give a normal-looking
 * result; nothing on the phone tells them apart").
 *
 * [messageKey] is a stable key ("checked_in", "wrong_pin") that the Activity
 * maps to the EN/SW string resource, so unit tests can compare full UI state
 * without Android resources.
 */
data class CheckInUiState(val success: Boolean, val messageKey: String)

/**
 * Real PIN plus duress PIN. Only salted, stretched hashes are stored, never
 * the PINs (threat model, section 2: "only hashes on the phone").
 */
interface PinStore {
    fun hasPins(): Boolean

    /**
     * Sets both PINs. Each must be 4-32 characters and they must differ.
     * Throws IllegalArgumentException otherwise.
     */
    fun setPins(realPin: String, duressPin: String)

    fun verify(pin: String): PinResult

    companion object {
        const val MIN_PIN_LEN = 4
        const val MAX_PIN_LEN = 32

        /** The screen for a verify result. REAL and DURESS give the same object. */
        fun uiState(result: PinResult): CheckInUiState = when (result) {
            PinResult.REAL, PinResult.DURESS -> CheckInUiState(true, "checked_in")
            PinResult.WRONG -> CheckInUiState(false, "wrong_pin")
        }
    }
}

/** In-memory [PinStore] for tests and previews; the app uses encrypted storage. */
class InMemoryPinStore : PinStore {
    private var salt: ByteArray? = null
    private var realHash: ByteArray? = null
    private var duressHash: ByteArray? = null

    override fun hasPins(): Boolean = salt != null

    override fun setPins(realPin: String, duressPin: String) {
        checkPin(realPin)
        checkPin(duressPin)
        require(realPin != duressPin) { "real and duress PINs must differ" }
        val newSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        salt = newSalt
        realHash = hashPin(newSalt, realPin)
        duressHash = hashPin(newSalt, duressPin)
    }

    override fun verify(pin: String): PinResult {
        val s = salt ?: return PinResult.WRONG
        val h = hashPin(s, pin)
        return when {
            MessageDigest.isEqual(h, realHash) -> PinResult.REAL
            MessageDigest.isEqual(h, duressHash) -> PinResult.DURESS
            else -> PinResult.WRONG
        }
    }

    companion object {
        /** SHA-256 stretching; cheap on a phone, expensive to brute-force. */
        const val HASH_ROUNDS = 100_000

        fun checkPin(pin: String) {
            require(pin.length in PinStore.MIN_PIN_LEN..PinStore.MAX_PIN_LEN) {
                "PIN must be ${PinStore.MIN_PIN_LEN}-${PinStore.MAX_PIN_LEN} characters"
            }
        }

        fun hashPin(salt: ByteArray, pin: String): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(salt)
            var h = md.digest(pin.toByteArray(Charsets.UTF_8))
            repeat(HASH_ROUNDS - 1) { h = md.digest(h) }
            return h
        }
    }
}
