package com.altradits.kengele.message.nostr

import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A WebSocket protocol failure: bad framing, oversize message, peer closed. */
class WsException(message: String) : Exception(message)

/**
 * Hand-rolled WebSocket client framing (RFC 6455) over a plain
 * `java.net.Socket`, so the transport needs no external dependency. Only what
 * a Nostr client needs: the opening handshake, masked text frames, ping/pong
 * and close.
 */
object WsFrame {
    const val OP_CONT = 0
    const val OP_TEXT = 1
    const val OP_BIN = 2
    const val OP_CLOSE = 8
    const val OP_PING = 9
    const val OP_PONG = 10

    /** Largest single message accepted, like the Go transport's relay limit. */
    const val MAX_MESSAGE = 256 * 1024

    const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    data class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private val random = SecureRandom()

    /** Encodes one client frame, masked as RFC 6455 requires. */
    fun encode(opcode: Int, payload: ByteArray, maskKey: ByteArray? = null): ByteArray {
        val mask = maskKey ?: ByteArray(4).also(random::nextBytes)
        require(mask.size == 4) { "mask key must be 4 bytes" }
        val head = if (payload.size < 126) 2 else if (payload.size < 65536) 4 else 10
        val out = ByteArray(head + 4 + payload.size)
        out[0] = (0x80 or opcode).toByte()
        var o = 1
        when {
            payload.size < 126 -> out[o++] = (0x80 or payload.size).toByte()
            payload.size < 65536 -> {
                out[o++] = (0x80 or 126).toByte()
                out[o++] = ((payload.size ushr 8) and 0xFF).toByte()
                out[o++] = (payload.size and 0xFF).toByte()
            }
            else -> {
                out[o++] = (0x80 or 127).toByte()
                // 64-bit length; payloads here never exceed Int range.
                val n = payload.size.toLong()
                for (i in 7 downTo 0) out[o++] = ((n ushr (8 * i)) and 0xFF).toByte()
            }
        }
        mask.copyInto(out, o)
        o += 4
        for (i in payload.indices) out[o++] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return out
    }

    /** Encodes one server frame (unmasked), for tests and fakes. */
    fun encodeUnmasked(fin: Boolean, opcode: Int, payload: ByteArray): ByteArray {
        val head = if (payload.size < 126) 2 else if (payload.size < 65536) 4 else 10
        val out = ByteArray(head + payload.size)
        out[0] = ((if (fin) 0x80 else 0) or opcode).toByte()
        var o = 1
        when {
            payload.size < 126 -> out[o++] = payload.size.toByte()
            payload.size < 65536 -> {
                out[o++] = 126
                out[o++] = ((payload.size ushr 8) and 0xFF).toByte()
                out[o++] = (payload.size and 0xFF).toByte()
            }
            else -> {
                out[o++] = 127
                val n = payload.size.toLong()
                for (i in 7 downTo 0) out[o++] = ((n ushr (8 * i)) and 0xFF).toByte()
            }
        }
        payload.copyInto(out, o)
        return out
    }

    /**
     * Reads one frame from a server. Servers must not mask; a masked frame is
     * a protocol error unless [allowMasked] (test relays reading client
     * frames). Throws [WsException] on EOF or framing errors.
     */
    fun decode(input: InputStream, maxPayload: Int = MAX_MESSAGE, allowMasked: Boolean = false): Frame {
        val b0 = readByte(input)
        val b1 = readByte(input)
        val fin = b0 and 0x80 != 0
        if (b0 and 0x70 != 0) throw WsException("extensions not supported")
        val opcode = b0 and 0x0F
        if (opcode !in setOf(OP_CONT, OP_TEXT, OP_BIN, OP_CLOSE, OP_PING, OP_PONG)) {
            throw WsException("bad opcode $opcode")
        }
        val masked = b1 and 0x80 != 0
        var len = (b1 and 0x7F).toLong()
        when (len) {
            126L -> len = (readByte(input).toLong() shl 8) or readByte(input).toLong()
            127L -> {
                len = 0
                repeat(8) { len = (len shl 8) or readByte(input).toLong() }
            }
        }
        if (opcode >= OP_CLOSE && (len > 125 || !fin)) throw WsException("bad control frame")
        if (len > maxPayload) throw WsException("frame too large")
        if (masked && !allowMasked) throw WsException("server must not mask")
        val mask = if (masked) readFully(input, 4) else null
        val payload = readFully(input, len.toInt())
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(fin, opcode, payload)
    }

    private fun readByte(input: InputStream): Int {
        val b = try {
            input.read()
        } catch (e: EOFException) {
            throw WsException("unexpected EOF")
        }
        if (b < 0) throw WsException("unexpected EOF")
        return b
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var o = 0
        while (o < n) {
            val r = try {
                input.read(out, o, n - o)
            } catch (e: EOFException) {
                throw WsException("unexpected EOF")
            }
            if (r < 0) throw WsException("unexpected EOF")
            o += r
        }
        return out
    }
}

/**
 * Assembles complete text messages from [WsFrame.decode], answering pings and
 * ignoring pongs. Throws [WsException] when the peer closes or breaks the
 * protocol.
 */
class FrameReader(private val input: InputStream, private val maxSize: Int = WsFrame.MAX_MESSAGE) {
    /**
     * Reads the next complete text message, calling [sendPong] for every ping
     * on the way (control frames may arrive between fragments).
     */
    fun nextText(sendPong: (ByteArray) -> Unit): String {
        var buf: ByteArray? = null
        while (true) {
            val f = WsFrame.decode(input, maxSize)
            when (f.opcode) {
                WsFrame.OP_PING -> sendPong(f.payload)
                WsFrame.OP_PONG -> Unit
                WsFrame.OP_CLOSE -> throw WsException("peer closed")
                WsFrame.OP_TEXT, WsFrame.OP_BIN -> {
                    if (buf != null) throw WsException("new message before fragments ended")
                    if (f.fin) return f.payload.toString(Charsets.UTF_8)
                    buf = f.payload
                }
                WsFrame.OP_CONT -> {
                    val cur = buf ?: throw WsException("stray continuation")
                    if (cur.size + f.payload.size > maxSize) throw WsException("message too large")
                    buf = cur + f.payload
                    if (f.fin) return buf.toString(Charsets.UTF_8)
                }
            }
        }
    }
}

/** The HTTP opening handshake around the socket. */
object WsHandshake {
    /** Fresh random Sec-WebSocket-Key (base64 of 16 random bytes). */
    fun clientKey(): String = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))

    /** The accept value the server must echo for [key]. */
    fun acceptFor(key: String): String {
        val hash = MessageDigest.getInstance("SHA-1").digest((key + WsFrame.GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(hash)
    }

    /** The GET request that opens the connection. */
    fun request(host: String, port: Int, tls: Boolean, path: String, key: String): String {
        val default = if (tls) 443 else 80
        val hostLine = if (port == default) host else "$host:$port"
        return "GET $path HTTP/1.1\r\n" +
            "Host: $hostLine\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "\r\n"
    }

    /**
     * Reads the server's response from [input] and throws [WsException]
     * unless it is `101` with the right accept value. Reads byte by byte so
     * no frame bytes are ever swallowed.
     */
    fun checkResponse(input: InputStream, key: String) {
        val text = readHead(input).toString(Charsets.US_ASCII)
        val lines = text.split("\r\n")
        val status = lines.firstOrNull() ?: ""
        if (!status.startsWith("HTTP/1.1 101") && !status.startsWith("HTTP/1.0 101")) {
            throw WsException("handshake rejected: $status")
        }
        val want = acceptFor(key)
        for (line in lines.drop(1)) {
            val name = line.substringBefore(':').trim().lowercase()
            if (name == "sec-websocket-accept" && line.substringAfter(':').trim() == want) return
        }
        throw WsException("handshake accept mismatch")
    }

    /**
     * Reads through the end of the HTTP headers (`\r\n\r\n` or a bare `\n\n`)
     * and returns exactly those bytes.
     */
    fun readHead(input: InputStream): ByteArray {
        val buf = ByteArray(8192)
        var n = 0
        while (n < buf.size) {
            val b = input.read()
            if (b < 0) throw WsException("handshake EOF")
            buf[n++] = b.toByte()
            if (n >= 4 && buf[n - 4] == '\r'.code.toByte() && buf[n - 3] == '\n'.code.toByte() &&
                buf[n - 2] == '\r'.code.toByte() && buf[n - 1] == '\n'.code.toByte()
            ) {
                return buf.copyOf(n)
            }
            if (n >= 2 && buf[n - 2] == '\n'.code.toByte() && buf[n - 1] == '\n'.code.toByte()) {
                return buf.copyOf(n)
            }
        }
        throw WsException("handshake headers too large")
    }
}
