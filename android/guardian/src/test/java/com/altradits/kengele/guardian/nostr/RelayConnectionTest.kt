package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.guardian.confirm.TestKeys
import com.altradits.kengele.message.nostr.GiftWrap
import com.altradits.kengele.message.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The relay connection against scripted channels: OK handling, dispatch, close. */
class RelayConnectionTest {
    private val priv = TestKeys.testPriv("relay-conn")

    private fun event(): NostrEvent = NostrEvent(
        kind = GiftWrap.KIND_GIFT_WRAP,
        createdAt = 1_800_000_000L,
        tags = listOf(listOf("p", "ab".repeat(32))),
        content = "x",
    ).sign(priv)

    @Test
    fun publishAcceptedRejectedAndTimeout() {
        val ch = FakeWsChannel()
        val relay = FakeRelay("wss://ok.example")
        relay.attach(ch)
        RelayConnection(ch).use { conn ->
            val ev = event()
            assertEquals(RelayConnection.PublishOutcome.Accepted, conn.publish(ev, 2000))
            assertEquals(listOf(ev), relay.received)
            // The bytes on the wire are a publish envelope.
            assertTrue(ch.sent.single().startsWith("[\"EVENT\","))
        }
        val ch2 = FakeWsChannel()
        FakeRelay("wss://no.example", accept = false).attach(ch2)
        RelayConnection(ch2).use { conn ->
            assertEquals(RelayConnection.PublishOutcome.Rejected, conn.publish(event(), 2000))
        }
        // Nobody answers: a timeout fails, it never counts as accepted.
        val ch3 = FakeWsChannel()
        RelayConnection(ch3).use { conn ->
            assertEquals(RelayConnection.PublishOutcome.Failed, conn.publish(event(), 100))
        }
        // Sending on a dead channel fails.
        val ch4 = FakeWsChannel()
        val conn4 = RelayConnection(ch4)
        conn4.close()
        assertEquals(RelayConnection.PublishOutcome.Failed, conn4.publish(event(), 100))
    }

    @Test
    fun subscribeDispatchesAndUnsubscribes() {
        val ch = FakeWsChannel()
        val relay = FakeRelay("wss://sub.example")
        relay.attach(ch)
        RelayConnection(ch).use { conn ->
            val sub = conn.subscribe("ab".repeat(32), 1_799_999_000L)
            // The REQ carries kind 1059, the p tag and since.
            val req = ch.sent.single()
            assertTrue(req.startsWith("[\"REQ\",\"${sub.id}\","))
            assertTrue("\"kinds\":[1059]" in req)
            assertTrue("\"#p\":[\"${"ab".repeat(32)}\"]" in req)
            assertTrue("\"since\":1799999000" in req)
            // Push two events; both arrive.
            val ev = event()
            relay.push(ch, ev)
            assertEquals(ev.id, sub.poll(2000)?.id)
            assertNull(sub.poll(100))
            // Unsubscribe ends it and tells the relay.
            conn.unsubscribe(sub.id)
            assertTrue(sub.isEnded)
            assertTrue(ch.sent.last().startsWith("[\"CLOSE\",\"${sub.id}\"]"))
        }
    }

    @Test
    fun closeEndsSubscriptionsAndWaiters() {
        val ch = FakeWsChannel()
        val conn = RelayConnection(ch)
        val sub = conn.subscribe("ab".repeat(32), 0)
        conn.close()
        assertTrue(sub.isEnded)
        assertEquals(RelayConnection.PublishOutcome.Failed, conn.publish(event(), 100))
    }

    private fun RelayConnection.use(block: (RelayConnection) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
