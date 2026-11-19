package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.guardian.confirm.KitOpenVectors
import com.altradits.kengele.guardian.confirm.PublicNote
import com.altradits.kengele.guardian.confirm.TestKeys
import com.altradits.kengele.message.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public-note broadcast against in-memory fake relays: the signed alert
 * note reaches every configured relay as a kind 1 `EVENT`, and only
 * explicit OKs count as accepted. All keys are made up.
 */
class PublishPublicTest {
    private val now = 1_800_000_000L
    private val ownPriv = TestKeys.testPriv("publish-own")

    private fun transport(relays: Map<String, FakeRelay>): NostrTransport = NostrTransport(
        ownPriv,
        NostrTransport.Config(
            relays = relays.keys.toList(),
            minAccepted = 1,
            publishTimeoutMs = 2000,
            clockSecs = { now },
            connect = { url -> relays[url]?.attach(FakeWsChannel()) ?: throw AssertionError("no relay $url") },
        ),
    )

    private fun signedAlert(): NostrEvent {
        val v = KitOpenVectors.noteNamed("alert-no-status")
        val alertKey = KitOpenVectors.kitNamed("kit-2of3").alertKey
        return PublicNote.buildAlert(v.alert, v.epoch, v.confirmed, null, "", now).sign(alertKey)
    }

    @Test
    fun broadcastsToEveryRelay() {
        val relays = mapOf(
            "wss://r1.example" to FakeRelay("wss://r1.example"),
            "wss://r2.example" to FakeRelay("wss://r2.example"),
        )
        val t = transport(relays)
        try {
            val note = signedAlert()
            val res = t.publishPublic(note)
            assertEquals(2, res.accepted)
            assertEquals(2, res.total)
            for ((_, relay) in relays) {
                assertEquals(1, relay.received.size)
                val got = relay.received[0]
                assertEquals(1, got.kind)
                assertEquals(note.id, got.id)
                assertEquals(note.content, got.content)
                assertEquals(note.tags, got.tags)
                assertTrue(got.checkId())
                assertTrue(got.checkSignature())
            }
        } finally {
            t.close()
        }
    }

    @Test
    fun countsOnlyExplicitOks() {
        val relays = mapOf(
            "wss://r1.example" to FakeRelay("wss://r1.example"),
            "wss://r2.example" to FakeRelay("wss://r2.example", accept = false),
        )
        val t = transport(relays)
        try {
            val res = t.publishPublic(signedAlert())
            assertEquals(1, res.accepted)
            assertEquals(2, res.total)
        } finally {
            t.close()
        }
    }
}
