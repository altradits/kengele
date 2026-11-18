package com.altradits.kengele.sms

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Vectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Store-backed directory: index-to-key resolution, off-by-default gating
 * and number validation. All keys are made-up test keys; all numbers are
 * in the fictional 555-01XX range.
 */
class StoreSmsDirectoryTest {
    private val keyA = Vectors.testGuardianHex("sms-dir-a")
    private val keyB = Vectors.testGuardianHex("sms-dir-b")
    private val keys = listOf(keyA, keyB)

    @Test
    fun resolvesTextsToGuardianKeys() {
        val store = InMemorySmsStore()
        store.set(keyA, SmsStoreTest.NUMBER_A, SmsProfile.ALARM_ONLY)
        store.set(keyB, SmsStoreTest.NUMBER_B, SmsProfile.SHUTDOWN_CHECKINS)
        val directory = StoreSmsDirectory(store) { keys }
        assertEquals(SmsEntry(SmsStoreTest.NUMBER_A, SmsProfile.ALARM_ONLY), directory.entry(0))
        assertEquals(SmsEntry(SmsStoreTest.NUMBER_B, SmsProfile.SHUTDOWN_CHECKINS), directory.entry(1))
    }

    @Test
    fun missingEntryOrIndexIsOff() {
        val store = InMemorySmsStore()
        store.set(keyB, SmsStoreTest.NUMBER_B, SmsProfile.ALARM_ONLY)
        val directory = StoreSmsDirectory(store) { keys }
        assertNull(directory.entry(0))
        assertNull(directory.entry(-1))
        assertNull(directory.entry(2))
        assertNull(StoreSmsDirectory(store) { emptyList() }.entry(0))
    }

    @Test
    fun keyOrderFollowsTheBatchOrder() {
        // The same texts list resolves against the keys captured with it:
        // reversing the keys must reverse the numbers, never mix them.
        val store = InMemorySmsStore()
        store.set(keyA, SmsStoreTest.NUMBER_A, SmsProfile.ALARM_ONLY)
        store.set(keyB, SmsStoreTest.NUMBER_B, SmsProfile.ALARM_ONLY)
        val reversed = StoreSmsDirectory(store) { keys.reversed() }
        assertEquals(SmsStoreTest.NUMBER_B, reversed.entry(0)?.number)
        assertEquals(SmsStoreTest.NUMBER_A, reversed.entry(1)?.number)
    }

    @Test
    fun invalidStoredNumbersFailSafeToSilence() {
        val store = object : SmsStore by InMemorySmsStore() {
            override fun get(hexKey: String): SmsEntry? =
                SmsEntry("bogus!", SmsProfile.ALARM_ONLY)
        }
        assertNull(StoreSmsDirectory(store) { keys }.entry(0))
        val blank = object : SmsStore by InMemorySmsStore() {
            override fun get(hexKey: String): SmsEntry? =
                SmsEntry("", SmsProfile.SHUTDOWN_CHECKINS)
        }
        assertNull(StoreSmsDirectory(store) { keys }.entry(0))
        assertNull(StoreSmsDirectory(blank) { keys }.entry(1))
    }

    @Test
    fun offEntriesReachTheSenderWhichSkipsThem() {
        // A store that still hands out OFF entries: the directory passes
        // them through (per the SmsDirectory contract) and the sender's
        // profile gating drops them, so nothing is sent or queued.
        val store = object : SmsStore by InMemorySmsStore() {
            override fun get(hexKey: String): SmsEntry? =
                SmsEntry(SmsStoreTest.NUMBER_A, SmsProfile.OFF)
        }
        val directory = StoreSmsDirectory(store) { keys }
        assertEquals(SmsProfile.OFF, directory.entry(0)?.profile)
        val gateway = FakeGateway()
        val sender = SmsSender(
            directory, gateway, FakeScheduler(),
            kindOfBatch = { KengeleMessage.Kind.PANIC },
            shutdownMode = { true },
            hub = SmsDeliveryHub(),
        )
        sender.send(listOf("A".repeat(KengeleMessage.TEXT_SIZE)))
        assertTrue(gateway.calls.isEmpty())
        assertEquals(0, sender.queuedCount())
    }

    @Test
    fun senderSendsOnlyToOptedInGuardians() {
        val store = InMemorySmsStore()
        store.set(keyB, SmsStoreTest.NUMBER_B, SmsProfile.ALARM_ONLY)
        val gateway = FakeGateway()
        val sender = SmsSender(
            StoreSmsDirectory(store) { keys }, gateway, FakeScheduler(),
            kindOfBatch = { KengeleMessage.Kind.PANIC },
            shutdownMode = { false },
            hub = SmsDeliveryHub(),
        )
        val text = "A".repeat(KengeleMessage.TEXT_SIZE)
        sender.send(listOf(text, text))
        assertEquals(1, gateway.calls.size)
        assertEquals(SmsStoreTest.NUMBER_B, gateway.calls.single().number)
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
        override fun schedule(delayMillis: Long, task: () -> Unit): SmsTaskHandle =
            SmsTaskHandle { }
    }
}
