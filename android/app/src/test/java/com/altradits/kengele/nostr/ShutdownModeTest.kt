package com.altradits.kengele.nostr

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Vectors
import com.altradits.kengele.message.nostr.MemoryOutbox
import com.altradits.kengele.message.nostr.NostrEvent
import com.altradits.kengele.sms.SmsScheduler
import com.altradits.kengele.sms.SmsTaskHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shutdown-mode signal: debounce enter/exit edges and the Nostr sender
 * feed. All keys are the made-up shared vectors; all relay addresses are
 * fictional example.com names.
 */
class ShutdownModeTest {
    private var now = 1_000_000L
    private fun monitor() = ShutdownModeMonitor(clockSecs = { now })

    @Test
    fun thresholdsArePinned() {
        assertEquals(3, ShutdownModeMonitor.ENTER_FAILURES)
        assertEquals(300L, ShutdownModeMonitor.ENTER_UNREACHABLE_SECS)
    }

    @Test
    fun startsOnline() {
        assertFalse(monitor().shutdownMode)
    }

    @Test
    fun entersAfterNConsecutiveFailures() {
        val monitor = monitor()
        repeat(ShutdownModeMonitor.ENTER_FAILURES - 1) {
            monitor.onPublish(false)
            assertFalse("entered early", monitor.shutdownMode)
        }
        monitor.onPublish(false)
        assertTrue(monitor.shutdownMode)
    }

    @Test
    fun staysOnlineWithoutAnyFailureHoweverLong() {
        val monitor = monitor()
        now += 10 * ShutdownModeMonitor.ENTER_UNREACHABLE_SECS
        assertFalse(monitor.shutdownMode)
    }

    @Test
    fun entersAfterMSecondsUnreachableWithoutNewFailures() {
        val monitor = monitor()
        val start = now
        monitor.onPublish(false)
        now = start + ShutdownModeMonitor.ENTER_UNREACHABLE_SECS - 1
        assertFalse("entered early on time", monitor.shutdownMode)
        now = start + ShutdownModeMonitor.ENTER_UNREACHABLE_SECS
        assertTrue("time leg never entered", monitor.shutdownMode)
    }

    @Test
    fun firstSuccessExitsAndResetsTheStreak() {
        val monitor = monitor()
        repeat(ShutdownModeMonitor.ENTER_FAILURES) { monitor.onPublish(false) }
        assertTrue(monitor.shutdownMode)
        monitor.onPublish(true)
        assertFalse("first success did not exit", monitor.shutdownMode)
        // The streak restarts: N-1 more failures still stay online.
        repeat(ShutdownModeMonitor.ENTER_FAILURES - 1) { monitor.onPublish(false) }
        assertFalse(monitor.shutdownMode)
        monitor.onPublish(false)
        assertTrue(monitor.shutdownMode)
    }

    @Test
    fun successBeforeEnteringResetsBothLegs() {
        val monitor = monitor()
        val start = now
        monitor.onPublish(false)
        monitor.onPublish(false)
        monitor.onPublish(true)
        // The old failures are forgotten: advancing past the time
        // threshold with no live streak stays online...
        now = start + ShutdownModeMonitor.ENTER_UNREACHABLE_SECS
        assertFalse("stale streak tripped the time leg", monitor.shutdownMode)
        // ...and one new failure starts a fresh streak at the new time.
        monitor.onPublish(false)
        assertFalse(monitor.shutdownMode)
        now += ShutdownModeMonitor.ENTER_UNREACHABLE_SECS - 1
        assertFalse(monitor.shutdownMode)
        now += 1
        assertTrue("fresh streak never tripped the time leg", monitor.shutdownMode)
    }

    @Test
    fun timeBasedEntryAlsoExitsOnFirstSuccess() {
        val monitor = monitor()
        monitor.onPublish(false)
        now += ShutdownModeMonitor.ENTER_UNREACHABLE_SECS
        assertTrue(monitor.shutdownMode)
        monitor.onPublish(true)
        assertFalse(monitor.shutdownMode)
    }

    // NostrCheckInSender feed: every gift wrap it publishes — fresh sends
    // and outbox retries — is one observation.

    private val activistPriv = Vectors.hex("activist_priv_hex")
    private val guardianPub = Vectors.hex("guardian_pub_hex")
    private val created = Vectors.num("event_created_unix")

    private val relayA = "wss://r1.example"
    private val relayB = "wss://r2.example"

    private class FakePublisher(var outcome: RelayPublishOutcome = RelayPublishOutcome.ACCEPTED) : RelayPublisher {
        val calls = mutableListOf<Pair<NostrEvent, String>>()
        val perRelay = mutableMapOf<String, RelayPublishOutcome>()

        override fun publish(event: NostrEvent, relayUrl: String, timeoutMs: Long): RelayPublishOutcome {
            calls.add(event to relayUrl)
            return perRelay[relayUrl] ?: outcome
        }
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

    private fun sealText(seq: Long = 1): String {
        val event = KengeleMessage.Event(
            KengeleMessage.Kind.CHECK_IN, created, seq, "Kisumu".toByteArray(Charsets.UTF_8),
        )
        return KengeleMessage.encodeText(KengeleMessage.seal(event, activistPriv, guardianPub))
    }

    private fun sender(
        publisher: FakePublisher,
        reachability: NostrReachabilitySink,
        scheduler: FakeScheduler = FakeScheduler(),
        outbox: MemoryOutbox = MemoryOutbox(),
        minAccepted: Int = 1,
    ) = NostrCheckInSender(
        ownPriv = activistPriv,
        recipients = listOf(guardianPub),
        relays = listOf(relayA, relayB),
        minAccepted = minAccepted,
        publish = publisher,
        outbox = outbox,
        scheduler = scheduler,
        clockSecs = { now },
        background = { it() },
        reachability = reachability,
    )

    @Test
    fun failedSendsEnterShutdownAndAcceptedExits() {
        val monitor = monitor()
        val publisher = FakePublisher(RelayPublishOutcome.FAILED)
        val sender = sender(publisher, monitor)
        repeat(ShutdownModeMonitor.ENTER_FAILURES - 1) { sender.send(listOf(sealText())) }
        assertFalse(monitor.shutdownMode)
        sender.send(listOf(sealText()))
        assertTrue("failed sends never entered shutdown", monitor.shutdownMode)
        publisher.outcome = RelayPublishOutcome.ACCEPTED
        sender.send(listOf(sealText()))
        assertFalse("accepted send did not exit shutdown", monitor.shutdownMode)
    }

    @Test
    fun refusedRelaysCountAsReachable() {
        val monitor = monitor()
        val publisher = FakePublisher(RelayPublishOutcome.REJECTED)
        val sender = sender(publisher, monitor)
        repeat(ShutdownModeMonitor.ENTER_FAILURES + 2) { sender.send(listOf(sealText())) }
        assertFalse("refusals wrongly entered shutdown", monitor.shutdownMode)
    }

    @Test
    fun oneAnsweringRelayKeepsTheSignalOnline() {
        val monitor = monitor()
        val publisher = FakePublisher()
        publisher.perRelay[relayA] = RelayPublishOutcome.FAILED
        publisher.perRelay[relayB] = RelayPublishOutcome.ACCEPTED
        val sender = sender(publisher, monitor)
        repeat(ShutdownModeMonitor.ENTER_FAILURES + 2) { sender.send(listOf(sealText())) }
        assertFalse(monitor.shutdownMode)
    }

    @Test
    fun retryFailuresFeedTheSignalAndRecoveryExits() {
        val monitor = monitor()
        val publisher = FakePublisher(RelayPublishOutcome.FAILED)
        val scheduler = FakeScheduler()
        val outbox = MemoryOutbox()
        val sender = sender(publisher, monitor, scheduler, outbox, minAccepted = 2)
        sender.send(listOf(sealText(seq = 7)))
        assertEquals(1, outbox.size())
        assertFalse(monitor.shutdownMode)
        // Each failed retry is another observation; the third enters.
        scheduler.runDue()
        assertFalse(monitor.shutdownMode)
        scheduler.runDue()
        assertTrue("failed retries never entered shutdown", monitor.shutdownMode)
        // Recovery through the same retry path exits at once.
        publisher.outcome = RelayPublishOutcome.ACCEPTED
        scheduler.runDue()
        assertEquals(0, outbox.size())
        assertFalse("retry recovery did not exit shutdown", monitor.shutdownMode)
    }

    @Test
    fun roundsThatContactNoRelayReportNothing() {
        val monitor = monitor()
        val publisher = FakePublisher(RelayPublishOutcome.ACCEPTED)
        val outbox = MemoryOutbox()
        // minAccepted above the relay count: the wrap is accepted but stays
        // queued below quorum, and the retry contacts nobody new.
        val sender = NostrCheckInSender(
            ownPriv = activistPriv,
            recipients = listOf(guardianPub),
            relays = listOf(relayA),
            minAccepted = 2,
            publish = publisher,
            outbox = outbox,
            scheduler = FakeScheduler(),
            clockSecs = { now },
            background = { it() },
            reachability = monitor,
        )
        sender.send(listOf(sealText()))
        assertEquals(1, outbox.size())
        sender.flush()
        // A no-contact retry must not count as a failure: two real
        // failures afterwards still stay online, the third enters.
        publisher.outcome = RelayPublishOutcome.FAILED
        sender.send(listOf(sealText(seq = 2)))
        sender.send(listOf(sealText(seq = 3)))
        assertFalse("no-contact retry counted as a failure", monitor.shutdownMode)
        sender.send(listOf(sealText(seq = 4)))
        assertTrue(monitor.shutdownMode)
    }
}
