package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.message.nostr.GiftWrap
import com.altradits.kengele.message.nostr.NostrEvent
import com.altradits.kengele.message.nostr.RelayMessages
import com.altradits.kengele.message.nostr.WsException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A minimal NIP-01 client over one [WsChannel]: publish with OK, subscribe,
 * unsubscribe. Mirrors the Go transport's `relayConn`: only an explicit OK
 * true counts as accepted; a dropped connection or a timeout does not.
 */
class RelayConnection(private val channel: WsChannel) {
    sealed interface PublishOutcome {
        object Accepted : PublishOutcome
        object Rejected : PublishOutcome
        object Failed : PublishOutcome
    }

    /** One open subscription: events arrive on [poll] until [ended]. */
    class Subscription internal constructor(val id: String) {
        private val queue = LinkedBlockingQueue<NostrEvent>(64)
        internal val ended = AtomicBoolean(false)

        val isEnded: Boolean get() = ended.get()

        /**
         * The next event, or null on timeout. A null with [isEnded] set and
         * an empty queue means the subscription is over.
         */
        fun poll(timeoutMs: Long): NostrEvent? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

        internal fun offer(ev: NostrEvent) {
            if (!ended.get()) queue.put(ev)
        }

        internal fun end() {
            ended.set(true)
        }
    }

    private val waiters = ConcurrentHashMap<String, LinkedBlockingQueue<PublishOutcome>>()
    private val subs = ConcurrentHashMap<String, Subscription>()
    private val closed = AtomicBoolean(false)
    private val random = SecureRandom()

    private val reader = thread(isDaemon = true, name = "nostr-relay-reader") { readLoop() }

    /**
     * Sends [event] and waits for the relay's OK, up to [timeoutMs]. Only an
     * explicit OK true is [PublishOutcome.Accepted].
     */
    fun publish(event: NostrEvent, timeoutMs: Long): PublishOutcome {
        if (closed.get()) return PublishOutcome.Failed
        val box = LinkedBlockingQueue<PublishOutcome>(1)
        waiters[event.id] = box
        try {
            channel.sendText(RelayMessages.publish(event))
        } catch (e: WsException) {
            waiters.remove(event.id)
            return PublishOutcome.Failed
        }
        val out = try {
            box.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
        waiters.remove(event.id)
        return out ?: PublishOutcome.Failed
    }

    /** Subscribes to gift wraps for [pTagHex] since [sinceSecs]. */
    fun subscribe(pTagHex: String, sinceSecs: Long): Subscription {
        val id = ByteArray(8).also(random::nextBytes).toHex()
        val sub = Subscription(id)
        subs[id] = sub
        try {
            channel.sendText(RelayMessages.req(id, listOf(GiftWrap.KIND_GIFT_WRAP), pTagHex, sinceSecs))
        } catch (e: WsException) {
            subs.remove(id)
            sub.end()
            throw e
        }
        return sub
    }

    fun unsubscribe(id: String) {
        val sub = subs.remove(id)
        sub?.end()
        if (!closed.get()) {
            try {
                channel.sendText(RelayMessages.close(id))
            } catch (e: WsException) {
                // Already gone.
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            channel.close()
        } catch (e: Exception) {
            // Already gone.
        }
        reader.interrupt()
        try {
            reader.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        failAll()
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val raw = try {
                    channel.nextText()
                } catch (e: WsException) {
                    return
                }
                when (val msg = RelayMessages.parse(raw)) {
                    is RelayMessages.Incoming.Ok -> {
                        val box = waiters.remove(msg.eventId)
                        box?.offer(if (msg.ok) PublishOutcome.Accepted else PublishOutcome.Rejected)
                    }
                    is RelayMessages.Incoming.RelayEvent -> {
                        val sub = subs[msg.subId]
                        if (sub != null && !sub.isEnded) {
                            try {
                                sub.offer(msg.event)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return
                            }
                        }
                    }
                    is RelayMessages.Incoming.Eose -> Unit // backfill just arrives; nothing to wait for
                    is RelayMessages.Incoming.Closed -> {
                        subs.remove(msg.subId)?.end()
                    }
                    else -> Unit
                }
            }
        } finally {
            failAll()
        }
    }

    private fun failAll() {
        for (box in waiters.values) box.offer(PublishOutcome.Failed)
        waiters.clear()
        for (sub in subs.values) sub.end()
        subs.clear()
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        return buildString {
            for (b in this@toHex) {
                append(digits[(b.toInt() ushr 4) and 0xF])
                append(digits[b.toInt() and 0xF])
            }
        }
    }
}
