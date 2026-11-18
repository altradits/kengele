package com.altradits.kengele.sms

/**
 * One guardian's SMS destination: the number to text and the opt-in
 * profile (docs/sms-transport.md, section 1).
 */
data class SmsEntry(val number: String, val profile: SmsProfile)

/**
 * Per-guardian SMS numbers and opt-in profiles.
 *
 * [entry] is indexed parallel to the guardians list whose sealed texts
 * [com.altradits.kengele.checkin.CheckInManager] builds: texts[i] belongs
 * to the guardian at index i. Null (or [SmsProfile.OFF]) means no SMS.
 */
interface SmsDirectory {
    fun entry(index: Int): SmsEntry?
}

/** In-memory directory for tests and as backing for the future opt-in UI. */
class InMemorySmsDirectory : SmsDirectory {
    private val entries = mutableMapOf<Int, SmsEntry>()

    fun set(index: Int, number: String, profile: SmsProfile) {
        entries[index] = SmsEntry(number, profile)
    }

    fun remove(index: Int) {
        entries.remove(index)
    }

    override fun entry(index: Int): SmsEntry? = entries[index]
}

/**
 * [SmsDirectory] backed by the per-guardian opt-in [SmsStore].
 *
 * [keys] supplies the current guardians list as lowercase key hex, in the
 * same order the sealed texts were built in: texts[i] belongs to keys[i].
 * Callers must capture that list once per batch — the stored guardians
 * have no stable order across reads, so resolving the list twice could
 * pair a text with the wrong number.
 *
 * Returns null for an unknown index, a guardian with no entry (SMS off,
 * the default), or a stored number that no longer validates: a corrupt
 * record must fail safe to silence, never to a paid SMS to a bad number.
 * An [SmsProfile.OFF] entry is returned as-is; the sender skips it via
 * [SmsProfile.sends], per the interface contract.
 */
class StoreSmsDirectory(
    private val store: SmsStore,
    private val keys: () -> List<String>,
) : SmsDirectory {
    override fun entry(index: Int): SmsEntry? {
        val key = keys().getOrNull(index) ?: return null
        val entry = store.get(key) ?: return null
        if (!SmsNumbers.isValidNumber(entry.number)) return null
        return entry
    }
}
