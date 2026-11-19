package com.altradits.kengele.message.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Relay URL normalization and parsing. */
class RelayUrlTest {
    @Test
    fun normalize() {
        assertEquals("wss://relay.example", RelayUrl.normalize("wss://relay.example"))
        assertEquals("wss://relay.example", RelayUrl.normalize("  wss://relay.example/ "))
        assertEquals("wss://relay.example", RelayUrl.normalize("relay.example"))
        assertEquals("wss://relay.example/path", RelayUrl.normalize("https://relay.example/path//"))
        assertEquals("ws://relay.example", RelayUrl.normalize("http://relay.example"))
        assertEquals("ws://127.0.0.1:8080", RelayUrl.normalize("127.0.0.1:8080"))
        assertEquals("ws://localhost:8080", RelayUrl.normalize("localhost:8080"))
        assertEquals("wss://relay.example:443", RelayUrl.normalize("wss://relay.example:443"))
        assertEquals("wss://relay.example:8443/x?q=1", RelayUrl.normalize("WSS://Relay.Example:8443/x?q=1"))
        assertEquals("wss://relay.example", RelayUrl.normalize("wss://relay.example#frag"))
    }

    @Test
    fun normalizeRejects() {
        for (bad in listOf("", "   ", "://x", "ftp://x.example", "wss://", "wss://user@relay.example", "wss://relay.example/x y", "ws://relay.example:99999")) {
            assertEquals("accepted $bad", "", RelayUrl.normalize(bad))
        }
    }

    @Test
    fun normalizeAllDedupsAndDrops() {
        assertEquals(
            listOf("wss://a.example", "wss://b.example"),
            RelayUrl.normalizeAll(listOf("wss://a.example", "wss://a.example/", "bogus bulb", "wss://b.example")),
        )
    }

    @Test
    fun parse() {
        val tls = RelayUrl.parse("wss://relay.example")
        assertEquals("relay.example", tls.host)
        assertEquals(443, tls.port)
        assertEquals(true, tls.tls)
        assertEquals("/", tls.path)
        val plain = RelayUrl.parse("ws://127.0.0.1:8080/r?q=1")
        assertEquals(8080, plain.port)
        assertEquals(false, plain.tls)
        assertEquals("/r?q=1", plain.path)
        try {
            RelayUrl.parse("wss://")
            fail("parsed bad URL")
        } catch (e: NostrException) {
            // Expected.
        }
    }
}
