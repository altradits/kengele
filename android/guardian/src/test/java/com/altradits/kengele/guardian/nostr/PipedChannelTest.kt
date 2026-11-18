package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.guardian.confirm.TestKeys
import com.altradits.kengele.message.nostr.GiftWrap
import com.altradits.kengele.message.nostr.NostrEvent
import com.altradits.kengele.message.nostr.NostrJson
import com.altradits.kengele.message.nostr.RelayMessages
import com.altradits.kengele.message.nostr.WsException
import com.altradits.kengele.message.nostr.WsFrame
import com.altradits.kengele.message.nostr.WsHandshake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The full client stack — channel framing, handshake bytes, connection,
 * envelopes — against a fake relay speaking real WebSocket frames over
 * piped streams. No sockets, so it runs in the sandbox; the socket layer
 * itself is covered by [LoopbackNostrTest] on an unsandboxed run.
 */
class PipedChannelTest {
    /** A fake relay speaking frames on one connection's streams. */
    private class PipeRelay(val input: InputStream, val output: OutputStream) {
        val stored: MutableList<NostrEvent> = Collections.synchronizedList(mutableListOf())
        val subs: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val pongs: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val done = CountDownLatch(1)

        @Volatile
        var reject = false

        fun run() {
            try {
                // The opening handshake.
                val head = WsHandshake.readHead(input).toString(Charsets.US_ASCII)
                val key = head.split("\r\n").first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
                val reply = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${WsHandshake.acceptFor(key)}\r\n\r\n"
                output.write(reply.toByteArray(Charsets.US_ASCII))
                output.flush()
                // Frames.
                while (true) {
                    val f = WsFrame.decode(input, allowMasked = true)
                    when (f.opcode) {
                        WsFrame.OP_TEXT -> onText(f.payload.toString(Charsets.UTF_8))
                        WsFrame.OP_PONG -> pongs.add(f.payload)
                        WsFrame.OP_CLOSE -> return
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                // Client went away.
            } finally {
                done.countDown()
            }
        }

        private fun onText(text: String) {
            when (val m = RelayMessages.parse(text)) {
                is RelayMessages.Incoming.Publish -> {
                    val ok = !reject && m.event.checkSignature()
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

        fun send(text: String) {
            output.write(WsFrame.encodeUnmasked(true, WsFrame.OP_TEXT, text.toByteArray(Charsets.UTF_8)))
            output.flush()
        }

        fun ping(payload: ByteArray) {
            output.write(WsFrame.encodeUnmasked(true, WsFrame.OP_PING, payload))
            output.flush()
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
    }

    private fun pipe(): Pair<PipedInputStream, PipedOutputStream> {
        val out = PipedOutputStream()
        val inp = PipedInputStream(out, 256 * 1024)
        return Pair(inp, out)
    }

    private fun event(): NostrEvent = NostrEvent(
        kind = GiftWrap.KIND_GIFT_WRAP,
        createdAt = 1_800_000_000L,
        tags = listOf(listOf("p", "cd".repeat(32))),
        content = "frame-bytes".repeat(100), // forces extended-length frames
    ).sign(TestKeys.testPriv("piped-signer"))

    @Test
    fun publishAndBackfillOverFrames() {
        val (clientIn, serverOut) = pipe()
        val (serverIn, clientOut) = pipe()
        val relay = PipeRelay(serverIn, serverOut)
        val server = Thread { relay.run() }
        server.isDaemon = true
        server.start()
        // The client half performs the handshake itself: replay it on the streams.
        val key = WsHandshake.clientKey()
        clientOut.write(WsHandshake.request("relay.example", 443, true, "/", key).toByteArray(Charsets.US_ASCII))
        clientOut.flush()
        WsHandshake.checkResponse(clientIn, key)
        val conn = RelayConnection(StreamWsChannel(clientIn, clientOut))
        try {
            val ev = event()
            assertEquals(RelayConnection.PublishOutcome.Accepted, conn.publish(ev, 5000))
            assertEquals(listOf(ev), relay.stored)
            val sub = conn.subscribe("cd".repeat(32), 0)
            try {
                assertEquals(ev.id, sub.poll(5000)?.id)
                assertNull(sub.poll(200))
            } finally {
                conn.unsubscribe(sub.id)
            }
        } finally {
            conn.close()
            clientOut.close()
            clientIn.close()
            assertTrue(relay.done.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun pingAnsweredAndRefusalHonored() {
        val (clientIn, serverOut) = pipe()
        val (serverIn, clientOut) = pipe()
        val relay = PipeRelay(serverIn, serverOut)
        relay.reject = true
        val server = Thread { relay.run() }
        server.isDaemon = true
        server.start()
        val key = WsHandshake.clientKey()
        clientOut.write(WsHandshake.request("relay.example", 443, true, "/", key).toByteArray(Charsets.US_ASCII))
        clientOut.flush()
        WsHandshake.checkResponse(clientIn, key)
        val conn = RelayConnection(StreamWsChannel(clientIn, clientOut))
        try {
            relay.ping("are-you-there".toByteArray(Charsets.UTF_8))
            val end = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < end && relay.pongs.isEmpty()) Thread.sleep(10)
            assertEquals(1, relay.pongs.size)
            assertEquals("are-you-there", relay.pongs.single().toString(Charsets.UTF_8))
            assertEquals(RelayConnection.PublishOutcome.Rejected, conn.publish(event(), 5000))
            assertTrue(relay.stored.isEmpty())
        } finally {
            conn.close()
            clientOut.close()
            clientIn.close()
            assertTrue(relay.done.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun badHandshakeRejected() {
        // A server that answers 404: the client refuses to speak frames.
        val bytes = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        try {
            WsHandshake.checkResponse(bytes.inputStream(), WsHandshake.clientKey())
            throw AssertionError("accepted a 404")
        } catch (e: WsException) {
            // Expected.
        }
    }
}
