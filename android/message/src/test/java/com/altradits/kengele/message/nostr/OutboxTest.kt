package com.altradits.kengele.message.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Outbox ordering, dedup eviction and backoff steps. */
class OutboxTest {
    private fun item(id: String, queued: Long) = OutboxItem(
        event = NostrEvent(id = id, kind = GiftWrap.KIND_GIFT_WRAP),
        relays = listOf("wss://a.example", "wss://b.example"),
        queuedSecs = queued,
    )

    @Test
    fun oldestFirstAndReplace() {
        val box = MemoryOutbox()
        box.put(item("new", 200))
        box.put(item("old", 100))
        assertEquals(listOf("old", "new"), box.list().map { it.event.id })
        // Same id replaces.
        val upd = item("old", 100).also { it.accepted.add("wss://a.example") }
        box.put(upd)
        assertEquals(2, box.size())
        assertEquals(setOf("wss://a.example"), box.list().first { it.event.id == "old" }.accepted)
        // Copies in and out: mutating the caller side changes nothing.
        upd.accepted.add("wss://b.example")
        assertEquals(setOf("wss://a.example"), box.list().first { it.event.id == "old" }.accepted)
        box.remove("old")
        assertEquals(listOf("new"), box.list().map { it.event.id })
        box.remove("missing")
        assertEquals(1, box.size())
    }

    @Test
    fun seenSetEvicts() {
        val s = SeenSet(2)
        assertFalse(s.seen("a"))
        assertFalse(s.seen("b"))
        assertTrue(s.seen("a"))
        s.seen("c") // evicts a
        assertFalse(s.seen("a"))
        // Re-adding a evicted b in turn.
        assertFalse(s.seen("b"))
    }

    @Test
    fun backoffDoublesToMax() {
        assertEquals(10_000L, NostrBackoff.nextMs(5_000, 300_000))
        assertEquals(300_000L, NostrBackoff.nextMs(200_000, 300_000))
        assertEquals(300_000L, NostrBackoff.nextMs(300_000, 300_000))
        assertEquals(Long.MAX_VALUE, NostrBackoff.nextMs(Long.MAX_VALUE - 1, Long.MAX_VALUE))
    }
}
