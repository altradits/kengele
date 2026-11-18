package com.altradits.kengele.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-part result aggregation and routing back to the sender. */
class SmsDeliveryHubTest {
    @Test
    fun okOnlyWhenEveryPartIsOk() {
        val hub = SmsDeliveryHub()
        val done = mutableListOf<SendResult>()
        hub.register(7, 2, done::add)
        hub.deliver(7, SendResult.OK)
        assertTrue(done.isEmpty())
        hub.deliver(7, SendResult.OK)
        assertEquals(listOf(SendResult.OK), done)
    }

    @Test
    fun firstFailureInArrivalOrderWins() {
        val hub = SmsDeliveryHub()
        val done = mutableListOf<SendResult>()
        hub.register(1, 2, done::add)
        hub.deliver(1, SendResult.FAILURE)
        hub.deliver(1, SendResult.OK)
        assertEquals(listOf(SendResult.FAILURE), done)

        val done2 = mutableListOf<SendResult>()
        hub.register(2, 2, done2::add)
        hub.deliver(2, SendResult.OK)
        hub.deliver(2, SendResult.NO_SERVICE)
        assertEquals(listOf(SendResult.NO_SERVICE), done2)
    }

    @Test
    fun unknownIdsAreIgnored() {
        val hub = SmsDeliveryHub()
        hub.deliver(999, SendResult.OK) // e.g. after a process restart: no crash, nothing happens.
    }

    @Test
    fun completesAtMostOnce() {
        val hub = SmsDeliveryHub()
        val done = mutableListOf<SendResult>()
        hub.register(1, 1, done::add)
        hub.deliver(1, SendResult.OK)
        hub.deliver(1, SendResult.FAILURE)
        assertEquals(listOf(SendResult.OK), done)
    }

    @Test
    fun capEvictsOldest() {
        val hub = SmsDeliveryHub()
        val done = mutableListOf<Long>()
        for (id in 1L..(SmsDeliveryHub.MAX_PENDING + 1L)) {
            hub.register(id, 1) { done.add(id) }
        }
        hub.deliver(1, SendResult.OK)
        assertTrue("oldest must be evicted", done.isEmpty())
        hub.deliver(2, SendResult.OK)
        hub.deliver((SmsDeliveryHub.MAX_PENDING + 1L).toLong(), SendResult.OK)
        assertEquals(listOf(2L, (SmsDeliveryHub.MAX_PENDING + 1L).toLong()), done)
    }
}
