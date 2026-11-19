package com.altradits.kengele.nostr

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Vectors
import com.altradits.kengele.message.nostr.GiftWrap
import com.altradits.kengele.message.nostr.MemoryOutbox
import com.altradits.kengele.message.nostr.NostrEvent
import com.altradits.kengele.message.nostr.NostrException
import com.altradits.kengele.sms.SmsScheduler
import com.altradits.kengele.sms.SmsTaskHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * NostrCheckInSender quorum, outbox, backoff and failure surfacing, against
 * scriptable fake relays. All keys are the made-up shared vectors; all
 * relay addresses are fictional example.com names.
 */
class NostrCheckInSenderTest {
    private val activistPriv = Vectors.hex("activist_priv_hex")
    private val activistPub = Vectors.hex("activist_pub_hex")
    private val guardianPriv = Vectors.hex("guardian_priv_hex")
    private val guardianPub = Vectors.hex("guardian_pub_hex")
    private val created = Vectors.num("event_created_unix")
    private val now = created + 60

    private val relayA = "wss://r1.example"
    private val relayB = "wss://r2.example"

    private class FakePublisher(var outcome: RelayPublishOutcome = RelayPublishOutcome.ACCEPTED) : RelayPublisher {
        data class Call(val event: NostrEvent, val relay: String)

        val calls = mutableListOf<Call>()
        var script: MutableList<RelayPublishOutcome> = mutableListOf()

        /** Per-relay outcomes; a scripted call index wins over the map. */
        val perRelay = mutableMapOf<String, RelayPublishOutcome>()

        var throwOn: Set<String> = emptySet()

        override fun publish(event: NostrEvent, relayUrl: String, timeoutMs: Long): RelayPublishOutcome {
            calls.add(Call(event, relayUrl))
            if (relayUrl in throwOn) throw RuntimeException("fake publisher blew up")
            if (script.isNotEmpty()) return script.removeAt(0)
            return perRelay[relayUrl] ?: outcome
        }

        fun callsTo(relay: String): List<Call> = calls.filter { it.relay == relay }
    }

    private class FakeScheduler : SmsScheduler {
        class Task(val delay: Long, val run: () -> Unit)

        val tasks = mutableListOf<Task>()

        override fun schedule(delayMillis: Long, task: () -> Unit): SmsTaskHandle {
            val t = Task(delayMillis, task)
            tasks.add(t)
            return SmsTaskHandle { tasks.remove(t) }
        }

        fun runDue() {
            val due = tasks.toList()
            tasks.clear()
            due.forEach { it.run() }
        }
    }

    private fun sealText(
        kind: KengeleMessage.Kind = KengeleMessage.Kind.CHECK_IN,
        seq: Long = 1,
        body: String = "Kisumu",
        to: ByteArray = guardianPub,
    ): String {
        val event = KengeleMessage.Event(kind, created, seq, body.toByteArray(Charsets.UTF_8))
        return KengeleMessage.encodeText(KengeleMessage.seal(event, activistPriv, to))
    }

    private fun openAsGuardian(event: NostrEvent, priv: ByteArray = guardianPriv): KengeleMessage.Event {
        val unwrapped = GiftWrap.unwrap(event, priv)
        return KengeleMessage.open(unwrapped.wire, priv, listOf(activistPub), now)
    }

    @Test
    fun quorumMetSendsOneWrapPerGuardianPerRelay() {
        val publisher = FakePublisher(RelayPublishOutcome.ACCEPTED)
        val outbox = MemoryOutbox()
        val scheduler = FakeScheduler()
        val statuses = mutableListOf<NostrSendStatus>()
        val secondPriv = Vectors.testPriv("nostr-second-guardian")
        val secondPub = KengeleMessage.publicKey(secondPriv)
        val sender = NostrCheckInSender(
            ownPriv = activistPriv,
            recipients = listOf(guardianPub, secondPub),
            relays = listOf(relayA, relayB),
            minAccepted = 1,
            publish = publisher,
            outbox = outbox,
            scheduler = scheduler,
            clockSecs = { now },
            background = { it() },
            onStatus = statuses::add,
        )
        val texts = listOf(sealText(seq = 1), sealText(seq = 1, to = secondPub))
        sender.send(texts)

        // Each guardian's text became its own gift wrap on both relays.
        assertEquals(4, publisher.calls.size)
        assertEquals(2, publisher.callsTo(relayA).size)
        assertEquals(2, publisher.callsTo(relayB).size)
        for (call in publisher.calls) {
            assertEquals(GiftWrap.KIND_GIFT_WRAP, call.event.kind)
        }
        // Each wrap opens for its own guardian only, as the same check-in.
        val first = openAsGuardian(publisher.callsTo(relayA)[0].event)
        assertEquals(KengeleMessage.Kind.CHECK_IN, first.kind)
        assertEquals("Kisumu", String(first.body, Charsets.UTF_8))
        val second = openAsGuardian(publisher.callsTo(relayA)[1].event, secondPriv)
        assertEquals(KengeleMessage.Kind.CHECK_IN, second.kind)
        try {
            openAsGuardian(publisher.callsTo(relayA)[1].event, guardianPriv)
            fail("second guardian's wrap opened with the first guardian's key")
        } catch (e: NostrException) {
            // Expected: not addressed to this key.
        }

        assertEquals(listOf(NostrSendStatus(sent = 2, total = 2, queued = 0, accepted = 2, relays = 2, dropped = 0)), statuses)
        assertEquals(0, outbox.size())
        assertEquals(0, sender.queuedCount())
        assertTrue("no retry when everything sent", scheduler.tasks.isEmpty())
    }

    @Test
    fun belowQuorumQueuesAndRetriesWithBackoff() {
        val publisher = FakePublisher(RelayPublishOutcome.FAILED)
        val outbox = MemoryOutbox()
        val scheduler = FakeScheduler()
        val statuses = mutableListOf<NostrSendStatus>()
        val sender = NostrCheckInSender(
            ownPriv = activistPriv,
            recipients = listOf(guardianPub),
            relays = listOf(relayA, relayB),
            minAccepted = 2,
            publish = publisher,
            outbox = outbox,
            scheduler = scheduler,
            retryMinMs = 1_000,
            retryMaxMs = 4_000,
            clockSecs = { now },
            background = { it() },
            onStatus = statuses::add,
        )
        sender.send(listOf(sealText(kind = KengeleMessage.Kind.PANIC)))

        assertEquals(listOf(NostrSendStatus(sent = 0, total = 1, queued = 1, accepted = 0, relays = 2, dropped = 0)), statuses)
        assertEquals(1, outbox.size())
        assertEquals(1, sender.queuedCount())

        // Retries republish the same gift wrap with doubling delays, capped.
        val firstId = outbox.list().single().event.id
        val delays = mutableListOf<Long>()
        repeat(4) {
            assertEquals(1, scheduler.tasks.size)
            delays.add(scheduler.tasks.single().delay)
            scheduler.runDue()
            assertEquals("retry wrapped a new event", firstId, outbox.list().single().event.id)
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 4_000L), delays)
        assertEquals(1, sender.queuedCount())

        // Once both relays accept, the retry clears the outbox and stops.
        publisher.outcome = RelayPublishOutcome.ACCEPTED
        scheduler.runDue()
        assertEquals(0, outbox.size())
        assertEquals(0, sender.queuedCount())
        assertTrue("retry kept running after recovery", scheduler.tasks.isEmpty())
    }

    @Test
    fun flushSkipsAcceptedRelaysAndKeepsQuorum() {
        val publisher = FakePublisher()
        publisher.perRelay[relayA] = RelayPublishOutcome.ACCEPTED
        publisher.perRelay[relayB] = RelayPublishOutcome.FAILED
        val outbox = MemoryOutbox()
        val scheduler = FakeScheduler()
        val sender = NostrCheckInSender(
            ownPriv = activistPriv,
            recipients = listOf(guardianPub),
            relays = listOf(relayA, relayB),
            minAccepted = 2,
            publish = publisher,
            outbox = outbox,
            scheduler = scheduler,
            clockSecs = { now },
            background = { it() },
        )
        sender.send(listOf(sealText()))
        assertEquals(1, outbox.size())
        assertEquals(setOf(relayA), outbox.list().single().accepted)
        assertEquals(1, publisher.callsTo(relayA).size)

        publisher.perRelay[relayB] = RelayPublishOutcome.ACCEPTED
        assertEquals(0, sender.flush())
        assertEquals(0, outbox.size())
        assertEquals("flush republished to the relay that accepted", 1, publisher.callsTo(relayA).size)
        assertEquals(2, publisher.callsTo(relayB).size)
    }

    @Test
    fun rejectedRelaysAreRetriedButNeverCount() {
        val publisher = FakePublisher(RelayPublishOutcome.REJECTED)
        val outbox = MemoryOutbox()
        val scheduler = FakeScheduler()
        val statuses = mutableListOf<NostrSendStatus>()
        val sender = NostrCheckInSender(
            ownPriv = activistPriv,
            recipients = listOf(guardianPub),
            relays = listOf(relayA),
            minAccepted = 1,
            publish = publisher,
            outbox = outbox,
            scheduler = scheduler,
            clockSecs = { now },
            background = { it() },
            onStatus = statuses::add,
        )
        sender.send(listOf(sealText()))
        assertEquals(0, statuses.single().sent)
        assertEquals(1, statuses.single().queued)
        assertEquals(1, outbox.size())

        // A refusal is not an accept, but the relay is tried again.
        scheduler.runDue()
        assertEquals(2, publisher.callsTo(relayA).size)
        assertEquals(1, outbox.size())
    }

    @Test
    fun partialQuorumAcrossGuardiansReportsWorstCase() {
        val publisher = FakePublisher(RelayPublishOutcome.ACCEPTED)
        // First guardian's wrap: both relays accept. Second: only relay A.
        publisher.script.addAll(
            listOf(
                RelayPublishOutcome.ACCEPTED, RelayPublishOutcome.ACCEPTED,
                RelayPublishOutcome.ACCEPTED, RelayPublishOutcome.FAILED,
            ),
        )
        val statuses = mutableListOf<NostrSendStatus>()
        val secondPub = KengeleMessage.publicKey(Vectors.testPriv("nostr-second-guardian"))
        val sender = NostrCheckInSender(
            ownPriv = activistPriv,
            recipients = listOf(guardianPub, secondPub),
            relays = listOf(relayA, relayB),
            minAccepted = 1,
            publish = publisher,
            outbox = MemoryOutbox(),
            scheduler = FakeScheduler(),
            clockSecs = { now },
            background = { it() },
            onStatus = statuses::add,
        )
        sender.send(listOf(sealText(seq = 1), sealText(seq = 1, to = secondPub)))
        // Both met quorum, but "sent to n/m" reports the weaker event's 1/2.
        assertEquals(listOf(NostrSendStatus(sent = 2, total = 2, queued = 0, accepted = 1, relays = 2, dropped = 0)), statuses)
    }

    @Test
    fun malformedTextsAndMissingGuardiansAreDropped() {
        val publisher = FakePublisher(RelayPublishOutcome.ACCEPTED)
        val statuses = mutableListOf<NostrSendStatus>()
        val secondPriv = Vectors.testPriv("nostr-second-guardian")
        val secondPub = KengeleMessage.publicKey(secondPriv)
        val sender = NostrCheckInSender(
            ownPriv = activistPriv,
            recipients = listOf(guardianPub, secondPub),
            relays = listOf(relayA),
            publish = publisher,
            outbox = MemoryOutbox(),
            scheduler = FakeScheduler(),
            clockSecs = { now },
            background = { it() },
            onStatus = statuses::add,
        )
        val good = sealText(seq = 2, to = secondPub)
        val badChar = good.substring(0, 303) + "!"
        // Texts pair with guardians by position: the malformed text for
        // guardian 1 is dropped, the good text for guardian 2 sends, and
        // the extra text with no guardian is dropped rather than
        // misrouted to someone else's guardian.
        sender.send(listOf(badChar, good, "extra text with no guardian"))
        assertEquals(1, publisher.calls.size)
        assertEquals(good, KengeleMessage.encodeText(GiftWrap.unwrap(publisher.calls.single().event, secondPriv).wire))
        assertEquals(listOf(NostrSendStatus(sent = 1, total = 1, queued = 0, accepted = 1, relays = 1, dropped = 2)), statuses)
    }

    @Test
    fun publisherThrowingCountsAsFailed() {
        val publisher = FakePublisher(RelayPublishOutcome.ACCEPTED)
        publisher.throwOn = setOf(relayA)
        val outbox = MemoryOutbox()
        val scheduler = FakeScheduler()
        val statuses = mutableListOf<NostrSendStatus>()
        val sender = NostrCheckInSender(
            ownPriv = activistPriv,
            recipients = listOf(guardianPub),
            relays = listOf(relayA),
            publish = publisher,
            outbox = outbox,
            scheduler = scheduler,
            clockSecs = { now },
            background = { it() },
            onStatus = statuses::add,
        )
        sender.send(listOf(sealText()))
        assertEquals(1, statuses.single().queued)
        assertEquals(1, outbox.size())
        assertEquals(1, scheduler.tasks.size)
    }

    @Test
    fun invalidRelaysAndMinAccepted() {
        try {
            NostrCheckInSender(
                ownPriv = activistPriv,
                recipients = listOf(guardianPub),
                relays = emptyList(),
                publish = FakePublisher(),
                scheduler = FakeScheduler(),
                background = { it() },
            )
            fail("sender without relays must fail")
        } catch (e: NostrException) {
            // Expected.
        }
        try {
            NostrCheckInSender(
                ownPriv = activistPriv,
                recipients = listOf(guardianPub),
                relays = listOf("not a url !!!", "  "),
                publish = FakePublisher(),
                scheduler = FakeScheduler(),
                background = { it() },
            )
            fail("sender with only invalid relays must fail")
        } catch (e: NostrException) {
            // Expected.
        }
        val sender = NostrCheckInSender(
            ownPriv = activistPriv,
            recipients = listOf(guardianPub),
            relays = listOf("wss://r1.example"),
            minAccepted = 0,
            publish = FakePublisher(),
            scheduler = FakeScheduler(),
            background = { it() },
        )
        assertEquals(1, sender.minAccepted)
        assertEquals(listOf("wss://r1.example"), sender.relays)
    }

    @Test
    fun emptyBatchSendsNothing() {
        val publisher = FakePublisher()
        val statuses = mutableListOf<NostrSendStatus>()
        val sender = NostrCheckInSender(
            ownPriv = activistPriv,
            recipients = listOf(guardianPub),
            relays = listOf(relayA),
            publish = publisher,
            outbox = MemoryOutbox(),
            scheduler = FakeScheduler(),
            background = { it() },
            onStatus = statuses::add,
        )
        sender.send(emptyList())
        assertTrue(publisher.calls.isEmpty())
        assertTrue(statuses.isEmpty())
    }
}
