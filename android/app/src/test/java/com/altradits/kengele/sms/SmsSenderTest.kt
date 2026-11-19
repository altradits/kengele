package com.altradits.kengele.sms

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Vectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SmsSender gating, queueing and backoff. All keys are the made-up shared
 * vectors; all phone numbers are in the fictional 555-01XX range.
 */
class SmsSenderTest {
    private val activistPriv = Vectors.hex("activist_priv_hex")
    private val guardianPub = Vectors.hex("guardian_pub_hex")
    private val created = Vectors.num("event_created_unix")

    private fun sealText(
        kind: KengeleMessage.Kind = KengeleMessage.Kind.CHECK_IN,
        seq: Long = 1,
        body: String = "Kisumu",
    ): String {
        val event = KengeleMessage.Event(kind, created, seq, body.toByteArray(Charsets.UTF_8))
        return KengeleMessage.encodeText(KengeleMessage.seal(event, activistPriv, guardianPub))
    }

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

    private val directory = InMemorySmsDirectory()
    private val gateway = FakeGateway()
    private val scheduler = FakeScheduler()
    private val hub = SmsDeliveryHub()
    private val notices = mutableListOf<SmsNotice>()
    private var kind = KengeleMessage.Kind.CHECK_IN
    private var shutdown = false
    private val sender = SmsSender(
        directory, gateway, scheduler,
        kindOfBatch = { kind },
        shutdownMode = { shutdown },
        hub = hub,
        onNotice = notices::add,
    )

    private fun complete(id: Long, vararg results: SendResult) {
        for (result in results) hub.deliver(id, result)
    }

    @Test
    fun sendsOneTwoSegmentSmsPerOptedInGuardian() {
        directory.set(0, NUMBER_A, SmsProfile.ALARM_ONLY)
        kind = KengeleMessage.Kind.PANIC
        val text = sealText(KengeleMessage.Kind.PANIC)
        sender.send(listOf(text))

        assertEquals(1, gateway.calls.size)
        val call = gateway.calls.single()
        assertEquals(NUMBER_A, call.number)
        // 228 bytes on the wire, 304 base64url chars, one 2-segment SMS.
        assertEquals(KengeleMessage.WIRE_SIZE, KengeleMessage.decodeText(text).size)
        assertEquals(KengeleMessage.TEXT_SIZE, text.length)
        assertEquals(2, call.parts.size)
        assertEquals(text, call.parts.joinToString(""))

        complete(call.id, SendResult.OK, SendResult.OK)
        assertEquals(0, sender.queuedCount())
        assertTrue(scheduler.tasks.isEmpty())
    }

    @Test
    fun offByDefaultSendsNothing() {
        // No directory entry, and an explicit OFF entry: nothing goes out.
        directory.set(1, NUMBER_B, SmsProfile.OFF)
        for (k in KengeleMessage.Kind.values()) {
            kind = k
            shutdown = true
            sender.send(listOf(sealText(k), sealText(k)))
        }
        assertTrue(gateway.calls.isEmpty())
        assertEquals(0, sender.queuedCount())
        assertTrue(notices.isEmpty())
    }

    @Test
    fun textsMapToGuardianIndexes() {
        directory.set(0, NUMBER_A, SmsProfile.ALARM_ONLY)
        directory.set(1, NUMBER_B, SmsProfile.ALARM_ONLY)
        kind = KengeleMessage.Kind.PANIC
        val texts = listOf(sealText(KengeleMessage.Kind.PANIC, seq = 1), sealText(KengeleMessage.Kind.PANIC, seq = 2))
        sender.send(texts)
        assertEquals(2, gateway.calls.size)
        assertEquals(NUMBER_A, gateway.calls[0].number)
        assertEquals(texts[0], gateway.calls[0].parts.joinToString(""))
        assertEquals(NUMBER_B, gateway.calls[1].number)
        assertEquals(texts[1], gateway.calls[1].parts.joinToString(""))
    }

    @Test
    fun missingEntrySkipsOnlyThatGuardian() {
        directory.set(1, NUMBER_B, SmsProfile.ALARM_ONLY)
        kind = KengeleMessage.Kind.PANIC
        val texts = listOf(sealText(KengeleMessage.Kind.PANIC), sealText(KengeleMessage.Kind.PANIC))
        sender.send(texts)
        assertEquals(1, gateway.calls.size)
        assertEquals(NUMBER_B, gateway.calls.single().number)
    }

    private fun attemptCount(profile: SmsProfile, batchKind: KengeleMessage.Kind, inShutdown: Boolean): Int {
        val dir = InMemorySmsDirectory().also { it.set(0, NUMBER_A, profile) }
        val gw = FakeGateway()
        SmsSender(dir, gw, FakeScheduler(), { batchKind }, { inShutdown }, SmsDeliveryHub())
            .send(listOf(sealText(batchKind)))
        return gw.calls.size
    }

    @Test
    fun alarmOnlySendsAlarmsButNeverOrdinaryCheckins() {
        for (batchKind in KengeleMessage.Kind.values()) {
            val expected = if (batchKind in SmsProfile.ALARM_KINDS) 1 else 0
            assertEquals("$batchKind", expected, attemptCount(SmsProfile.ALARM_ONLY, batchKind, false))
            assertEquals("$batchKind shutdown", expected, attemptCount(SmsProfile.ALARM_ONLY, batchKind, true))
        }
    }

    @Test
    fun shutdownCheckinsSendsOrdinaryCheckinsOnlyInShutdownMode() {
        for (batchKind in KengeleMessage.Kind.values()) {
            val expectedOutside = if (batchKind in SmsProfile.ALARM_KINDS) 1 else 0
            assertEquals("$batchKind", expectedOutside, attemptCount(SmsProfile.SHUTDOWN_CHECKINS, batchKind, false))
            assertEquals("$batchKind shutdown", 1, attemptCount(SmsProfile.SHUTDOWN_CHECKINS, batchKind, true))
        }
    }

    @Test
    fun retriesReuseTheSameTextWithBackoff() {
        directory.set(0, NUMBER_A, SmsProfile.ALARM_ONLY)
        kind = KengeleMessage.Kind.PANIC
        val text = sealText(KengeleMessage.Kind.PANIC)
        sender.send(listOf(text))
        val id = gateway.calls.single().id

        val delays = mutableListOf<Long>()
        repeat(6) {
            complete(id, SendResult.NO_SERVICE, SendResult.NO_SERVICE)
            assertEquals(1, scheduler.tasks.size)
            delays.add(scheduler.tasks.single().delay)
            scheduler.runDue()
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 300_000L, 300_000L, 300_000L), delays)
        // Same text every time: a retry looks like a repeat, not a new event.
        assertEquals(7, gateway.calls.size)
        for (call in gateway.calls) {
            assertEquals(NUMBER_A, call.number)
            assertEquals(text, call.parts.joinToString(""))
        }
        assertEquals(1, sender.queuedCount())
        assertTrue("no-service retries stay quiet", notices.isEmpty())
    }

    @Test
    fun recoversAfterFailure() {
        directory.set(0, NUMBER_A, SmsProfile.ALARM_ONLY)
        kind = KengeleMessage.Kind.PANIC
        sender.send(listOf(sealText(KengeleMessage.Kind.PANIC)))
        val id = gateway.calls.single().id
        complete(id, SendResult.NO_SERVICE, SendResult.NO_SERVICE)
        scheduler.runDue()
        complete(id, SendResult.OK, SendResult.OK)
        assertEquals(0, sender.queuedCount())
        assertTrue(scheduler.tasks.isEmpty())
        assertEquals(2, gateway.calls.size)
    }

    @Test
    fun oneBadPartRetriesTheWholeSmsAndShowsTheActivist() {
        directory.set(0, NUMBER_A, SmsProfile.ALARM_ONLY)
        kind = KengeleMessage.Kind.PANIC
        val text = sealText(KengeleMessage.Kind.PANIC)
        sender.send(listOf(text))
        val id = gateway.calls.single().id
        complete(id, SendResult.OK, SendResult.FAILURE)
        assertEquals(listOf(SmsNotice.SEND_FAILED_QUEUED), notices)
        assertEquals(1, scheduler.tasks.size)
        scheduler.runDue()
        assertEquals(2, gateway.calls.size)
        assertEquals(text, gateway.calls[1].parts.joinToString(""))
    }

    @Test
    fun radioOffRetriesQuietly() {
        directory.set(0, NUMBER_A, SmsProfile.ALARM_ONLY)
        kind = KengeleMessage.Kind.PANIC
        sender.send(listOf(sealText(KengeleMessage.Kind.PANIC)))
        complete(gateway.calls.single().id, SendResult.RADIO_OFF, SendResult.RADIO_OFF)
        assertEquals(1, scheduler.tasks.size)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun missingPermissionNotifiesAndQueues() {
        directory.set(0, NUMBER_A, SmsProfile.ALARM_ONLY)
        kind = KengeleMessage.Kind.PANIC
        sender.send(listOf(sealText(KengeleMessage.Kind.PANIC)))
        complete(gateway.calls.single().id, SendResult.NO_PERMISSION, SendResult.NO_PERMISSION)
        assertEquals(listOf(SmsNotice.NO_PERMISSION), notices)
        assertEquals(1, scheduler.tasks.size)
        assertEquals(1, sender.queuedCount())
    }

    @Test
    fun malformedTextsAreDroppedNeverSent() {
        directory.set(0, NUMBER_A, SmsProfile.ALARM_ONLY)
        directory.set(1, NUMBER_B, SmsProfile.ALARM_ONLY)
        directory.set(2, NUMBER_A, SmsProfile.ALARM_ONLY)
        kind = KengeleMessage.Kind.PANIC
        val good = sealText(KengeleMessage.Kind.PANIC)
        val badChar = good.substring(0, 303) + "!"
        sender.send(listOf("too short", badChar, good))
        assertEquals(1, gateway.calls.size)
        assertEquals(good, gateway.calls.single().parts.joinToString(""))
        assertEquals(listOf(SmsNotice.INVALID_TEXT_DROPPED, SmsNotice.INVALID_TEXT_DROPPED), notices)
    }

    @Test
    fun emptyBatchSendsNothing() {
        directory.set(0, NUMBER_A, SmsProfile.ALARM_ONLY)
        sender.send(emptyList())
        assertTrue(gateway.calls.isEmpty())
        assertTrue(notices.isEmpty())
    }

    companion object {
        // Fictional 555-01XX range, never real numbers.
        const val NUMBER_A = "+15550100001"
        const val NUMBER_B = "+15550100002"
    }
}
