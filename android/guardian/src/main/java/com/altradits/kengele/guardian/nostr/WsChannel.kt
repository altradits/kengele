package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.message.nostr.FrameReader
import com.altradits.kengele.message.nostr.RelayUrl
import com.altradits.kengele.message.nostr.WsException
import com.altradits.kengele.message.nostr.WsFrame
import com.altradits.kengele.message.nostr.WsHandshake
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One open WebSocket conversation with a relay: send text, receive complete
 * text messages. The socket implementation is [SocketWsChannel]; tests use
 * fakes.
 */
interface WsChannel {
    /** Sends one text message; throws [WsException] when broken. */
    fun sendText(text: String)

    /** Reads the next complete text message, blocking; throws [WsException] when closed or broken. */
    fun nextText(): String

    /** Closes the channel; unblocks a [nextText] call. */
    fun close()
}

/**
 * A [WsChannel] over a plain `java.net.Socket` (or TLS for `wss://`), with a
 * hand-rolled opening handshake and frame codec. No `java.net.http` (absent
 * on API 26) and no external dependency.
 */
class SocketWsChannel(url: String, connectTimeoutMs: Int = 10_000) : WsChannel {
    private val socket: Socket
    private val input: InputStream
    private val output: OutputStream

    init {
        val normalized = RelayUrl.normalize(url)
        if (normalized.isEmpty()) throw WsException("bad relay URL")
        val p = RelayUrl.parse(normalized)
        try {
            var s: Socket = Socket()
            s.connect(InetSocketAddress(p.host, p.port), connectTimeoutMs)
            if (p.tls) {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, p.host, p.port, true) as SSLSocket
                tls.soTimeout = connectTimeoutMs
                tls.startHandshake()
                s = tls
            }
            s.soTimeout = 0 // reads block until close() drops the socket
            val key = WsHandshake.clientKey()
            val out = s.getOutputStream()
            out.write(WsHandshake.request(p.host, p.port, p.tls, p.path, key).toByteArray(Charsets.US_ASCII))
            out.flush()
            WsHandshake.checkResponse(s.getInputStream(), key)
            socket = s
            input = s.getInputStream()
            output = out
        } catch (e: WsException) {
            throw e
        } catch (e: IOException) {
            throw WsException("connect failed: ${e.message}")
        }
    }

    override fun sendText(text: String) = delegate.sendText(text)

    override fun nextText(): String = delegate.nextText()

    override fun close() {
        delegate.close()
        try {
            socket.close()
        } catch (e: IOException) {
            // Already gone.
        }
    }

    private val delegate: WsChannel = StreamWsChannel(input, output)
}

/**
 * A [WsChannel] over already-connected streams: the frames after the
 * handshake. [SocketWsChannel] delegates to it once the socket is open;
 * tests wire it to piped streams.
 */
class StreamWsChannel(private val input: InputStream, private val output: OutputStream) : WsChannel {
    private val reader = FrameReader(input)
    private val sendLock = Any()

    @Volatile
    private var closed = false

    override fun sendText(text: String) {
        synchronized(sendLock) {
            if (closed) throw WsException("closed")
            try {
                output.write(WsFrame.encode(WsFrame.OP_TEXT, text.toByteArray(Charsets.UTF_8)))
                output.flush()
            } catch (e: IOException) {
                throw WsException("send failed")
            }
        }
    }

    override fun nextText(): String {
        try {
            return reader.nextText { pong ->
                synchronized(sendLock) {
                    if (!closed) {
                        output.write(WsFrame.encode(WsFrame.OP_PONG, pong))
                        output.flush()
                    }
                }
            }
        } catch (e: WsException) {
            throw e
        } catch (e: IOException) {
            throw WsException("receive failed")
        }
    }

    override fun close() {
        closed = true
    }
}
