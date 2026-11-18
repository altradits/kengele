package com.altradits.kengele.message.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Relay envelopes: exact bytes out, lenient parsing in. */
class RelayMessagesTest {
    @Test
    fun publishBytes() {
        val ev = NostrEvent.parseJson(NostrVectors.str("wrap_json"))
        assertEquals("[\"EVENT\",${ev.toJson()}]", RelayMessages.publish(ev))
    }

    @Test
    fun reqBytes() {
        assertEquals(
            "[\"REQ\",\"sub1\",{\"kinds\":[1059],\"#p\":[\"abc\"],\"since\":123}]",
            RelayMessages.req("sub1", listOf(1059), "abc", 123),
        )
    }

    @Test
    fun closeBytes() {
        assertEquals("[\"CLOSE\",\"sub1\"]", RelayMessages.close("sub1"))
    }

    @Test
    fun parseReplies() {
        val ok = RelayMessages.parse("[\"OK\",\"id1\",true,\"\"]") as RelayMessages.Incoming.Ok
        assertEquals("id1", ok.eventId)
        assertTrue(ok.ok)
        val no = RelayMessages.parse("[\"OK\",\"id2\",false,\"blocked: test\"]") as RelayMessages.Incoming.Ok
        assertEquals("id2", no.eventId)
        assertTrue(!no.ok)
        assertEquals("blocked: test", no.reason)

        val gw = NostrVectors.str("wrap_json")
        val event = RelayMessages.parse("[\"EVENT\",\"s1\",$gw]") as RelayMessages.Incoming.RelayEvent
        assertEquals("s1", event.subId)
        assertEquals(NostrEvent.parseJson(gw), event.event)

        val pub = RelayMessages.parse("[\"EVENT\",$gw]") as RelayMessages.Incoming.Publish
        assertEquals(NostrEvent.parseJson(gw), pub.event)

        val eose = RelayMessages.parse("[\"EOSE\",\"s1\"]") as RelayMessages.Incoming.Eose
        assertEquals("s1", eose.subId)
        val closed = RelayMessages.parse("[\"CLOSED\",\"s1\",\"shutting down\"]") as RelayMessages.Incoming.Closed
        assertEquals("s1", closed.subId)
        val notice = RelayMessages.parse("[\"NOTICE\",\"hi\"]") as RelayMessages.Incoming.Notice
        assertEquals("hi", notice.text)
    }

    @Test
    fun malformedIsUnknownNeverThrows() {
        for (bad in listOf(
            "",
            "not json",
            "[\"OK\"]",
            "[\"OK\",\"id\"]",
            "[\"OK\",\"id\",\"yes\",\"\"]",
            "[\"EVENT\",\"s1\"]",
            "[\"EVENT\",\"s1\",{}]",
            "[\"EVENT\",\"s1\",[],\"extra\"]",
            "[\"EVENT\"]",
            "[\"EOSE\"]",
            "[\"CLOSED\"]",
            "[\"AUTH\",\"x\"]",
            "{\"kind\":1}",
            "[]",
        )) {
            assertTrue("threw or misparsed: $bad", RelayMessages.parse(bad) is RelayMessages.Incoming.Unknown)
        }
    }
}
