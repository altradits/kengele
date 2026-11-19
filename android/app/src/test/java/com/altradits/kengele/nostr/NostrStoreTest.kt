package com.altradits.kengele.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Relay normalization and min-accepted rules for the activist's store. */
class NostrStoreTest {
    @Test
    fun emptyByDefault() {
        val store = InMemoryNostrStore()
        assertTrue(store.relays().isEmpty())
        assertEquals(1, store.minAccepted())
    }

    @Test
    fun setRelaysNormalizesSortsAndDropsInvalid() {
        val store = InMemoryNostrStore()
        store.setRelays(
            listOf(
                "wss://R2.EXAMPLE/",
                "relay.example",
                "  ",
                "not a url !!!",
                "ws://127.0.0.1:8080/",
                "wss://r2.example",
                "localhost:8081",
            ),
        )
        assertEquals(
            listOf(
                "ws://127.0.0.1:8080",
                "ws://localhost:8081",
                "wss://r2.example",
                "wss://relay.example",
            ),
            store.relays(),
        )
    }

    @Test
    fun minAcceptedIsAtLeastOne() {
        val store = InMemoryNostrStore()
        store.setMinAccepted(0)
        assertEquals(1, store.minAccepted())
        store.setMinAccepted(-3)
        assertEquals(1, store.minAccepted())
        store.setMinAccepted(2)
        assertEquals(2, store.minAccepted())
    }
}
