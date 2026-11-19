package com.altradits.kengele.nostr

import com.altradits.kengele.message.nostr.FrameReader
import com.altradits.kengele.message.nostr.NostrEvent
import com.altradits.kengele.message.nostr.NostrException
import com.altradits.kengele.message.nostr.RelayMessages
import com.altradits.kengele.message.nostr.RelayUrl
import com.altradits.kengele.message.nostr.WsException
import com.altradits.kengele.message.nostr.WsFrame
import com.altradits.kengele.message.nostr.WsHandshake
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/** What one relay did with one gift wrap. Only [ACCEPTED] counts as sent. */
enum class RelayPublishOutcome {
    /** The relay answered OK true. */
    ACCEPTED,

    /** The relay answered OK false: reachable, but refused the event. */
    REJECTED,

    /** Unreachable, handshake failed, or no OK in time. */
    FAILED,
}

/**
 * Publishes one gift wrap to one relay and waits for its OK. Android-free;
 * tests inject fakes, the app uses [SocketRelayPublisher]. Blocking: call
 * off the UI thread.
 */
fun interface RelayPublisher {
    fun publish(event: NostrEvent, relayUrl: String, timeoutMs: Long): RelayPublishOutcome
}

/**
 * Probes whether one relay is reachable (TCP connect plus the WebSocket
 * handshake, no event sent). Backs the settings screen's test button.
 * Blocking: call off the UI thread.
 */
fun interface RelayTester {
    fun reachable(relayUrl: String, timeoutMs: Long): Boolean
}

/**
 * A [RelayPublisher] over a plain `java.net.Socket` (or TLS for `wss://`),
 * reusing the message module's handshake, framing and envelope code. Each
 * publish opens a fresh connection, sends one EVENT, reads until the
 * relay's OK for that event id (answering pings on the way), then closes.
 * Sequential and short-lived: the activist side only publishes, so it
 * needs none of the guardian's subscription machinery.
 */
class SocketRelayPublisher(
    private val connectTimeoutMs: Int = 10_000,
) : RelayPublisher {
    override fun publish(event: NostrEvent, relayUrl: String, timeoutMs: Long): RelayPublishOutcome {
        val socket = try {
            RelaySockets.open(relayUrl, connectTimeoutMs)
        } catch (e: WsException) {
            return RelayPublishOutcome.FAILED
        }
        try {
            socket.soTimeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val out = socket.getOutputStream()
            out.write(WsFrame.encode(WsFrame.OP_TEXT, RelayMessages.publish(event).toByteArray(Charsets.UTF_8)))
            out.flush()
            val reader = FrameReader(socket.getInputStream())
            val sendPong: (ByteArray) -> Unit = { pong ->
                out.write(WsFrame.encode(WsFrame.OP_PONG, pong))
                out.flush()
            }
            val deadlineMs = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0)
            while (true) {
                if (System.currentTimeMillis() > deadlineMs) return RelayPublishOutcome.FAILED
                val text = try {
                    reader.nextText(sendPong)
                } catch (e: WsException) {
                    return RelayPublishOutcome.FAILED
                } catch (e: IOException) {
                    // Read timeout or a broken pipe: no OK in time.
                    return RelayPublishOutcome.FAILED
                }
                when (val msg = RelayMessages.parse(text)) {
                    is RelayMessages.Incoming.Ok ->
                        if (msg.eventId == event.id) {
                            return if (msg.ok) RelayPublishOutcome.ACCEPTED else RelayPublishOutcome.REJECTED
                        }
                    // Anything else (NOTICE, stray EVENT, garbage) is
                    // ignored while waiting for our OK, like go-nostr.
                    else -> Unit
                }
            }
        } catch (e: IOException) {
            return RelayPublishOutcome.FAILED
        } finally {
            try {
                socket.close()
            } catch (e: IOException) {
                // Already gone.
            }
        }
        // Unreachable: every path above returns. Fail safe so a skipped
        // write is queued for retry rather than dropped silently.
        return RelayPublishOutcome.FAILED
    }
}

/** A [RelayTester] that connects and runs the opening handshake. */
class SocketRelayTester(
    private val connectTimeoutMs: Int = 10_000,
) : RelayTester {
    override fun reachable(relayUrl: String, timeoutMs: Long): Boolean {
        val socket = try {
            RelaySockets.open(relayUrl, timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt().coerceAtMost(connectTimeoutMs))
        } catch (e: WsException) {
            return false
        }
        try {
            socket.close()
        } catch (e: IOException) {
            // Already gone.
        }
        return true
    }
}

/** Opens a connected, handshaked relay socket; throws [WsException]. */
private object RelaySockets {
    fun open(relayUrl: String, connectTimeoutMs: Int): Socket {
        val normalized = RelayUrl.normalize(relayUrl)
        if (normalized.isEmpty()) throw WsException("bad relay URL")
        val p = try {
            RelayUrl.parse(normalized)
        } catch (e: NostrException) {
            throw WsException("bad relay URL")
        }
        try {
            var socket: Socket = Socket()
            socket.connect(InetSocketAddress(p.host, p.port), connectTimeoutMs)
            if (p.tls) {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, p.host, p.port, true) as javax.net.ssl.SSLSocket
                tls.soTimeout = connectTimeoutMs
                tls.startHandshake()
                socket = tls
            }
            socket.soTimeout = connectTimeoutMs
            val key = WsHandshake.clientKey()
            val out = socket.getOutputStream()
            out.write(WsHandshake.request(p.host, p.port, p.tls, p.path, key).toByteArray(Charsets.US_ASCII))
            out.flush()
            WsHandshake.checkResponse(socket.getInputStream(), key)
            return socket
        } catch (e: WsException) {
            throw e
        } catch (e: IOException) {
            throw WsException("connect failed: ${e.message}")
        }
    }
}
