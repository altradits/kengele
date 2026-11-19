package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.guardian.GuardianState
import com.altradits.kengele.guardian.confirm.Tally
import com.altradits.kengele.message.nostr.NostrEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The app's shared Nostr transport, built from [GuardianState] (own key,
 * relays, min-accepted) and the enrolled kit's senders. One transport serves
 * sends and the inbox receiver; a retry thread drains the outbox while
 * anything waits. All threads are daemons; activities start and stop the
 * receiver with the screen.
 */
object NostrService {
    /** The outcome of sending a batch of sealed messages. */
    data class SendSummary(val reached: Int, val total: Int, val queued: Int)

    /** Last known relay reachability, for the UI. */
    data class RelayStatus(val reachable: Int, val total: Int, val atSecs: Long)

    @Volatile
    private var transport: NostrTransport? = null

    @Volatile
    private var signature: String = ""

    @Volatile
    private var retryStop: AtomicBoolean? = null

    @Volatile
    private var receiver: NostrTransport.ReceiveHandle? = null

    @Volatile
    var status: RelayStatus? = null
        private set

    /** Called on every reachability change; must post to the UI thread itself. */
    @Volatile
    var statusListener: (() -> Unit)? = null

    /**
     * The shared transport, or null when there is nothing to connect to (no
     * relays) or nobody to talk to (not enrolled). Rebuilds when the settings
     * change.
     */
    @Synchronized
    fun transport(): NostrTransport? {
        val relays = try {
            GuardianState.relays()
        } catch (e: Exception) {
            return null
        }
        val tally = try {
            GuardianState.tally()
        } catch (e: Exception) {
            null
        }
        if (relays.isEmpty() || tally == null) return null
        val own = try {
            GuardianState.ownPriv()
        } catch (e: Exception) {
            return null
        }
        val sig = relays.sorted().joinToString(",") + "|" + GuardianState.minAccepted()
        val cached = transport
        if (cached != null && sig == signature) return cached
        stopReceiverLocked()
        stopRetryLocked()
        try {
            cached?.close()
        } catch (e: Exception) {
            // Already gone.
        }
        val fresh = NostrTransport(
            own,
            NostrTransport.Config(
                relays = relays,
                minAccepted = GuardianState.minAccepted(),
                senders = tally.senders(),
                connectivity = NostrTransport.Connectivity { reachable, total, at ->
                    status = RelayStatus(reachable, total, at)
                    try {
                        statusListener?.invoke()
                    } catch (e: Exception) {
                        // A listener bug must not break the transport.
                    }
                },
            ),
        )
        transport = fresh
        signature = sig
        return fresh
    }

    /**
     * Sends each sealed [wires] entry to the matching recipient on the calling
     * thread (call off the UI thread). Starts the retry thread when anything
     * queues. Returns null when there is no transport (see [transport]).
     */
    fun sendNow(wires: List<ByteArray>, recipients: List<ByteArray>): SendSummary? {
        val t = transport() ?: return null
        val results = t.sendAll(wires, recipients)
        val queued = results.count { it.queued }
        if (queued > 0) ensureRetry(t)
        return SendSummary(reached = results.size - queued, total = results.size, queued = queued)
    }

    /**
     * Broadcasts a signed public note to every configured relay on the
     * calling thread (call off the UI thread). Returns null when there is no
     * transport (see [transport]).
     */
    fun publishPublic(event: NostrEvent): NostrTransport.PublicResult? {
        val t = transport() ?: return null
        return t.publishPublic(event)
    }

    /**
     * Starts the inbox receiver; deliveries go to [onDelivery] on background
     * threads. Returns false when there is no transport. Safe to call when
     * already running.
     */
    @Synchronized
    fun startReceiver(onDelivery: (NostrTransport.Delivery) -> Unit): Boolean {
        if (receiver?.running == true) return true
        val t = transport() ?: return false
        receiver = t.startReceive(onDelivery)
        return true
    }

    /** Stops the receiver; deliveries already taken still finish. */
    @Synchronized
    fun stopReceiver() {
        stopReceiverLocked()
    }

    private fun stopReceiverLocked() {
        try {
            receiver?.stop()
        } catch (e: Exception) {
            // Already gone.
        }
        receiver = null
    }

    @Synchronized
    private fun ensureRetry(t: NostrTransport) {
        if (retryStop != null) return
        val stop = AtomicBoolean(false)
        retryStop = stop
        thread(isDaemon = true, name = "nostr-retry") {
            try {
                t.runLoop(stop)
            } finally {
                synchronized(this) {
                    if (retryStop === stop) retryStop = null
                }
            }
        }
    }

    private fun stopRetryLocked() {
        retryStop?.set(true)
        retryStop = null
    }

    /** The enrolled tally, or null when not enrolled. */
    fun tally(): Tally? = try {
        GuardianState.tally()
    } catch (e: Exception) {
        null
    }
}
