package com.altradits.kengele.message.nostr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/** WebSocket framing, the handshake and message assembly. */
class WsFrameTest {
    @Test
    fun maskedEncodeDecodeShape() {
        val payload = "hello relay".toByteArray(Charsets.UTF_8)
        val mask = byteArrayOf(1, 2, 3, 4)
        val frame = WsFrame.encode(WsFrame.OP_TEXT, payload, mask)
        assertEquals(0x81.toByte(), frame[0]) // FIN + text
        assertEquals((0x80 or payload.size).toByte(), frame[1])
        assertArrayEquals(mask, frame.copyOfRange(2, 6))
        val masked = frame.copyOfRange(6, frame.size)
        val plain = ByteArray(masked.size) { i -> (masked[i].toInt() xor mask[i % 4].toInt()).toByte() }
        assertArrayEquals(payload, plain)
    }

    @Test
    fun extendedLengths() {
        for (size in listOf(125, 126, 300, 65535, 65536, 100000)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val frame = WsFrame.encodeUnmasked(true, WsFrame.OP_TEXT, payload)
            val back = WsFrame.decode(ByteArrayInputStream(frame), 200000)
            assertTrue(back.fin)
            assertEquals(WsFrame.OP_TEXT, back.opcode)
            assertArrayEquals(payload, back.payload)
        }
    }

    @Test
    fun decodeRejectsBadFrames() {
        // Masked inbound (servers must not mask).
        try {
            WsFrame.decode(ByteArrayInputStream(WsFrame.encode(WsFrame.OP_TEXT, "x".toByteArray())))
            fail("accepted masked server frame")
        } catch (e: WsException) {
            // Expected.
        }
        // Bad opcode.
        try {
            WsFrame.decode(ByteArrayInputStream(byteArrayOf(0x83.toByte(), 0)))
            fail("accepted bad opcode")
        } catch (e: WsException) {
            // Expected.
        }
        // Truncated.
        try {
            WsFrame.decode(ByteArrayInputStream(byteArrayOf(0x82.toByte())))
            fail("accepted truncation")
        } catch (e: WsException) {
            // Expected.
        }
        // Oversize.
        try {
            WsFrame.decode(ByteArrayInputStream(WsFrame.encodeUnmasked(true, WsFrame.OP_TEXT, ByteArray(200))), 100)
            fail("accepted oversize frame")
        } catch (e: WsException) {
            // Expected.
        }
    }

    @Test
    fun maskedDecodeAllowedForTestRelays() {
        val payload = "client says hi".toByteArray(Charsets.UTF_8)
        val frame = WsFrame.encode(WsFrame.OP_TEXT, payload, byteArrayOf(9, 8, 7, 6))
        val back = WsFrame.decode(ByteArrayInputStream(frame), 1024, allowMasked = true)
        assertEquals(WsFrame.OP_TEXT, back.opcode)
        assertArrayEquals(payload, back.payload)
    }

    @Test
    fun readerAssemblesFragmentsAndAnswersPing() {
        val pongs = mutableListOf<ByteArray>()
        val bytes = WsFrame.encodeUnmasked(false, WsFrame.OP_TEXT, "hel".toByteArray()) +
            WsFrame.encodeUnmasked(true, WsFrame.OP_PING, "p".toByteArray()) +
            WsFrame.encodeUnmasked(true, WsFrame.OP_CONT, "lo".toByteArray())
        val text = FrameReader(ByteArrayInputStream(bytes)).nextText { pongs.add(it) }
        assertEquals("hello", text)
        assertEquals(1, pongs.size)
        assertArrayEquals("p".toByteArray(), pongs[0])
    }

    @Test
    fun readerStopsOnClose() {
        val bytes = WsFrame.encodeUnmasked(true, WsFrame.OP_CLOSE, byteArrayOf(3, 0xE8.toByte()))
        try {
            FrameReader(ByteArrayInputStream(bytes)).nextText { fail("pong on close") }
            fail("ignored close")
        } catch (e: WsException) {
            // Expected.
        }
    }

    @Test
    fun handshakeVectorAndRequest() {
        // RFC 6455's own example.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsHandshake.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
        val req = WsHandshake.request("relay.example", 443, true, "/", "KEY")
        assertTrue(req.startsWith("GET / HTTP/1.1\r\n"))
        assertTrue("Host: relay.example\r\n" in req)
        assertTrue("Upgrade: websocket\r\n" in req)
        assertTrue("Sec-WebSocket-Key: KEY\r\n" in req)
        assertTrue(req.endsWith("\r\n\r\n"))
        val custom = WsHandshake.request("127.0.0.1", 8080, false, "/path?q=1", "K")
        assertTrue("Host: 127.0.0.1:8080\r\n" in custom)
        assertTrue(custom.startsWith("GET /path?q=1 HTTP/1.1\r\n"))
    }

    @Test
    fun handshakeResponseChecked() {
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val good = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
        WsHandshake.checkResponse(ByteArrayInputStream(good.toByteArray(Charsets.US_ASCII)), key)
        // No read-ahead: frame bytes after the headers stay in the stream.
        val extra = byteArrayOf(9, 9, 9)
        val input = ByteArrayInputStream(good.toByteArray(Charsets.US_ASCII) + extra)
        WsHandshake.checkResponse(input, key)
        assertArrayEquals(extra, input.readBytes())
        // Rejected and mismatched handshakes.
        for (bad in listOf(
            "HTTP/1.1 404 Not Found\r\n\r\n",
            "HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: wrong\r\n\r\n",
            "HTTP/1.1 101 Switching Protocols\r\n\r\n",
            "",
        )) {
            try {
                WsHandshake.checkResponse(ByteArrayInputStream(bad.toByteArray(Charsets.US_ASCII)), key)
                fail("accepted $bad")
            } catch (e: WsException) {
                // Expected.
            }
        }
    }
}
