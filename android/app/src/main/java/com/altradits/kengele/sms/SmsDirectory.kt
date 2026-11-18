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
 * Placeholder until the per-guardian number/profile store and UI land:
 * SMS stays off for every guardian, per the spec default.
 */
object DisabledSmsDirectory : SmsDirectory {
    override fun entry(index: Int): SmsEntry? = null
}
