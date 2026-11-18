package com.altradits.kengele.sms

import com.altradits.kengele.checkin.CheckInSender
import com.altradits.kengele.message.KengeleMessage

/** SMS events the activist must see; the activity maps these to strings. */
enum class SmsNotice {
    /** A send failed and is queued for retry (spec: show the activist). */
    SEND_FAILED_QUEUED,

    /** SEND_SMS is not granted; queued, will retry once granted. */
    NO_PERMISSION,

    /** A malformed text was dropped instead of sending a paid SMS. */
    INVALID_TEXT_DROPPED,
}

/**
 * Encrypted SMS fallback sender (docs/sms-transport.md, section 2).
 *
 * Each sealed 304-character text goes out as one two-segment concatenated
 * SMS to its guardian's number only where that guardian opted in
 * ([SmsDirectory], default off). Failed sends stay in an in-memory queue
 * (nothing to find on a seized phone) and retry with [SmsBackoff].
 * Resends reuse the exact same text, so a retry looks like a repeat.
 *
 * All Android calls sit behind [SmsGateway] and [SmsScheduler], so
 * JUnit-only tests cover the gating, queueing and backoff logic.
 */
class SmsSender(
    private val directory: SmsDirectory,
    private val gateway: SmsGateway,
    private val scheduler: SmsScheduler,
    private val kindOfBatch: () -> KengeleMessage.Kind,
    private val shutdownMode: () -> Boolean = { false },
    private val hub: SmsDeliveryHub = SmsDeliveryHubs.shared,
    private val onNotice: (SmsNotice) -> Unit = {},
) : CheckInSender {

    private class Queued(val text: String, val number: String, var failures: Int = 0)

    private val queue = LinkedHashMap<Long, Queued>()
    private var nextId = 1L

    override fun send(texts: List<String>) {
        if (texts.isEmpty()) return
        val kind = kindOfBatch()
        val shutdown = shutdownMode()
        for ((index, text) in texts.withIndex()) {
            val entry = directory.entry(index) ?: continue
            if (!entry.profile.sends(kind, shutdown)) continue
            if (!isSendableText(text)) {
                onNotice(SmsNotice.INVALID_TEXT_DROPPED)
                continue
            }
            val id = nextId++
            queue[id] = Queued(text, entry.number)
            attempt(id)
        }
    }

    /** Sends still held for retry (tests and diagnostics). */
    fun queuedCount(): Int = queue.size

    private fun attempt(id: Long) {
        val queued = queue[id] ?: return
        val parts = gateway.divide(queued.text)
        if (parts.isEmpty()) {
            queue.remove(id)
            onNotice(SmsNotice.INVALID_TEXT_DROPPED)
            return
        }
        hub.register(id, parts.size) { result -> onComplete(id, result) }
        gateway.send(id, queued.number, parts)
    }

    private fun onComplete(id: Long, result: SendResult) {
        val queued = queue[id] ?: return
        if (result == SendResult.OK) {
            queue.remove(id)
            return
        }
        queued.failures++
        when (result) {
            SendResult.FAILURE -> onNotice(SmsNotice.SEND_FAILED_QUEUED)
            SendResult.NO_PERMISSION -> onNotice(SmsNotice.NO_PERMISSION)
            // NO_SERVICE and RADIO_OFF retry quietly; RADIO_OFF additionally
            // wants a retry-when-service-returns trigger, still to come.
            else -> Unit
        }
        scheduler.schedule(SmsBackoff.delayMillis(queued.failures)) { attempt(id) }
    }

    companion object {
        /**
         * A sendable text is exactly the 304-character base64url form of a
         * 228-byte wire message. Anything else is never sent: an SMS costs
         * money and cannot be unsent.
         */
        fun isSendableText(text: String): Boolean =
            text.length == KengeleMessage.TEXT_SIZE && isBase64UrlText(text)
    }
}
