package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.MessageException
import com.altradits.kengele.message.Secp256k1
import com.altradits.kengele.message.nostr.GiftWrap
import com.altradits.kengele.message.nostr.MemoryOutbox
import com.altradits.kengele.message.nostr.NostrBackoff
import com.altradits.kengele.message.nostr.NostrEvent
import com.altradits.kengele.message.nostr.NostrException
import com.altradits.kengele.message.nostr.OutboxItem
import com.altradits.kengele.message.nostr.RelayUrl
import com.altradits.kengele.message.nostr.SeenSet
import com.altradits.kengele.message.nostr.WsException
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Sends and receives Kengele messages over Nostr relays, mirroring
 * `internal/transport/nostr.Transport`: gift-wrap publish with a
 * [Config.minAccepted] quorum, a memory outbox with backoff retries, gift-wrap
 * subscriptions with Since backfill, seen-set dedup and per-relay
 * reachability reports. Its key is the same key the message package signs and
 * decrypts with. Android-free; safe for concurrent use.
 */
class NostrTransport(
    private val ownPriv: ByteArray,
    val config: Config,
) {
    /** Relay reachability after every publish round and whenever a receive connection opens or drops. Must not block. */
    fun interface Connectivity {
        fun relayStatus(reachable: Int, total: Int, atSecs: Long)
    }

    data class Config(
        /** The user's relays. Every message goes to all of them (plus any inbox relays set for the recipient). */
        val relays: List<String>,
        /** How many relays must accept a gift wrap for [send] to count it as sent. */
        val minAccepted: Int = 1,
        /** Bounds one relay's connect and publish, in ms. */
        val publishTimeoutMs: Long = 10_000,
        /** Bounds the backoff of [runLoop] and of receive reconnects, in ms. */
        val retryMinMs: Long = 5_000,
        val retryMaxMs: Long = 300_000,
        /** Keys whose messages [startReceive] accepts; others are dropped. */
        val senders: List<ByteArray> = emptyList(),
        /** How far back the first subscription looks, in epoch seconds. Default: now minus [GiftWrap.MAX_JITTER_SECS]. */
        val sinceSecs: Long? = null,
        /** Keeps unsent messages. */
        val outbox: MemoryOutbox = MemoryOutbox(),
        /** Told about relay reachability, when set. */
        val connectivity: Connectivity? = null,
        /** How many recent messages receive remembers. */
        val dedupSize: Int = 10_000,
        val clockSecs: () -> Long = { System.currentTimeMillis() / 1000 },
        /** Opens a channel; tests inject fakes. */
        val connect: (String) -> WsChannel = { url -> SocketWsChannel(url, 10_000) },
    )

    /** What happened to one [send]. */
    data class Result(val accepted: Int, val queued: Boolean)

    /** One verified message received over Nostr. */
    data class Delivery(val event: KengeleMessage.Event, val wire: ByteArray)

    /** A running [startReceive]; [stop] ends every receive loop. */
    class ReceiveHandle internal constructor(
        private val stopFlag: AtomicBoolean,
        private val threads: List<Thread>,
    ) {
        fun stop() {
            stopFlag.set(true)
            for (t in threads) {
                t.interrupt()
                try {
                    t.join(5000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        val running: Boolean get() = !stopFlag.get()
    }

    private val ownPub: ByteArray = try {
        Secp256k1.publicKey(ownPriv)
    } catch (e: MessageException) {
        throw NostrException("bad transport key")
    }
    private val ownHex: String = ownPub.toHex()

    private val lock = Any()
    private val conns = mutableMapOf<String, RelayConnection>() // publish connections
    private val up = mutableMapOf<String, Boolean>() // last known reachability per relay
    private val inbox = mutableMapOf<String, List<String>>()
    private val notify = LinkedBlockingQueue<Boolean>(1)
    private val dedup = SeenSet(if (config.dedupSize <= 0) 10_000 else config.dedupSize)

    init {
        if (config.relays.isEmpty()) throw NostrException("no relays configured")
    }

    fun ownPub(): ByteArray = ownPub.copyOf()

    /** Adds a recipient's inbox relays to the relays their messages are published to. */
    fun setInboxRelays(recipientX: ByteArray, relays: List<String>) {
        synchronized(lock) { inbox[recipientX.toHex()] = RelayUrl.normalizeAll(relays) }
    }

    /**
     * Gift-wraps [wire] for [recipientX] and publishes it. When fewer than
     * minAccepted relays accept it, the wrap goes to the outbox and
     * [Result.queued] is true; the message is not lost.
     */
    fun send(wire: ByteArray, recipientX: ByteArray): Result {
        if (recipientX.size != 32) throw NostrException("recipient key must be 32 bytes")
        val gw = GiftWrap.wrap(wire, ownPriv, recipientX, config.clockSecs())
        val item = OutboxItem(
            event = gw,
            relays = relaysFor(recipientX.toHex()),
            queuedSecs = config.clockSecs(),
        )
        publishItem(item)
        val n = item.accepted.size
        if (n >= config.minAccepted.coerceAtLeast(1)) return Result(n, false)
        config.outbox.put(item)
        notify.offer(true)
        return Result(n, true)
    }

    /** Sends wires[i] to recipients[i], for separately sealed messages. */
    fun sendAll(wires: List<ByteArray>, recipients: List<ByteArray>): List<Result> {
        if (wires.size != recipients.size) throw NostrException("wires and recipients differ in length")
        return wires.indices.map { send(wires[it], recipients[it]) }
    }

    /** Retries every queued gift wrap once; returns how many are still waiting. */
    fun flush(): Int {
        val items = config.outbox.list()
        var pending = 0
        for (item in items) {
            publishItem(item)
            if (item.accepted.size >= config.minAccepted.coerceAtLeast(1)) {
                config.outbox.remove(item.event.id)
            } else {
                pending++
                config.outbox.put(item)
            }
        }
        return pending
    }

    /**
     * Retries the outbox until [stop] is set: at once when [send] queues
     * something, then with exponential backoff while anything still waits.
     */
    fun runLoop(stop: AtomicBoolean) {
        var delay = config.retryMinMs
        while (!stop.get()) {
            val pending = try {
                flush()
            } catch (e: Exception) {
                0
            }
            if (pending == 0) {
                delay = config.retryMinMs
                while (!stop.get()) {
                    if (notify.poll(500, TimeUnit.MILLISECONDS) != null) break
                }
            } else {
                sleepInterruptibly(delay, stop)
                delay = NostrBackoff.nextMs(delay, config.retryMaxMs)
            }
        }
    }

    /** Drops the publish connections. */
    fun close() {
        synchronized(lock) {
            for (c in conns.values) {
                try {
                    c.close()
                } catch (e: Exception) {
                    // Already gone.
                }
            }
            conns.clear()
        }
    }

    /**
     * Subscribes on every relay for gift wraps addressed to this key and calls
     * [onDelivery] once per verified message, however many relays carry it.
     * Messages from keys not in the config senders are dropped.
     */
    fun startReceive(onDelivery: (Delivery) -> Unit): ReceiveHandle {
        val stop = AtomicBoolean(false)
        val firstSince = config.sinceSecs ?: (config.clockSecs() - GiftWrap.MAX_JITTER_SECS)
        val threads = config.relays.map { url ->
            thread(isDaemon = true, name = "nostr-receive") { receiveLoop(url, firstSince, stop, onDelivery) }
        }
        return ReceiveHandle(stop, threads)
    }

    private fun receiveLoop(url: String, firstSince: Long, stop: AtomicBoolean, onDelivery: (Delivery) -> Unit) {
        var since = firstSince
        var delay = config.retryMinMs
        while (!stop.get()) {
            val conn = try {
                RelayConnection(config.connect(url))
            } catch (e: Exception) {
                setUp(url, false)
                if (!sleepInterruptibly(delay, stop)) return
                delay = NostrBackoff.nextMs(delay, config.retryMaxMs)
                continue
            }
            setUp(url, true)
            delay = config.retryMinMs
            val connectedAt = config.clockSecs()
            try {
                val sub = try {
                    conn.subscribe(ownHex, since)
                } catch (e: WsException) {
                    null
                }
                if (sub != null) {
                    while (!stop.get() && !sub.isEnded) {
                        val ev = try {
                            sub.poll(500)
                        } catch (e: Exception) {
                            null
                        } ?: continue
                        val d = open(ev)
                        if (d != null) {
                            try {
                                onDelivery(d)
                            } catch (e: Exception) {
                                // A listener bug must not kill the loop.
                            }
                        }
                    }
                    conn.unsubscribe(sub.id)
                }
            } finally {
                try {
                    conn.close()
                } catch (e: Exception) {
                    // Already gone.
                }
            }
            setUp(url, false)
            // After a drop, look back far enough to catch anything backdated
            // that was published while we were away. Dedup hides repeats.
            since = connectedAt - GiftWrap.MAX_JITTER_SECS
            if (stop.get()) return
            if (!sleepInterruptibly(delay, stop)) return
        }
    }

    /**
     * Unwraps and verifies one gift wrap. Anything wrong is dropped silently:
     * nothing about it is logged.
     */
    fun open(ev: NostrEvent): Delivery? {
        if (dedup.seen("e:" + ev.id)) return null
        val unwrapped = try {
            GiftWrap.unwrap(ev, ownPriv)
        } catch (e: NostrException) {
            return null
        }
        if (!isSender(unwrapped.sealer)) return null
        val event = try {
            KengeleMessage.open(unwrapped.wire, ownPriv, listOf(unwrapped.sealer), config.clockSecs())
        } catch (e: MessageException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        // The same message can arrive in different gift wraps (one per relay
        // round, or re-sent by the sender); keep only the first.
        val sum = MessageDigest.getInstance("SHA-256").digest(unwrapped.wire)
        if (dedup.seen("w:" + sum.toHex())) return null
        return Delivery(event, unwrapped.wire)
    }

    private fun isSender(k: ByteArray): Boolean = config.senders.any { it.contentEquals(k) }

    /** Tries every relay of [item] that has not accepted it yet, in parallel, and records which accept. */
    private fun publishItem(item: OutboxItem) {
        item.attempts++
        val targets = item.relays.filter { it !in item.accepted }
        if (targets.isEmpty()) {
            report()
            return
        }
        val accepted = BooleanArray(targets.size)
        val threads = targets.mapIndexed { i, url ->
            thread(isDaemon = true) {
                val (ok, reached) = publishOne(url, item.event)
                setUp(url, reached)
                accepted[i] = ok
            }
        }
        for (t in threads) t.join()
        targets.forEachIndexed { i, url ->
            if (accepted[i]) item.accepted.add(url)
        }
        report()
    }

    /** Returns whether the relay accepted the event and whether it could be reached at all. */
    private fun publishOne(url: String, event: NostrEvent): Pair<Boolean, Boolean> {
        val conn = try {
            conn(url)
        } catch (e: Exception) {
            return Pair(false, false)
        }
        return when (conn.publish(event, config.publishTimeoutMs)) {
            RelayConnection.PublishOutcome.Accepted -> Pair(true, true)
            RelayConnection.PublishOutcome.Rejected -> Pair(false, true) // the relay answered "no": reachable
            RelayConnection.PublishOutcome.Failed -> {
                dropConn(url, conn)
                Pair(false, false)
            }
        }
    }

    private fun conn(url: String): RelayConnection {
        synchronized(lock) { conns[url]?.let { return it } }
        val fresh = RelayConnection(config.connect(url))
        synchronized(lock) {
            val old = conns.put(url, fresh)
            if (old != null && old !== fresh) {
                try {
                    old.close()
                } catch (e: Exception) {
                    // Already gone.
                }
            }
        }
        return fresh
    }

    private fun dropConn(url: String, conn: RelayConnection) {
        synchronized(lock) {
            if (conns[url] === conn) conns.remove(url)
        }
        try {
            conn.close()
        } catch (e: Exception) {
            // Already gone.
        }
    }

    private fun relaysFor(recipientHex: String): List<String> {
        synchronized(lock) {
            return (config.relays + (inbox[recipientHex] ?: emptyList())).toSet().sorted()
        }
    }

    private fun setUp(url: String, upNow: Boolean) {
        val changed: Boolean
        synchronized(lock) {
            val known = up.containsKey(url)
            changed = up[url] != upNow
            up[url] = upNow
            if (!changed && known) return
        }
        report()
    }

    private fun report() {
        val fn = config.connectivity ?: return
        val snapshot: Pair<Int, Int>
        synchronized(lock) {
            snapshot = Pair(up.values.count { it }, up.size)
        }
        try {
            fn.relayStatus(snapshot.first, snapshot.second, config.clockSecs())
        } catch (e: Exception) {
            // A listener bug must not break sending.
        }
    }

    private fun sleepInterruptibly(ms: Long, stop: AtomicBoolean): Boolean {
        var left = ms.coerceAtLeast(0)
        while (left > 0 && !stop.get()) {
            try {
                Thread.sleep(minOf(500, left))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            left -= 500
        }
        return !stop.get()
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
