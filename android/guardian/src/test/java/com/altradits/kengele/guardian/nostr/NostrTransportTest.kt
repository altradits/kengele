package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.guardian.confirm.ConfirmBodies
import com.altradits.kengele.guardian.confirm.IgnoreReason
import com.altradits.kengele.guardian.confirm.ShamirVectors
import com.altradits.kengele.guardian.confirm.Tally
import com.altradits.kengele.guardian.confirm.TestKeys
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Secp256k1
import com.altradits.kengele.message.nostr.GiftWrap
import com.altradits.kengele.message.nostr.MemoryOutbox
import com.altradits.kengele.message.nostr.NostrException
import com.altradits.kengele.message.nostr.NostrJson
import com.altradits.kengele.message.nostr.WsException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The transport against in-memory fake relays: quorum, outbox retries,
 * dedup, sender filtering, connectivity and the intake-to-tally wiring.
 * Mirrors the Go `internal/transport/nostr` cases. All keys are made up.
 */
class NostrTransportTest {
    private val now = 1_800_000_000L
    private val activistPriv = TestKeys.testPriv("nostr-activist")
    private val activistPub = Secp256k1.publicKey(activistPriv)
    private val guardianPrivs = listOf("nostr-g0", "nostr-g1", "nostr-g2").map { TestKeys.testPriv(it) }
    private val guardianPubs = guardianPrivs.map { Secp256k1.publicKey(it) }
    private val strangerPriv = TestKeys.testPriv("nostr-stranger")

    private class World(val relays: Map<String, FakeRelay>) {
        val channels = ConcurrentHashMap<String, MutableList<FakeWsChannel>>()

        @Volatile
        var down: Boolean = false

        fun connect(url: String): WsChannel {
            if (down) throw WsException("relay down")
            val r = relays[url] ?: throw WsException("no such relay")
            val ch = FakeWsChannel()
            r.attach(ch)
            channels.computeIfAbsent(url) { Collections.synchronizedList(mutableListOf()) }.add(ch)
            return ch
        }

        fun waitSubscribed(url: String, timeoutMs: Long = 5000L) {
            val relay = relays[url] ?: throw AssertionError("no relay $url")
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (relay.subIds.isNotEmpty()) return
                Thread.sleep(10)
            }
            fail("no subscription on $url")
        }

        /** Models relay fan-out: every received event goes to every subscriber channel. */
        fun fanout() {
            for ((url, relay) in relays) {
                val chans = channels[url]?.toList() ?: emptyList()
                for (ev in relay.received.toList()) {
                    for (ch in chans) {
                        for (sub in relay.subIds.toList()) {
                            ch.deliver("[\"EVENT\",\"$sub\",${ev.toJson()}]")
                        }
                    }
                }
            }
        }
    }

    private val statusLog = Collections.synchronizedList(mutableListOf<Triple<Int, Int, Long>>())

    private fun transport(
        priv: ByteArray,
        world: World,
        urls: List<String>,
        minAccepted: Int = 1,
        senders: List<ByteArray> = emptyList(),
        outbox: MemoryOutbox = MemoryOutbox(),
    ): NostrTransport = NostrTransport(
        priv,
        NostrTransport.Config(
            relays = urls,
            minAccepted = minAccepted,
            publishTimeoutMs = 2000,
            retryMinMs = 20,
            retryMaxMs = 200,
            senders = senders,
            outbox = outbox,
            connectivity = NostrTransport.Connectivity { reachable, total, at ->
                statusLog.add(Triple(reachable, total, at))
            },
            clockSecs = { now },
            connect = { world.connect(it) },
        ),
    )

    private fun seal(kind: KengeleMessage.Kind, seq: Long, from: ByteArray, to: ByteArray): ByteArray =
        KengeleMessage.seal(
            KengeleMessage.Event(kind, now, seq, "Kenyatta Ave".toByteArray(Charsets.UTF_8)),
            from,
            to,
        )

    private fun receiveOne(q: LinkedBlockingQueue<NostrTransport.Delivery>): NostrTransport.Delivery =
        q.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no delivery in time")

    @Test
    fun activistToGuardiansOverRelays() {
        val world = World(mapOf("wss://r1.example" to FakeRelay("wss://r1.example"), "wss://r2.example" to FakeRelay("wss://r2.example")))
        val urls = world.relays.keys.toList()
        val handles = mutableListOf<NostrTransport.ReceiveHandle>()
        val queues = mutableListOf<LinkedBlockingQueue<NostrTransport.Delivery>>()
        val receivers = mutableListOf<NostrTransport>()
        try {
            for (g in guardianPrivs) {
                val rx = transport(g, world, urls, senders = listOf(activistPub))
                receivers.add(rx)
                val q = LinkedBlockingQueue<NostrTransport.Delivery>()
                queues.add(q)
                handles.add(rx.startReceive { q.offer(it) })
            }
            for (u in urls) world.waitSubscribed(u)

            val tx = transport(activistPriv, world, urls)
            try {
                val wires = guardianPubs.map { seal(KengeleMessage.Kind.PANIC, 3, activistPriv, it) }
                val results = tx.sendAll(wires, guardianPubs)
                for (r in results) {
                    assertEquals(2, r.accepted)
                    assertEquals(false, r.queued)
                }
            } finally {
                tx.close()
            }
            world.fanout()
            for ((i, q) in queues.withIndex()) {
                val d = receiveOne(q)
                assertEquals(KengeleMessage.Kind.PANIC, d.event.kind)
                assertEquals(3L, d.event.seq)
                assertArrayEquals(activistPub, d.event.signer)
                assertNull("extra delivery for guardian $i", q.poll(200, TimeUnit.MILLISECONDS))
            }
        } finally {
            handles.forEach { it.stop() }
            receivers.forEach { it.close() }
        }
    }

    @Test
    fun duplicateSuppression() {
        val world = World(mapOf("wss://r1.example" to FakeRelay("wss://r1.example"), "wss://r2.example" to FakeRelay("wss://r2.example"), "wss://r3.example" to FakeRelay("wss://r3.example")))
        val urls = world.relays.keys.toList()
        val rx = transport(guardianPrivs[0], world, urls, senders = listOf(activistPub))
        val q = LinkedBlockingQueue<NostrTransport.Delivery>()
        val handle = rx.startReceive { q.offer(it) }
        try {
            for (u in urls) world.waitSubscribed(u)
            val wire = seal(KengeleMessage.Kind.CHECK_IN, 1, activistPriv, guardianPubs[0])
            val wrapA = GiftWrap.wrap(wire, activistPriv, guardianPubs[0], now)
            val wrapB = GiftWrap.wrap(wire, activistPriv, guardianPubs[0], now) // re-wrapped copy
            for (u in urls) {
                val relay = world.relays[u]!!
                val ch = world.channels[u]!!.last()
                relay.push(ch, wrapA)
                relay.push(ch, wrapB)
            }
            receiveOne(q)
            assertNull("duplicate delivered", q.poll(400, TimeUnit.MILLISECONDS))
        } finally {
            handle.stop()
            rx.close()
        }
    }

    @Test
    fun partialAcceptanceAndFlushSkipsAccepted() {
        val okRelay = FakeRelay("wss://ok.example")
        val refuseRelay = FakeRelay("wss://refuse.example", accept = false)
        val world = World(mapOf(okRelay.url to okRelay, refuseRelay.url to refuseRelay))
        val urls = listOf(okRelay.url, refuseRelay.url)
        val wire = seal(KengeleMessage.Kind.CHECK_IN, 1, activistPriv, guardianPubs[0])

        val tx1 = transport(activistPriv, world, urls)
        try {
            val res = tx1.send(wire, guardianPubs[0])
            assertEquals(1, res.accepted)
            assertEquals(false, res.queued)
        } finally {
            tx1.close()
        }

        val outbox = MemoryOutbox()
        val tx2 = transport(activistPriv, world, urls, minAccepted = 2, outbox = outbox)
        try {
            val wire2 = seal(KengeleMessage.Kind.CHECK_IN, 2, activistPriv, guardianPubs[0])
            val res = tx2.send(wire2, guardianPubs[0])
            assertEquals(1, res.accepted)
            assertEquals(true, res.queued)
            val items = outbox.list()
            assertEquals(1, items.size)
            assertEquals(setOf(okRelay.url), items[0].accepted)
            // Once the second relay accepts, flush clears it without republishing to the first.
            refuseRelay.accept = true
            val before = okRelay.received.size
            assertEquals(0, tx2.flush())
            assertEquals(before, okRelay.received.size)
        } finally {
            tx2.close()
        }
    }

    @Test
    fun outageQueuesThenRetryAfterReconnect() {
        val relay = FakeRelay("wss://flaky.example")
        val world = World(mapOf(relay.url to relay))
        world.down = true
        statusLog.clear()
        val tx = transport(activistPriv, world, listOf(relay.url))
        val stop = AtomicBoolean(false)
        val runner = Thread { tx.runLoop(stop) }
        try {
            val res = tx.send(seal(KengeleMessage.Kind.PANIC, 9, activistPriv, guardianPubs[0]), guardianPubs[0])
            assertEquals(0, res.accepted)
            assertEquals(true, res.queued)
            val during = statusLog.last()
            assertEquals(0, during.first)
            assertEquals(1, during.second)

            runner.isDaemon = true
            runner.start()
            Thread.sleep(100)
            world.down = false

            val end = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < end) {
                if (tx.config.outbox.size() == 0) break
                Thread.sleep(20)
            }
            assertEquals(0, tx.config.outbox.size())
            assertEquals(1, relay.received.size)
            assertEquals(1, statusLog.last().first)
        } finally {
            stop.set(true)
            runner.join(5000)
            tx.close()
        }
    }

    @Test
    fun unknownSenderDropped() {
        val relay = FakeRelay("wss://r.example")
        val world = World(mapOf(relay.url to relay))
        val rx = transport(guardianPrivs[0], world, listOf(relay.url), senders = listOf(activistPub))
        val q = LinkedBlockingQueue<NostrTransport.Delivery>()
        val handle = rx.startReceive { q.offer(it) }
        try {
            world.waitSubscribed(relay.url)
            val wire = seal(KengeleMessage.Kind.PANIC, 1, strangerPriv, guardianPubs[0])
            relay.push(world.channels[relay.url]!!.last(), GiftWrap.wrap(wire, strangerPriv, guardianPubs[0], now))
            assertNull("stranger delivered", q.poll(400, TimeUnit.MILLISECONDS))
        } finally {
            handle.stop()
            rx.close()
        }
    }

    @Test
    fun badInputRejected() {
        val world = World(mapOf("wss://r.example" to FakeRelay("wss://r.example")))
        try {
            NostrTransport(guardianPrivs[0], NostrTransport.Config(relays = emptyList()))
            fail("accepted no relays")
        } catch (e: NostrException) {
            // Expected.
        }
        val tx = transport(activistPriv, world, listOf("wss://r.example"))
        try {
            try {
                tx.send("hello".toByteArray(), guardianPubs[0])
                fail("wrapped a non-Kengele payload")
            } catch (e: NostrException) {
                // Expected.
            }
            try {
                tx.sendAll(listOf(seal(KengeleMessage.Kind.PANIC, 1, activistPriv, guardianPubs[0])), emptyList())
                fail("accepted mismatched lengths")
            } catch (e: NostrException) {
                // Expected.
            }
        } finally {
            tx.close()
        }
    }

    @Test
    fun subscribeBackfillsSinceMaxJitterByDefault() {
        val world = World(mapOf("wss://r.example" to FakeRelay("wss://r.example")))
        val rx = transport(guardianPrivs[0], world, listOf("wss://r.example"), senders = listOf(activistPub))
        val handle = rx.startReceive { }
        try {
            world.waitSubscribed("wss://r.example")
            val req = world.channels["wss://r.example"]!!.last().sent.single()
            val filter = (NostrJson.parse(req) as NostrJson.Arr).items[2] as NostrJson.Obj
            assertEquals(now - GiftWrap.MAX_JITTER_SECS, (filter.fields["since"] as NostrJson.Num).long())
        } finally {
            handle.stop()
            rx.close()
        }
    }

    @Test
    fun intakeToTallyWiringReleasesAndStandsDown() {
        val vec = ShamirVectors.named("k2-of-n3")
        val tally = Tally(activistPub, guardianPubs, guardianPubs[0], 2)
        val world = World(mapOf("wss://r.example" to FakeRelay("wss://r.example")))
        val rx = transport(guardianPrivs[0], world, listOf("wss://r.example"), senders = tally.senders())
        try {
            fun deliver(fromPriv: ByteArray, event: KengeleMessage.Event): NostrIntake.Result {
                val wire = KengeleMessage.seal(event, fromPriv, guardianPubs[0])
                val d = rx.open(GiftWrap.wrap(wire, fromPriv, guardianPubs[0], now))
                    ?: throw AssertionError("not delivered")
                return NostrIntake.intake(d, tally, now)
            }
            // Two guardian confirmations release with the rebuilt key.
            val c1 = deliver(
                guardianPrivs[1],
                KengeleMessage.Event(KengeleMessage.Kind.GUARDIAN_CONFIRM, now, 1, ConfirmBodies.encodeConfirm(activistPub, 1, vec.shares[1])),
            ) as NostrIntake.Result.Counted
            assertEquals(com.altradits.kengele.guardian.confirm.Action.NONE, c1.outcome.action)
            val c2 = deliver(
                guardianPrivs[2],
                KengeleMessage.Event(KengeleMessage.Kind.GUARDIAN_CONFIRM, now, 2, ConfirmBodies.encodeConfirm(activistPub, 1, vec.shares[2])),
            ) as NostrIntake.Result.Counted
            assertEquals(com.altradits.kengele.guardian.confirm.Action.RELEASE, c2.outcome.action)
            assertArrayEquals(vec.secret, c2.outcome.key)
            // A duplicate confirmation is ignored, not counted twice.
            val dup = deliver(
                guardianPrivs[1],
                KengeleMessage.Event(KengeleMessage.Kind.GUARDIAN_CONFIRM, now, 3, ConfirmBodies.encodeConfirm(activistPub, 1, vec.shares[1])),
            )
            assertTrue(dup is NostrIntake.Result.Ignored)
            // A panic from the activist is not a confirmation: ignored.
            val panic = deliver(
                activistPriv,
                KengeleMessage.Event(KengeleMessage.Kind.PANIC, now, 4, "msaada".toByteArray(Charsets.UTF_8)),
            ) as NostrIntake.Result.Ignored
            assertEquals(IgnoreReason.IGNORED_KIND, panic.reason)
            // Two guardian all-clears stand the alarm down (here: found safe after release).
            val s1 = deliver(
                guardianPrivs[1],
                KengeleMessage.Event(KengeleMessage.Kind.ALL_CLEAR, now, 5, ConfirmBodies.encodeAllClear(activistPub, 1)),
            ) as NostrIntake.Result.Counted
            assertEquals(com.altradits.kengele.guardian.confirm.Action.NONE, s1.outcome.action)
            val s2 = deliver(
                guardianPrivs[2],
                KengeleMessage.Event(KengeleMessage.Kind.ALL_CLEAR, now, 6, ConfirmBodies.encodeAllClear(activistPub, 1)),
            ) as NostrIntake.Result.Counted
            assertEquals(com.altradits.kengele.guardian.confirm.Action.FOUND_SAFE, s2.outcome.action)
        } finally {
            rx.close()
        }
    }

    @Test
    fun receiveReportsConnectivityUpAndDown() {
        val world = World(mapOf("wss://r.example" to FakeRelay("wss://r.example")))
        statusLog.clear()
        val rx = transport(guardianPrivs[0], world, listOf("wss://r.example"), senders = listOf(activistPub))
        val handle = rx.startReceive { }
        try {
            val end = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < end) {
                if (statusLog.any { it.first == 1 && it.second == 1 }) break
                Thread.sleep(20)
            }
            assertTrue("never reachable", statusLog.any { it.first == 1 && it.second == 1 })
        } finally {
            handle.stop()
            rx.close()
        }
        assertTrue("never reported down", statusLog.any { it.first == 0 })
    }
}
