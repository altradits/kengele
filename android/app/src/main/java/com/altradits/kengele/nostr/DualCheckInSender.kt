package com.altradits.kengele.nostr

import com.altradits.kengele.R
import com.altradits.kengele.checkin.CheckInSender
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.sms.SmsDirectory

/**
 * Sends one sealed batch over both transports: Nostr when relays are
 * configured, SMS per the existing opt-in profiles. Either side may be
 * null (Nostr with no relays, SMS never): a null side is skipped, so the
 * four routing cases — Nostr-only, SMS-only, both, neither — fall out of
 * what the activity passes in. Ordinary check-ins outside shutdown stay
 * Nostr-only because [com.altradits.kengele.sms.SmsSender] already skips
 * them per profile; this fan-out adds no gating of its own.
 */
class DualCheckInSender(
    private val nostr: CheckInSender? = null,
    private val sms: CheckInSender? = null,
) : CheckInSender {
    override fun send(texts: List<String>) {
        nostr?.send(texts)
        sms?.send(texts)
    }
}

/**
 * Whether any of the first [count] SMS directory entries sends [kind] now:
 * the same [com.altradits.kengele.sms.SmsProfile.sends] gate
 * [com.altradits.kengele.sms.SmsSender] applies, so activities can tell
 * "nothing will go out" before sending.
 */
fun smsWillSend(directory: SmsDirectory, count: Int, kind: KengeleMessage.Kind, shutdownMode: Boolean): Boolean {
    for (i in 0 until count) {
        val entry = directory.entry(i) ?: continue
        if (entry.profile.sends(kind, shutdownMode)) return true
    }
    return false
}

/** Maps a [NostrSendStatus] to the EN/SW string the activist sees. */
object NostrStatusStrings {
    /** A string resource plus its format arguments. */
    data class Render(val res: Int, val args: Array<Any>)

    fun render(status: NostrSendStatus): Render = when {
        status.total == 0 && status.dropped > 0 ->
            Render(R.string.nostr_invalid_dropped, emptyArray())
        status.queued == 0 ->
            Render(R.string.nostr_sent, arrayOf<Any>(status.accepted, status.relays))
        status.sent == 0 ->
            Render(R.string.nostr_all_queued, emptyArray())
        else ->
            Render(R.string.nostr_partial_queued, arrayOf<Any>(status.sent, status.total, status.queued))
    }
}
