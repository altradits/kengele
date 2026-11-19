package com.altradits.kengele.sms

import com.altradits.kengele.checkin.RecordingSender
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Vectors
import com.altradits.kengele.nostr.DualCheckInSender
import com.altradits.kengele.nostr.ShutdownModeMonitor
import com.altradits.kengele.nostr.smsWillSend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shutdown routing through the real seams: a live [ShutdownModeMonitor]
 * drives [SmsSender], so ordinary check-ins go by SMS only while Nostr
 * is unreachable and only for Shutdown-check-ins guardians. Panics and
 * duress check-ins always send where profiled; Off stays silent. All
 * keys are the made-up shared vectors; all phone numbers are in the
 * fictional 555-01XX range.
 */
class ShutdownRoutingTest {
    private val activistPriv = Vectors.hex("activist_priv_hex")
    private val guardianPub = Vectors.hex("guardian_pub_hex")
    private val created = Vectors.num("event_created_unix")

    private class FakeGateway : SmsGateway {
        data class Call(val id: Long, val number: String, val parts: List<String>)

        val calls = mutableListOf<Call>()

        override fun divide(text: String): List<String> = text.chunked(153)

        override fun send(requestId: Long, number: String, parts: List<String>) {
            calls.add(Call(requestId, number, parts))
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

    private fun sealText(kind: KengeleMessage.Kind): String {
        val event = KengeleMessage.Event(kind, created, 1, "Kisumu".toByteArray(Charsets.UTF_8))
        return KengeleMessage.encodeText(KengeleMessage.seal(event, activistPriv, guardianPub))
    }

    /** A monitor driven to exactly the wanted mode, asserting it got there. */
    private fun monitor(offline: Boolean): ShutdownModeMonitor {
        val monitor = ShutdownModeMonitor()
        if (offline) {
            repeat(ShutdownModeMonitor.ENTER_FAILURES) { monitor.onPublish(false) }
            assertTrue(monitor.shutdownMode)
        } else {
            assertFalse(monitor.shutdownMode)
        }
        return monitor
    }

    private fun smsSender(
        profile: SmsProfile?,
        kind: KengeleMessage.Kind,
        monitor: ShutdownModeMonitor,
        gateway: FakeGateway,
        scheduler: FakeScheduler,
        hub: SmsDeliveryHub = SmsDeliveryHub(),
        notices: MutableList<SmsNotice> = mutableListOf(),
    ): SmsSender {
        val directory = InMemorySmsDirectory()
        if (profile != null) directory.set(0, NUMBER_A, profile)
        return SmsSender(
            directory, gateway, scheduler,
            kindOfBatch = { kind },
            shutdownMode = { monitor.shutdownMode },
            hub = hub,
            onNotice = notices::add,
        )
    }

    /**
     * The routing matrix, stated outright (not via SmsProfile.sends, which
     * is the code under test): online/offline x Off/Alarm-only/Shutdown
     * x check-in/panic/duress.
     */
    @Test
    fun routingMatrix() {
        val kinds = listOf(
            KengeleMessage.Kind.CHECK_IN,
            KengeleMessage.Kind.PANIC,
            KengeleMessage.Kind.DURESS_CHECK_IN,
        )
        val profiles = listOf(SmsProfile.OFF, SmsProfile.ALARM_ONLY, SmsProfile.SHUTDOWN_CHECKINS)
        for (offline in listOf(false, true)) {
            for (profile in profiles) {
                for (kind in kinds) {
                    val expected = when {
                        profile == SmsProfile.OFF -> 0
                        kind == KengeleMessage.Kind.CHECK_IN ->
                            if (offline && profile == SmsProfile.SHUTDOWN_CHECKINS) 1 else 0
                        else -> 1
                    }
                    val gateway = FakeGateway()
                    val sender = smsSender(profile, kind, monitor(offline), gateway, FakeScheduler())
                    sender.send(listOf(sealText(kind)))
                    assertEquals("offline=$offline profile=$profile kind=$kind", expected, gateway.calls.size)
                }
            }
        }
    }

    @Test
    fun shutdownCheckinGoesOutOverBothTransports() {
        val nostr = RecordingSender()
        val gateway = FakeGateway()
        val texts = listOf(sealText(KengeleMessage.Kind.CHECK_IN))
        // Online: the Nostr leg carries the check-in, SMS stays quiet.
        DualCheckInSender(
            nostr = nostr,
            sms = smsSender(SmsProfile.SHUTDOWN_CHECKINS, KengeleMessage.Kind.CHECK_IN, monitor(false), gateway, FakeScheduler()),
        ).send(texts)
        assertEquals(listOf(texts), nostr.batches)
        assertTrue("SMS leg sent while online", gateway.calls.isEmpty())
        // Offline: the same batch gains an SMS leg with the same text.
        DualCheckInSender(
            nostr = nostr,
            sms = smsSender(SmsProfile.SHUTDOWN_CHECKINS, KengeleMessage.Kind.CHECK_IN, monitor(true), gateway, FakeScheduler()),
        ).send(texts)
        assertEquals(listOf(texts, texts), nostr.batches)
        assertEquals(1, gateway.calls.size)
        assertEquals(texts.single(), gateway.calls.single().parts.joinToString(""))
    }

    @Test
    fun alarmOnlyNeverGainsACheckinLeg() {
        for (offline in listOf(false, true)) {
            val gateway = FakeGateway()
            val sender = smsSender(SmsProfile.ALARM_ONLY, KengeleMessage.Kind.CHECK_IN, monitor(offline), gateway, FakeScheduler())
            sender.send(listOf(sealText(KengeleMessage.Kind.CHECK_IN)))
            assertTrue("offline=$offline", gateway.calls.isEmpty())
            assertEquals(0, sender.queuedCount())
        }
    }

    @Test
    fun nothingConfiguredStaysSilentEvenInShutdown() {
        val offline = monitor(true)
        for (kind in listOf(KengeleMessage.Kind.CHECK_IN, KengeleMessage.Kind.PANIC)) {
            // No directory entry at all.
            val gateway = FakeGateway()
            val sender = smsSender(null, kind, offline, gateway, FakeScheduler())
            sender.send(listOf(sealText(kind)))
            assertTrue("kind=$kind", gateway.calls.isEmpty())
            assertEquals(0, sender.queuedCount())
            // Explicit Off entry: same silence.
            val offGateway = FakeGateway()
            val offSender = smsSender(SmsProfile.OFF, kind, offline, offGateway, FakeScheduler())
            offSender.send(listOf(sealText(kind)))
            assertTrue("kind=$kind", offGateway.calls.isEmpty())
            assertEquals(0, offSender.queuedCount())
        }
        // The nothing-configured gate agrees in both modes.
        val empty = InMemorySmsDirectory()
        for (kind in listOf(KengeleMessage.Kind.CHECK_IN, KengeleMessage.Kind.PANIC)) {
            assertFalse(smsWillSend(empty, 1, kind, false))
            assertFalse(smsWillSend(empty, 1, kind, true))
        }
        // Both sides absent is still a no-op.
        DualCheckInSender(nostr = null, sms = null).send(listOf(sealText(KengeleMessage.Kind.CHECK_IN)))
    }

    @Test
    fun shutdownCheckinRetriesUseTheSameBackoff() {
        val gateway = FakeGateway()
        val scheduler = FakeScheduler()
        val hub = SmsDeliveryHub()
        val notices = mutableListOf<SmsNotice>()
        val sender = smsSender(
            SmsProfile.SHUTDOWN_CHECKINS, KengeleMessage.Kind.CHECK_IN,
            monitor(true), gateway, scheduler, hub, notices,
        )
        val text = sealText(KengeleMessage.Kind.CHECK_IN)
        sender.send(listOf(text))
        assertEquals(1, gateway.calls.size)
        val id = gateway.calls.single().id
        val delays = mutableListOf<Long>()
        repeat(6) {
            hub.deliver(id, SendResult.NO_SERVICE)
            hub.deliver(id, SendResult.NO_SERVICE)
            assertEquals(1, scheduler.tasks.size)
            delays.add(scheduler.tasks.single().delay)
            scheduler.runDue()
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 300_000L, 300_000L, 300_000L), delays)
        assertEquals(7, gateway.calls.size)
        for (call in gateway.calls) {
            assertEquals(NUMBER_A, call.number)
            assertEquals(text, call.parts.joinToString(""))
        }
        assertEquals(1, sender.queuedCount())
        assertTrue("no-service retries stay quiet", notices.isEmpty())
    }

    @Test
    fun exitingShutdownStopsTheSmsLeg() {
        val monitor = ShutdownModeMonitor()
        repeat(ShutdownModeMonitor.ENTER_FAILURES) { monitor.onPublish(false) }
        assertTrue(monitor.shutdownMode)
        val text = sealText(KengeleMessage.Kind.CHECK_IN)
        val gateway = FakeGateway()
        val sender = smsSender(SmsProfile.SHUTDOWN_CHECKINS, KengeleMessage.Kind.CHECK_IN, monitor, gateway, FakeScheduler())
        sender.send(listOf(text))
        assertEquals(1, gateway.calls.size)
        // One reachable relay and the next check-in stays Nostr-only.
        monitor.onPublish(true)
        sender.send(listOf(text))
        assertEquals(1, gateway.calls.size)
    }

    companion object {
        // Fictional 555-01XX range, never a real number.
        const val NUMBER_A = "+15550100001"
    }
}
