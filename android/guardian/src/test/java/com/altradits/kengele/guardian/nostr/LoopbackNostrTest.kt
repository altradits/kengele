package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.guardian.confirm.TestKeys
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Secp256k1
import com.altradits.kengele.message.nostr.GiftWrap
import com.altradits.kengele.message.nostr.NostrEvent
import com.altradits.kengele.message.nostr.NostrJson
import com.altradits.kengele.message.nostr.RelayMessages
import com.altradits.kengele.message.nostr.WsFrame
import com.altradits.kengele.message.nostr.WsHandshake
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The socket layer — [SocketWsChannel] over real loopback TCP — against a
 * fake relay on a [ServerSocket].
 *
 * NOTE: loopback sockets are blocked in the sandbox, so these tests fail
 * there with a bind/connect error. Run them unsandboxed:
 * `export JAVA_HOME=/tmp/kengele-toolchain/jdk17/Contents/Home
 * ANDROID_HOME=/tmp/kengele-android-sdk GRADLE_USER_HOME=/tmp/kengele-gradle;
 * /tmp/kengele-toolchain/gradle-8.7/bin/gradle -p android
 * :guardian:testDebugUnitTest --offline --console=plain`
 * then check LoopbackNostrTest. The same flows over piped streams are
 * covered by [PipedChannelTest], which runs everywhere.
 */
class LoopbackNostrTest {
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
                    val subs = mutableListOf<String>()
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
                        val text = f.payload.toString(Charsets.UTF_8)
                        when (val m = RelayMessages.parse(text)) {
                            is RelayMessages.Incoming.Publish -> {
                                val ok = accept && m.event.checkSignature()
                                if (ok) stored.add(m.event)
                                send("[\"OK\",\"${m.event.id}\",${if (ok) "true" else "false"},\"${if (ok) "" else "blocked: test"}\"]")
                            }
                            is RelayMessages.Incoming.Unknown -> {
                                reqSubId(text)?.let { sub ->
                                    subs.add(sub)
                                    for (ev in stored.toList()) send("[\"EVENT\",\"$sub\",${ev.toJson()}]")
                                    send("[\"EOSE\",\"$sub\"]")
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                // Client went away.
            }
        }

        private fun reqSubId(text: String): String? {
            return try {
                val arr = NostrJson.parse(text) as? NostrJson.Arr ?: return null
                if ((arr.items.firstOrNull() as? NostrJson.Str)?.v != "REQ") return null
                (arr.items.getOrNull(1) as? NostrJson.Str)?.v
            } catch (e: Exception) {
                null
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

    @Test
    fun publishAndSubscribeOverLoopback() {
        LoopRelay().use { relay ->
            val conn = RelayConnection(SocketWsChannel(relay.url()))
            try {
                val ev = NostrEvent(
                    kind = GiftWrap.KIND_GIFT_WRAP,
                    createdAt = 1_800_000_000L,
                    tags = listOf(listOf("p", "ef".repeat(32))),
                    content = "loopback",
                ).sign(TestKeys.testPriv("loopback-signer"))
                assertEquals(RelayConnection.PublishOutcome.Accepted, conn.publish(ev, 5000))
                assertEquals(listOf(ev), relay.stored)
                val sub = conn.subscribe("ef".repeat(32), 0)
                try {
                    assertEquals(ev.id, sub.poll(5000)?.id)
                    assertNull(sub.poll(200))
                } finally {
                    conn.unsubscribe(sub.id)
                }
            } finally {
                conn.close()
            }
        }
    }

    @Test
    fun transportSendAndReceiveOverLoopback() {
        LoopRelay().use { relay ->
            val activistPriv = TestKeys.testPriv("loopback-activist")
            val activistPub = Secp256k1.publicKey(activistPriv)
            val guardianPriv = TestKeys.testPriv("loopback-guardian")
            val guardianPub = Secp256k1.publicKey(guardianPriv)
            val now = 1_800_000_000L
            val rx = NostrTransport(
                guardianPriv,
                NostrTransport.Config(
                    relays = listOf(relay.url()),
                    senders = listOf(activistPub),
                    clockSecs = { now },
                ),
            )
            val q = LinkedBlockingQueue<NostrTransport.Delivery>()
            val handle = rx.startReceive { q.offer(it) }
            try {
                // Wait for the REQ to arrive (the relay stores nothing yet).
                val tx = NostrTransport(activistPriv, NostrTransport.Config(relays = listOf(relay.url()), clockSecs = { now }))
                try {
                    val wire = KengeleMessage.seal(
                        KengeleMessage.Event(KengeleMessage.Kind.PANIC, now, 3, "msaada".toByteArray(Charsets.UTF_8)),
                        activistPriv,
                        guardianPub,
                    )
                    val res = tx.send(wire, guardianPub)
                    assertEquals(1, res.accepted)
                    assertEquals(false, res.queued)
                } finally {
                    tx.close()
                }
                // The relay only answers REQ with stored events; live fan-out
                // is the relay's job in production. Reconnect to backfill.
                handle.stop()
                val q2 = LinkedBlockingQueue<NostrTransport.Delivery>()
                val handle2 = rx.startReceive { q2.offer(it) }
                try {
                    val d = q2.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no delivery in time")
                    assertEquals(KengeleMessage.Kind.PANIC, d.event.kind)
                    assertEquals(3L, d.event.seq)
                    assertArrayEquals(activistPub, d.event.signer)
                    assertNull(q2.poll(200, TimeUnit.MILLISECONDS))
                    assertNull(q.poll(100, TimeUnit.MILLISECONDS))
                } finally {
                    handle2.stop()
                }
            } finally {
                rx.close()
            }
        }
    }
}
