package com.altradits.kengele.nostr

import com.altradits.kengele.checkin.CheckInSender
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.nostr.GiftWrap
import com.altradits.kengele.message.nostr.MemoryOutbox
import com.altradits.kengele.message.nostr.NostrBackoff
import com.altradits.kengele.message.nostr.NostrEvent
import com.altradits.kengele.message.nostr.NostrException
import com.altradits.kengele.message.nostr.OutboxItem
import com.altradits.kengele.message.nostr.RelayUrl
import com.altradits.kengele.sms.SmsScheduler
import com.altradits.kengele.sms.SystemSmsScheduler
import kotlin.concurrent.thread

/** Process-wide outbox used by the senders; tests use their own. */
object NostrOutboxes {
    var shared = MemoryOutbox()
}

/**
 * What one [NostrCheckInSender.send] did. [accepted] is the fewest relay
 * accepts any attempted event got, so "sent to [accepted] of [relays]"
 * never overstates what the relays confirmed.
 */
data class NostrSendStatus(
    /** Events at least minAccepted relays accepted. */
    val sent: Int,
    /** Events attempted (texts paired with a guardian). */
    val total: Int,
    /** Events below quorum, now in the outbox for retry. */
    val queued: Int,
    /** Fewest accepts any attempted event got (0 when none attempted). */
    val accepted: Int,
    /** Relays each event was published to. */
    val relays: Int,
    /** Texts dropped: malformed, or with no matching guardian. */
    val dropped: Int,
)

/**
 * Publishes sealed check-ins over Nostr (docs/nostr-transport.md,
 * "Sending"): each text is gift-wrapped for its guardian and published to
 * every configured relay; only an explicit OK true counts, and an event
 * fewer than [minAccepted] relays accept goes to the memory outbox with a
 * backoff retry while the app is alive. Retries republish the same gift
 * wrap, only to relays that have not accepted it yet.
 *
 * [recipients] runs parallel to the sealed texts [send] receives (texts[i]
 * belongs to recipients[i]), like [com.altradits.kengele.sms.SmsDirectory]:
 * a text whose guardian is missing is dropped, never misaddressed.
 * Malformed texts are dropped too: a relay must never see a mangled wrap.
 *
 * Network work runs on [background]; [onStatus] is called there, so the
 * activity must post to the UI thread itself. All Android calls sit behind
 * [RelayPublisher] and [SmsScheduler], so JUnit-only tests cover the
 * quorum, queueing and backoff logic.
 */
class NostrCheckInSender(
    private val ownPriv: ByteArray,
    private val recipients: List<ByteArray>,
    relays: List<String>,
    minAccepted: Int = 1,
    private val publish: RelayPublisher = SocketRelayPublisher(),
    private val outbox: MemoryOutbox = NostrOutboxes.shared,
    private val scheduler: SmsScheduler = SystemSmsScheduler(),
    private val publishTimeoutMs: Long = 10_000,
    private val retryMinMs: Long = 5_000,
    private val retryMaxMs: Long = 300_000,
    private val clockSecs: () -> Long = { System.currentTimeMillis() / 1000 },
    private val wrap: (wire: ByteArray, recipient: ByteArray, nowSecs: Long) -> NostrEvent =
        { wire, recipient, now -> GiftWrap.wrap(wire, ownPriv, recipient, now) },
    private val background: (task: () -> Unit) -> Unit = { task ->
        thread(isDaemon = true, name = "nostr-send") { task() }
    },
    private val onStatus: (NostrSendStatus) -> Unit = {},
) : CheckInSender {

    val relays: List<String> = RelayUrl.normalizeAll(relays)
    val minAccepted: Int = minAccepted.coerceAtLeast(1)

    private val lock = Any()
    private var retryDelayMs: Long = retryMinMs
    private var retryScheduled: Boolean = false

    init {
        if (this.relays.isEmpty()) throw NostrException("no relays configured")
    }

    override fun send(texts: List<String>) {
        if (texts.isEmpty()) return
        background { sendNow(texts) }
    }

    /** Queued gift wraps still waiting for a quorum (tests and diagnostics). */
    fun queuedCount(): Int = outbox.size()

    /**
     * Republishes every queued gift wrap once, only to relays that have
     * not accepted it yet; returns how many still wait. Public so retries
     * and tests drive the same path as [send].
     */
    fun flush(): Int {
        val items = outbox.list()
        var pending = 0
        for (item in items) {
            publishItem(item)
            if (item.accepted.size >= minAccepted) {
                outbox.remove(item.event.id)
            } else {
                pending++
                outbox.put(item)
            }
        }
        return pending
    }

    private fun sendNow(texts: List<String>) {
        var sent = 0
        var queued = 0
        var dropped = 0
        var worstAccepted = Int.MAX_VALUE
        var attempted = 0
        for ((index, text) in texts.withIndex()) {
            val recipient = recipients.getOrNull(index)
            if (recipient == null || recipient.size != 32) {
                dropped++
                continue
            }
            val wire = try {
                KengeleMessage.decodeText(text)
            } catch (e: Exception) {
                dropped++
                continue
            }
            val event = try {
                wrap(wire, recipient, clockSecs())
            } catch (e: Exception) {
                dropped++
                continue
            }
            attempted++
            val item = OutboxItem(event = event, relays = relays, queuedSecs = clockSecs())
            publishItem(item)
            worstAccepted = minOf(worstAccepted, item.accepted.size)
            if (item.accepted.size >= minAccepted) {
                sent++
            } else {
                queued++
                outbox.put(item)
            }
        }
        if (queued > 0) ensureRetry()
        onStatus(
            NostrSendStatus(
                sent = sent,
                total = attempted,
                queued = queued,
                accepted = if (attempted == 0) 0 else worstAccepted,
                relays = relays.size,
                dropped = dropped,
            ),
        )
    }

    /** Publishes [item] to every relay that has not accepted it yet, in turn. */
    private fun publishItem(item: OutboxItem) {
        item.attempts++
        for (url in item.relays) {
            if (url in item.accepted) continue
            val outcome = try {
                publish.publish(item.event, url, publishTimeoutMs)
            } catch (e: Exception) {
                // A publisher bug must not break the round: count it as failed.
                RelayPublishOutcome.FAILED
            }
            if (outcome == RelayPublishOutcome.ACCEPTED) item.accepted.add(url)
        }
    }

    private fun ensureRetry() {
        synchronized(lock) {
            if (retryScheduled) return
            retryScheduled = true
        }
        scheduler.schedule(retryDelayMs) { runRetry() }
    }

    private fun runRetry() {
        val pending = try {
            flush()
        } catch (e: Exception) {
            0
        }
        synchronized(lock) {
            // The outbox size, not just this flush's list: a send that
            // queued while the flush ran must keep the retry alive.
            if (pending == 0 && outbox.size() == 0) {
                retryDelayMs = retryMinMs
                retryScheduled = false
                return
            }
            retryDelayMs = NostrBackoff.nextMs(retryDelayMs, retryMaxMs)
            retryScheduled = false
        }
        ensureRetry()
    }
}
