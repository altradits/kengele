package com.altradits.kengele.nostr

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Vectors
import com.altradits.kengele.message.nostr.GiftWrap
import com.altradits.kengele.message.nostr.NostrEvent
import com.altradits.kengele.message.nostr.RelayMessages
import com.altradits.kengele.message.nostr.WsFrame
import com.altradits.kengele.message.nostr.WsHandshake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The socket layer — [SocketRelayPublisher] and [SocketRelayTester] over
 * real loopback TCP — against a fake relay on a [ServerSocket].
 *
 * NOTE: loopback sockets are blocked in the sandbox, so these tests fail
 * there with a bind/connect error. Run them unsandboxed:
 * `export JAVA_HOME=/tmp/kengele-toolchain/jdk17/Contents/Home
 * ANDROID_HOME=/tmp/kengele-android-sdk GRADLE_USER_HOME=/tmp/kengele-gradle;
 * /tmp/kengele-toolchain/gradle-8.7/bin/gradle -p android
 * :app:testDebugUnitTest --offline --console=plain`
 * then check LoopbackNostrSenderTest. Quorum, outbox and backoff over fake
 * relays are covered by [NostrCheckInSenderTest], which runs everywhere.
 * All keys are made up.
 */
class LoopbackNostrSenderTest {
    private class LoopRelay : AutoCloseable {
        val server = ServerSocket()
        val stored: MutableList<NostrEvent> = Collections.synchronizedList(mutableListOf())
        val running = AtomicBoolean(true)

        @Volatile
        var accept = true

        init {
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", 0))
            val t = Thread { acceptLoop() }
            t.isDaemon = true
            t.start()
        }

        fun url(): String = "ws://127.0.0.1:${server.localPort}"

        private fun acceptLoop() {
            while (running.get()) {
                try {
                    val s = server.accept()
                    val t = Thread { handle(s) }
                    t.isDaemon = true
                    t.start()
                } catch (e: Exception) {
                    return
                }
            }
        }

        private fun handle(s: Socket) {
            try {
                s.use {
                    val input = it.getInputStream()
                    val output = it.getOutputStream()
                    val head = WsHandshake.readHead(input).toString(Charsets.US_ASCII)
                    val key = head.split("\r\n").first { l -> l.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
                    val reply = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ${WsHandshake.acceptFor(key)}\r\n\r\n"
                    output.write(reply.toByteArray(Charsets.US_ASCII))
                    output.flush()
                    fun send(text: String) {
                        output.write(WsFrame.encodeUnmasked(true, WsFrame.OP_TEXT, text.toByteArray(Charsets.UTF_8)))
                        output.flush()
                    }
                    while (running.get()) {
                        val f = WsFrame.decode(input, allowMasked = true)
                        if (f.opcode == WsFrame.OP_CLOSE) return
                        if (f.opcode == WsFrame.OP_PING) {
                            output.write(WsFrame.encodeUnmasked(true, WsFrame.OP_PONG, f.payload))
                            output.flush()
                            continue
                        }
                        if (f.opcode != WsFrame.OP_TEXT) continue
                        when (val m = RelayMessages.parse(f.payload.toString(Charsets.UTF_8))) {
                            is RelayMessages.Incoming.Publish -> {
                                val ok = accept && m.event.checkSignature()
                                if (ok) stored.add(m.event)
                                send("[\"OK\",\"${m.event.id}\",${if (ok) "true" else "false"},\"${if (ok) "" else "blocked: test"}\"]")
                            }
                            else -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                // Client went away.
            }
        }

        override fun close() {
            running.set(false)
            try {
                server.close()
            } catch (e: Exception) {
                // Already gone.
            }
        }
    }

    private val activistPriv = Vectors.hex("activist_priv_hex")
    private val guardianPub = Vectors.hex("guardian_pub_hex")
    private val now = Vectors.num("event_created_unix") + 60

    private fun giftWrap(): NostrEvent {
        val wire = KengeleMessage.seal(
            KengeleMessage.Event(KengeleMessage.Kind.PANIC, now, 3, "msaada".toByteArray(Charsets.UTF_8)),
            activistPriv,
            guardianPub,
        )
        return GiftWrap.wrap(wire, activistPriv, guardianPub, now)
    }

    @Test
    fun publishAcceptedOverLoopback() {
        LoopRelay().use { relay ->
            val event = giftWrap()
            assertEquals(RelayPublishOutcome.ACCEPTED, SocketRelayPublisher().publish(event, relay.url(), 5_000))
            assertEquals(listOf(event), relay.stored)
        }
    }

    @Test
    fun publishRejectedOverLoopback() {
        LoopRelay().use { relay ->
            relay.accept = false
            assertEquals(RelayPublishOutcome.REJECTED, SocketRelayPublisher().publish(giftWrap(), relay.url(), 5_000))
            assertTrue(relay.stored.isEmpty())
        }
    }

    @Test
    fun publishToDeadPortFailsWithoutThrowing() {
        val deadPort = LoopRelay().use { it.server.localPort }
        assertEquals(
            RelayPublishOutcome.FAILED,
            SocketRelayPublisher(connectTimeoutMs = 2_000).publish(giftWrap(), "ws://127.0.0.1:$deadPort", 2_000),
        )
    }

    @Test
    fun publishToBadUrlFailsWithoutThrowing() {
        assertEquals(RelayPublishOutcome.FAILED, SocketRelayPublisher().publish(giftWrap(), "not a url !!!", 2_000))
    }

    @Test
    fun testerReportsReachableAndDeadRelays() {
        LoopRelay().use { relay ->
            assertTrue(SocketRelayTester().reachable(relay.url(), 5_000))
            assertFalse(SocketRelayTester().reachable("ws://127.0.0.1:1", 2_000))
            assertFalse(SocketRelayTester().reachable("not a url !!!", 2_000))
        }
    }
}
