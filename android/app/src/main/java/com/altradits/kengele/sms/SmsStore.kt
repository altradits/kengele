package com.altradits.kengele.sms

/**
 * Per-guardian SMS fallback opt-in: a phone number plus a profile
 * ([SmsProfile], docs/sms-transport.md, section 1).
 *
 * Entries are keyed by guardian public-key hex (lowercase, 64 characters),
 * not by guardian-list index: the guardians list can be reordered or
 * shortened, and an SMS number must never slide onto the wrong guardian.
 * [StoreSmsDirectory] resolves the current guardians list onto this store.
 *
 * No entry means [SmsProfile.OFF]: SMS stays off by default, because every
 * text tells the phone company that the activist's number texted the
 * guardian's number (threat model docs/threat-model.md, residual risk 7).
 * Setting [SmsProfile.OFF] removes the entry, so an opted-out guardian
 * leaves nothing encrypted at rest either.
 */
interface SmsStore {
    /** The SMS entry for [hexKey], or null when SMS is off for that guardian. */
    fun get(hexKey: String): SmsEntry?

    /**
     * Sets the SMS entry for [hexKey]. [SmsProfile.OFF] removes the entry
     * (the number is ignored); any other profile stores the normalised
     * number. Throws IllegalArgumentException for a bad key or number.
     */
    fun set(hexKey: String, number: String, profile: SmsProfile)

    /** Removes the entry for [hexKey]; false when there was none. */
    fun remove(hexKey: String): Boolean

    fun clear()
}

/**
 * In-memory [SmsStore] for tests; the app uses encrypted storage
 * ([com.altradits.kengele.store.EncryptedSmsStore]).
 */
class InMemorySmsStore : SmsStore {
    private val entries = LinkedHashMap<String, SmsEntry>()

    override fun get(hexKey: String): SmsEntry? {
        val key = SmsNumbers.normKey(hexKey) ?: return null
        return entries[key]
    }

    override fun set(hexKey: String, number: String, profile: SmsProfile) {
        val key = SmsNumbers.normKey(hexKey)
        require(key != null) { "guardian key must be 64 hex characters" }
        if (profile == SmsProfile.OFF) {
            entries.remove(key)
            return
        }
        entries[key] = SmsEntry(SmsNumbers.checkNumber(number), profile)
    }

    override fun remove(hexKey: String): Boolean {
        val key = SmsNumbers.normKey(hexKey) ?: return false
        return entries.remove(key) != null
    }

    override fun clear() = entries.clear()
}

/**
 * Phone-number and key normalisation shared by every [SmsStore] and by
 * [StoreSmsDirectory], so all of it is unit-testable on the JVM.
 *
 * Numbers are stored in a normalised form: whitespace, dashes, dots and
 * brackets stripped, then an optional single leading `+` with 7 to 15
 * digits (ITU-T E.164 allows at most 15). National format is accepted —
 * SmsManager dials it fine on the device — but letters, double pluses and
 * short codes are rejected: an SMS costs money and cannot be unsent.
 */
object SmsNumbers {
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    private val STRIP = Regex("[ \\t\\-().]")
    private val NUMBER = Regex("^\\+?[0-9]{7,15}$")

    /** Lowercase [hexKey], or null when it is not 64 hex characters. */
    fun normKey(hexKey: String): String? {
        val key = hexKey.trim().lowercase()
        return if (HEX64.matches(key)) key else null
    }

    /** Normalises [number], or throws IllegalArgumentException. */
    fun checkNumber(number: String): String {
        val norm = number.trim().replace(STRIP, "")
        require(NUMBER.matches(norm)) {
            "phone number must be 7-15 digits, with an optional leading +"
        }
        return norm
    }

    /** True when [number] normalises cleanly. Never throws. */
    fun isValidNumber(number: String): Boolean = try {
        checkNumber(number)
        true
    } catch (e: IllegalArgumentException) {
        false
    }
}
