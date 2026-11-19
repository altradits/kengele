package com.altradits.kengele.sms

import com.altradits.kengele.guardian.InMemoryGuardianStore
import com.altradits.kengele.message.KengeleMessage

/** True for a non-empty string over the unpadded base64url alphabet. */
internal fun isBase64UrlText(s: String): Boolean {
    if (s.isEmpty()) return false
    for (c in s) {
        val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
        if (!ok) return false
    }
    return true
}

/**
 * Trial-decodes one 304-character candidate: strict decode, then open with
 * a watched sender's key. Returns null for anything that is not ours
 * (wrong shape, unknown sender, altered text), never throwing.
 */
fun interface SmsDecoder {
    fun tryOpen(text: String): KengeleMessage.Event?
}

/**
 * SMS receive path (docs/sms-transport.md, section 3), no markers:
 *
 * - Path A: a full 304-character body is trial-decoded at once.
 * - Path B: 153/151-character candidate parts from the same number are
 *   held up to 10 minutes and tried in both orders when a partner arrives.
 *
 * Only combinations that decode AND open with a watched sender's key are
 * accepted; everything else is ignored and left untouched. The sender's
 * phone number is not trusted. Accepted events are deduplicated by
 * (signer, seq) and handed to [onEvent]; engine (watcher) wiring comes
 * later. Pure Kotlin so JUnit-only tests cover both paths.
 */
class SmsReassembler(
    private val decoder: SmsDecoder,
    private val onEvent: (KengeleMessage.Event) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Part(val text: String, val atMillis: Long)
    private data class Seen(val signerHex: String, val seq: Long)

    private val held = mutableMapOf<String, ArrayDeque<Part>>()
    private val order = ArrayDeque<Pair<String, Part>>()
    private var heldTotal = 0
    private val seen = LinkedHashMap<Seen, Unit>()

    /** Hands one incoming text from [from] to the Path A / Path B logic. */
    fun onText(from: String, body: String, nowMillis: Long = clockMillis()) {
        prune(nowMillis)
        val text = body.filterNot { it.isWhitespace() }
        if (text.length == KengeleMessage.TEXT_SIZE) {
            if (!isBase64UrlText(text)) return
            val event = decoder.tryOpen(text) ?: return
            emit(event)
            return
        }
        if ((text.length == PART_LEN_LONG || text.length == PART_LEN_SHORT) &&
            isBase64UrlText(text)
        ) {
            holdAndTry(from, Part(text, nowMillis))
        }
        // Anything else is an ordinary SMS: ignored and left untouched.
    }

    /** Candidate parts currently held (tests and diagnostics). */
    fun heldCount(): Int = heldTotal

    /** Distinct (signer, seq) pairs remembered (tests and diagnostics). */
    fun seenCount(): Int = seen.size

    private fun holdAndTry(from: String, part: Part) {
        val list = held.getOrPut(from) { ArrayDeque() }
        list.addLast(part)
        order.addLast(from to part)
        heldTotal++
        while (list.size > MAX_PARTS_PER_NUMBER) {
            order.remove(from to list.removeFirst())
            heldTotal--
        }
        while (heldTotal > MAX_PARTS_TOTAL) {
            val (number, oldest) = order.removeFirst()
            held[number]?.remove(oldest)
            if (held[number]?.isEmpty() == true) held.remove(number)
            heldTotal--
        }
        // Newest partner first; each pair is tried in both orders.
        for (other in list.asReversed()) {
            if (other === part) continue
            val event = decoder.tryOpen(other.text + part.text)
                ?: decoder.tryOpen(part.text + other.text)
                ?: continue
            list.remove(other)
            list.remove(part)
            order.remove(from to other)
            order.remove(from to part)
            heldTotal -= 2
            if (list.isEmpty()) held.remove(from)
            emit(event)
            return
        }
    }

    private fun prune(nowMillis: Long) {
        if (held.isEmpty()) return
        val expired = mutableListOf<Pair<String, Part>>()
        for ((number, list) in held) {
            val it = list.iterator()
            while (it.hasNext()) {
                val part = it.next()
                if (nowMillis - part.atMillis > PART_WINDOW_MILLIS) {
                    it.remove()
                    expired.add(number to part)
                    heldTotal--
                }
            }
        }
        for (e in expired) order.remove(e)
        held.entries.removeIf { it.value.isEmpty() }
    }

    private fun emit(event: KengeleMessage.Event) {
        val key = Seen(
            event.signer?.let { InMemoryGuardianStore.bytesToHex(it) } ?: "",
            event.seq,
        )
        if (seen.containsKey(key)) return
        seen[key] = Unit
        while (seen.size > MAX_SEEN) {
            val oldest = seen.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        onEvent(event)
    }

    companion object {
        /** Two-segment concatenated SMS splits 304 chars into 153 + 151. */
        const val PART_LEN_LONG = 153
        const val PART_LEN_SHORT = 151

        /** Path B parts are held for at most 10 minutes. */
        const val PART_WINDOW_MILLIS = 10 * 60 * 1000L

        /** At most 4 candidate parts per number, 64 in total. */
        const val MAX_PARTS_PER_NUMBER = 4
        const val MAX_PARTS_TOTAL = 64

        /** Bounds the (signer, seq) memory; oldest evicted first. */
        const val MAX_SEEN = 4096
    }
}
