package com.altradits.kengele.mesh

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Vectors
import java.io.File
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Android-free port of the `internal/meshcarry` Go tests: fragment sizes,
 * round-trips, rejects, TTL behaviour, flood dedup, kind-indistinguishability
 * and memory-only carriage. Made-up test data only; never real people,
 * keys or numbers.
 */
class MeshCarryTest {
    // -- helpers. --

    /** Deterministic 228-byte datagram with a position marker. */
    private fun testDatagram(fill: Byte): ByteArray =
        ByteArray(MeshCarry.DATAGRAM_SIZE) { i -> (fill + (i % 251)).toByte() }

    private fun assertKind(kind: MeshCarryException.Kind, block: () -> Unit) {
        try {
            block()
            fail("want $kind, nothing thrown")
        } catch (e: MeshCarryException) {
            assertEquals(kind, e.kind)
        }
    }

    /** In-memory multi-hop harness mirroring the Go Simulator, for tests only. */
    private class TestMesh(var sendTtl: Int = MeshCarry.DEFAULT_TTL) {
        enum class Kind { BROADCAST, RELAY, REASSEMBLED }

        data class TraceEvent(
            val kind: Kind,
            val node: String,
            val msgId: Int,
            val index: Int,
            val total: Int,
            val ttl: Int,
            val hops: Int = 0,
            val bytes: Int = 0,
        )

        data class Delivery(val datagram: ByteArray, val from: String, val hops: Int)
        private data class Flight(val to: String, val from: String, val frag: ByteArray, val hops: Int)

        private val nodes = mutableMapOf<String, BlindNode>()
        private val links = mutableMapOf<String, MutableSet<String>>()
        private val inbox = ArrayDeque<Flight>()
        private val deliveries = mutableMapOf<String, MutableList<Delivery>>()
        var nextId: Int = 1
        var onTrace: ((TraceEvent) -> Unit)? = null

        fun addNode(id: String) {
            nodes.getOrPut(id) { BlindNode(id) }
            links.getOrPut(id) { mutableSetOf() }
        }

        fun node(id: String): BlindNode? = nodes[id]

        fun link(a: String, b: String) {
            addNode(a)
            addNode(b)
            if (a == b) return
            links.getValue(a).add(b)
            links.getValue(b).add(a)
        }

        fun peers(id: String): List<String> = links[id]?.sorted() ?: emptyList()

        fun inject(nodeId: String, datagram: ByteArray) {
            val n = nodes[nodeId] ?: throw AssertionError("unknown node $nodeId")
            val ttl = MeshCarry.broadcastTtl(peers(nodeId).size, sendTtl)
            val msgId = nextId
            val frags = MeshCarry.split(datagram, msgId, ttl)
            nextId = (nextId + 1) and MeshCarry.MAX_MSG_ID
            n.markSeenDatagram(datagram)
            for (i in frags.indices) n.markRelayedFrag(msgId, i)
            for (to in peers(nodeId)) {
                for (i in frags.indices) {
                    inbox.addLast(Flight(to, nodeId, frags[i], 1))
                    onTrace?.invoke(
                        TraceEvent(Kind.BROADCAST, nodeId, msgId, i, frags.size, ttl),
                    )
                }
            }
        }

        /** Delivers one wave; returns how many fragments were relayed. */
        fun step(): Int {
            val wave = inbox.toList()
            inbox.clear()
            var relays = 0
            for (fl in wave) {
                val n = nodes[fl.to] ?: continue
                val res = try {
                    n.receive(fl.frag)
                } catch (e: MeshCarryException) {
                    continue // carriers drop malformed fragments silently
                }
                val relay = res.relay
                if (relay != null) {
                    relays++
                    val rh = MeshCarry.parseHeader(relay).header
                    onTrace?.invoke(
                        TraceEvent(Kind.RELAY, fl.to, rh.msgId, rh.index, rh.total, rh.ttl),
                    )
                    for (to in peers(fl.to)) {
                        inbox.addLast(Flight(to, fl.to, relay, fl.hops + 1))
                    }
                }
                val delivery = res.delivery
                if (delivery != null) {
                    deliveries.getOrPut(fl.to) { mutableListOf() }
                        .add(Delivery(delivery.datagram, fl.from, fl.hops))
                    val fh = MeshCarry.parseHeader(fl.frag).header
                    onTrace?.invoke(
                        TraceEvent(
                            Kind.REASSEMBLED, fl.to, fh.msgId, fh.index, fh.total, fh.ttl,
                            fl.hops, delivery.datagram.size,
                        ),
                    )
                }
            }
            return relays
        }

        fun run(maxSteps: Int) {
            repeat(maxSteps) { if (step() == 0) return }
        }

        fun deliveries(id: String): List<Delivery> = deliveries[id]?.toList() ?: emptyList()
    }

    private fun lineMesh(): TestMesh {
        val m = TestMesh()
        m.addNode("A")
        m.addNode("B")
        m.addNode("C")
        m.link("A", "B")
        m.link("B", "C")
        return m
    }

    // -- T1: split sizes. --

    @Test
    fun splitMakesTwoRadioSizedFragments() {
        val frags = MeshCarry.split(testDatagram(0xA5.toByte()), 0x123456, MeshCarry.DEFAULT_TTL)
        assertEquals(2, frags.size)
        assertEquals(MeshCarry.FRAGMENT_MTU, frags[0].size)
        assertEquals(MeshCarry.HEADER_LEN + 87, frags[1].size)
        for (f in frags) assertTrue(f.size <= MeshCarry.FRAGMENT_MTU)
        val carried = frags[0].size + frags[1].size - 2 * MeshCarry.HEADER_LEN
        assertEquals(MeshCarry.DATAGRAM_SIZE, carried)
        for (i in frags.indices) {
            val p = MeshCarry.parseHeader(frags[i])
            assertEquals(MeshCarry.DEFAULT_TTL, p.header.ttl)
            assertEquals(2, p.header.total)
            assertEquals(i, p.header.index)
            assertEquals(0x123456, p.header.msgId)
            assertEquals(frags[i].size, MeshCarry.HEADER_LEN + p.chunk.size)
        }
    }

    // -- T3: split rejects. --

    @Test
    fun splitRejectsAnythingBut228Bytes() {
        for (n in listOf(0, 1, 227, 229, 300)) {
            assertKind(MeshCarryException.Kind.DATAGRAM_SIZE) {
                MeshCarry.split(ByteArray(n), 1, MeshCarry.DEFAULT_TTL)
            }
        }
        assertKind(MeshCarryException.Kind.FRAGMENT) {
            MeshCarry.split(testDatagram(1), MeshCarry.MAX_MSG_ID + 1, MeshCarry.DEFAULT_TTL)
        }
        assertKind(MeshCarryException.Kind.FRAGMENT) {
            MeshCarry.split(testDatagram(1), -1, MeshCarry.DEFAULT_TTL)
        }
    }

    // -- T3: parse rejects. --

    @Test
    fun parseTellsOtherTrafficFromMalformedFragments() {
        val good = MeshCarry.split(testDatagram(2), 0xABCDEF, 6)[0]
        for ((name, f) in mapOf(
            "empty" to ByteArray(0),
            "one byte" to byteArrayOf(0x4B),
            "bad magic" to ByteArray(20) { 0x58 },
            "other mesh" to byteArrayOf(0x4B, 0x47) + ByteArray(20) { 1 },
        )) {
            try {
                MeshCarry.parseHeader(f)
                fail("$name: want NOT_KENGELE")
            } catch (e: MeshCarryException) {
                assertEquals(name, MeshCarryException.Kind.NOT_KENGELE, e.kind)
            }
        }
        // Bare "KC" magic is a truncated Kengele fragment, not other traffic.
        assertKind(MeshCarryException.Kind.FRAGMENT) {
            MeshCarry.parseHeader(byteArrayOf(0x4B, 0x43))
        }
        fun mutate(i: Int, v: Byte): ByteArray = good.copyOf().also { it[i] = v }
        val bad = mapOf(
            "bad version" to mutate(2, 0x02),
            "total zero" to mutate(4, 0),
            "index past end" to mutate(5, 2),
            "truncated" to good.copyOfRange(0, MeshCarry.HEADER_LEN),
            "oversized" to good + ByteArray(MeshCarry.FRAGMENT_MTU),
            "short non-last" to good.copyOfRange(0, MeshCarry.HEADER_LEN + 100),
        )
        for ((name, f) in bad) {
            try {
                MeshCarry.parseHeader(f)
                fail("$name: want FRAGMENT")
            } catch (e: MeshCarryException) {
                assertEquals(name, MeshCarryException.Kind.FRAGMENT, e.kind)
            }
        }
        // TTL 0 parses: expiry is enforced by the node, not the parser.
        val zero = MeshCarry.parseHeader(mutate(3, 0))
        assertEquals(0, zero.header.ttl)
    }

    // -- T2: reassembly round-trip, any order, dups ignored. --

    @Test
    fun reassemblerRoundTripsInAnyOrder() {
        val dg = testDatagram(0x3C)
        val frags = MeshCarry.split(dg, 0x010203, MeshCarry.DEFAULT_TTL)
        for (order in listOf(listOf(0, 1), listOf(1, 0))) {
            val r = Reassembler()
            var out: Reassembler.AddResult = Reassembler.AddResult.Ignored
            for ((step, i) in order.withIndex()) {
                out = r.add(frags[i])
                if (step == 0) assertTrue(out is Reassembler.AddResult.Incomplete)
            }
            assertTrue(out is Reassembler.AddResult.Complete)
            assertArrayEquals(dg, (out as Reassembler.AddResult.Complete).datagram)
            assertEquals(0, r.pending())
        }
        // A duplicate of the first fragment is ignored; the group still
        // completes exactly once.
        val r = Reassembler()
        assertTrue(r.add(frags[0]) is Reassembler.AddResult.Incomplete)
        assertTrue(r.add(frags[0]) is Reassembler.AddResult.Ignored)
        val done = r.add(frags[1])
        assertTrue(done is Reassembler.AddResult.Complete)
        assertArrayEquals(dg, (done as Reassembler.AddResult.Complete).datagram)
    }

    // -- T8 (reassembly half): mixed traffic ignored. --

    @Test
    fun reassemblerIgnoresMixedTraffic() {
        val dg = testDatagram(0x77)
        val frags = MeshCarry.split(dg, 0x0B0B0B, MeshCarry.DEFAULT_TTL)
        val r = Reassembler()
        val junk = listOf(
            "hello from another mesh".toByteArray(),
            ByteArray(150),
            byteArrayOf(0x4B, 0x47, 1, 1, 0) + ByteArray(40) { 9 },
        )
        for (j in junk) assertTrue(r.add(j) is Reassembler.AddResult.Ignored)
        assertTrue(r.add(frags[0]) is Reassembler.AddResult.Incomplete)
        for (j in junk) assertTrue(r.add(j) is Reassembler.AddResult.Ignored)
        val done = r.add(frags[1])
        assertTrue(done is Reassembler.AddResult.Complete)
        assertArrayEquals(dg, (done as Reassembler.AddResult.Complete).datagram)
    }

    // -- T3: eviction + expiry + conflicts + wrong sizes. --

    @Test
    fun reassemblerEvictsOldestPastCap() {
        val r = Reassembler(maxGroups = 2)
        val dg = testDatagram(9)
        val f0 = MeshCarry.split(dg, 1, MeshCarry.DEFAULT_TTL)
        val f1 = MeshCarry.split(dg, 2, MeshCarry.DEFAULT_TTL)
        val f2 = MeshCarry.split(dg, 3, MeshCarry.DEFAULT_TTL)
        r.add(f0[0])
        r.add(f1[0])
        r.add(f2[0])
        assertEquals(2, r.pending())
        assertTrue(r.add(f1[1]) is Reassembler.AddResult.Complete)
        assertTrue(r.add(f2[1]) is Reassembler.AddResult.Complete)
        // The evicted group is gone: its second fragment starts a fresh,
        // incomplete group instead of completing anything.
        assertTrue(r.add(f0[1]) is Reassembler.AddResult.Incomplete)
        assertEquals(1, r.pending())
    }

    @Test
    fun reassemblerExpiresGroupsAfterTenMinuteWindow() {
        assertEquals(10L * 60 * 1000, MeshCarry.REASSEMBLE_TIMEOUT_MS)
        var now = 1_000_000L
        val r = Reassembler(timeoutMs = 60_000, clock = { now })
        val dg = testDatagram(11)
        val frags = MeshCarry.split(dg, 0x0E0E0E, MeshCarry.DEFAULT_TTL)
        r.add(frags[0])
        r.evictExpired(now + 120_000)
        assertEquals(0, r.pending())
        // A late fragment starts over instead of joining stale state, and
        // a resend of the full datagram completes.
        assertTrue(r.add(frags[1]) is Reassembler.AddResult.Incomplete)
        val done = r.add(frags[0])
        assertTrue(done is Reassembler.AddResult.Complete)
        assertArrayEquals(dg, (done as Reassembler.AddResult.Complete).datagram)
        // Fresh groups survive an eviction pass at the current time.
        now += 120_000
        val r2 = Reassembler(timeoutMs = 60_000, clock = { now })
        r2.add(frags[0])
        r2.evictExpired(now)
        assertEquals(1, r2.pending())
    }

    @Test
    fun reassemblerDropsConflictingPartCount() {
        val r = Reassembler()
        val frags = MeshCarry.split(testDatagram(13), 0x0F0F0F, MeshCarry.DEFAULT_TTL)
        r.add(frags[0])
        val clash = frags[0].copyOf().also { it[4] = 1; it[5] = 0 }
        assertKind(MeshCarryException.Kind.FRAGMENT) { r.add(clash) }
        assertEquals(0, r.pending())
    }

    @Test
    fun reassemblerAcceptsOnlyExact228ByteCompletions() {
        // total=1 with a full chunk: completes to 141 bytes, not 228.
        val solo = ByteArray(MeshCarry.HEADER_LEN + MeshCarry.MAX_CHUNK)
        solo[0] = 0x4B
        solo[1] = 0x43
        solo[2] = MeshCarry.VERSION.toByte()
        solo[3] = MeshCarry.DEFAULT_TTL.toByte()
        solo[4] = 1
        solo[5] = 0
        solo[6] = 0x11
        solo[7] = 0x22
        solo[8] = 0x33
        val r = Reassembler()
        assertKind(MeshCarryException.Kind.DATAGRAM_SIZE) { r.add(solo) }
        // total=3: even the smallest completion (2x141+1) exceeds 228.
        val triple = solo.copyOf().also {
            it[4] = 3
            it[5] = 0
            it[6] = 0x44
            it[7] = 0x55
            it[8] = 0x66
        }
        assertKind(MeshCarryException.Kind.DATAGRAM_SIZE) { r.add(triple) }
    }

    // -- T4: multi-hop delivery with hop counts. --

    @Test
    fun multiHopDeliversByteIdenticalAtFarEnd() {
        val m = lineMesh()
        m.nextId = 0x123456
        val dg = testDatagram(0x5A)
        m.inject("A", dg)
        // Wave 1 fans A->B (2 relays at B), wave 2 fans B->C (2 relays at
        // C), wave 3 lands C's relays back at B, already seen: silence.
        for ((step, want) in listOf(2, 2, 0).withIndex()) {
            assertEquals("step ${step + 1}", want, m.step())
        }
        val got = m.deliveries("C")
        assertEquals(1, got.size)
        assertArrayEquals(dg, got[0].datagram)
        assertEquals("B", got[0].from)
        assertEquals(2, got[0].hops)
        val mid = m.deliveries("B")
        assertEquals(1, mid.size)
        assertEquals("A", mid[0].from)
        assertEquals(1, mid[0].hops)
        assertTrue(m.deliveries("A").isEmpty())
    }

    @Test
    fun ttlFallsOncePerHop() {
        assertEquals(7, MeshCarry.DEFAULT_TTL)
        val m = lineMesh()
        val relayTtls = mutableListOf<Int>()
        m.onTrace = { ev ->
            if (ev.kind == TestMesh.Kind.RELAY) relayTtls.add(ev.ttl)
        }
        m.inject("A", testDatagram(1))
        m.run(10)
        // B relays at TTL 6, C relays at TTL 5: the 7 the sender set falls
        // once per hop.
        assertEquals(listOf(6, 6, 5, 5), relayTtls)
        // A bare node relays a copy with TTL-1 and keeps the original intact.
        val n = BlindNode("solo")
        val frag = MeshCarry.split(testDatagram(2), 0x999999, 7)[0]
        val res = n.receive(frag)
        assertNotNull(res.relay)
        assertEquals(7, MeshCarry.parseHeader(frag).header.ttl)
        assertEquals(6, MeshCarry.parseHeader(res.relay!!).header.ttl)
    }

    // -- T5: TTL expiry. --

    @Test
    fun ttlExpiryStopsTheFlood() {
        val m = lineMesh()
        m.sendTtl = 1
        m.inject("A", testDatagram(2))
        m.run(10)
        assertEquals(1, m.deliveries("B").size)
        assertTrue(m.deliveries("C").isEmpty())
        // A TTL-0 fragment is dropped even by a bare node.
        val n = BlindNode("lonely")
        val frag = MeshCarry.split(testDatagram(3), 0x777777, 0)[0]
        val res = n.receive(frag)
        assertNull(res.relay)
        assertNull(res.delivery)
    }

    // -- T6: the honest limit is silence, not an error. --

    @Test
    fun isolatedNodeDeliversNothing() {
        val m = TestMesh()
        m.addNode("A")
        m.inject("A", testDatagram(4))
        m.run(5)
        assertEquals(0, m.step())
        assertTrue(m.deliveries("A").isEmpty())
    }

    // -- T7: flood dedup by sha256 seen-set. --

    @Test
    fun diamondDeliversExactlyOnce() {
        val m = TestMesh()
        for (id in listOf("A", "B", "C", "D")) m.addNode(id)
        m.link("A", "B")
        m.link("A", "C")
        m.link("B", "D")
        m.link("C", "D")
        m.inject("A", testDatagram(5))
        m.run(20)
        assertEquals(1, m.deliveries("D").size)
        // The second copy of the same datagram is dropped by hash even
        // under a fresh message id.
        val d = m.node("D")!!
        val again = MeshCarry.split(testDatagram(5), 0xEEEEEE, MeshCarry.DEFAULT_TTL)
        var delivered = 0
        for (f in again) if (d.receive(f).delivery != null) delivered++
        assertEquals(0, delivered)
    }

    // -- T8 (simulator half): garbage changes nothing. --

    @Test
    fun mixedTrafficThroughChainStillDelivers() {
        val m = lineMesh()
        m.inject("A", testDatagram(6))
        val b = m.node("B")!!
        for (junk in listOf(ByteArray(0), "not kengele".toByteArray(), ByteArray(150) { 0xFF.toByte() })) {
            val res = b.receive(junk)
            assertNull(res.relay)
            assertNull(res.delivery)
        }
        m.run(10)
        val got = m.deliveries("C")
        assertEquals(1, got.size)
        assertArrayEquals(testDatagram(6), got[0].datagram)
    }

    @Test
    fun droppingMiddleNodeProvesHonestSilence() {
        val m = lineMesh()
        m.node("B")!!.drop = true
        m.inject("A", testDatagram(7))
        m.run(10)
        assertTrue(m.deliveries("C").isEmpty())
    }

    // -- Dense crowd: TTL 5 at 6+ links. --

    @Test
    fun denseCrowdCapsBroadcastTtl() {
        assertEquals(6, MeshCarry.DENSE_CROWD_LINKS)
        assertEquals(5, MeshCarry.DENSE_CROWD_TTL)
        assertEquals(7, MeshCarry.broadcastTtl(0))
        assertEquals(7, MeshCarry.broadcastTtl(5))
        assertEquals(5, MeshCarry.broadcastTtl(6))
        assertEquals(5, MeshCarry.broadcastTtl(20))
        assertEquals(3, MeshCarry.broadcastTtl(6, want = 3))
        val m = TestMesh()
        m.addNode("hub")
        for (id in listOf("p1", "p2", "p3", "p4", "p5", "p6")) m.link("hub", id)
        val broadcastTtls = mutableListOf<Int>()
        m.onTrace = { ev ->
            if (ev.kind == TestMesh.Kind.BROADCAST) broadcastTtls.add(ev.ttl)
        }
        m.inject("hub", testDatagram(8))
        assertEquals(12, broadcastTtls.size) // 2 frags x 6 peers
        assertTrue(broadcastTtls.all { it == MeshCarry.DENSE_CROWD_TTL })
    }

    @Test
    fun freshMsgIdsFit24Bits() {
        repeat(16) {
            val id = MeshCarry.freshMsgId()
            assertTrue(id in 0..MeshCarry.MAX_MSG_ID)
        }
    }

    // -- T9a: relay works on random blobs with no message layer involved. --

    @Test
    fun relayWorksOnRandomBlobs() {
        val dg = ByteArray(MeshCarry.DATAGRAM_SIZE).also(SecureRandom()::nextBytes)
        val frags = MeshCarry.split(dg, 0xC0FFEE, MeshCarry.DEFAULT_TTL)
        val r = Reassembler()
        var out: Reassembler.AddResult = Reassembler.AddResult.Ignored
        for (f in frags) out = r.add(f)
        assertTrue(out is Reassembler.AddResult.Complete)
        assertArrayEquals(dg, (out as Reassembler.AddResult.Complete).datagram)
        val m = lineMesh()
        m.nextId = 42
        m.inject("A", dg)
        m.run(10)
        val got = m.deliveries("C")
        assertEquals(1, got.size)
        assertArrayEquals(dg, got[0].datagram)
    }

    // -- T9b: a sealed check-in and a sealed panic are indistinguishable. --

    @Test
    fun sealedCheckInAndPanicAreIndistinguishableOnTheCarrier() {
        // Made-up keys, generated deterministically for this test only.
        val activistPriv = Vectors.testPriv("mesh-test/activist")
        val guardianPub = KengeleMessage.publicKey(Vectors.testPriv("mesh-test/guardian"))
        val created = 1_767_200_000L // fixed made-up drill timestamp
        val checkIn = KengeleMessage.seal(
            KengeleMessage.Event(KengeleMessage.Kind.CHECK_IN, created, 7, "Kisumu".toByteArray()),
            activistPriv, guardianPub,
        )
        val panic = KengeleMessage.seal(
            KengeleMessage.Event(KengeleMessage.Kind.PANIC, created, 8, "Mtihani".toByteArray()),
            activistPriv, guardianPub,
        )
        assertEquals(MeshCarry.DATAGRAM_SIZE, checkIn.size)
        assertEquals(MeshCarry.DATAGRAM_SIZE, panic.size)

        val fragsA = MeshCarry.split(checkIn, 0xAAAAAA, MeshCarry.DEFAULT_TTL)
        val fragsB = MeshCarry.split(panic, 0xBBBBBB, MeshCarry.DEFAULT_TTL)
        assertEquals(fragsA.size, fragsB.size)
        for (i in fragsA.indices) {
            assertEquals("frag $i size", fragsA[i].size, fragsB[i].size)
            val ha = MeshCarry.parseHeader(fragsA[i]).header
            val hb = MeshCarry.parseHeader(fragsB[i]).header
            assertEquals("frag $i shape", ha.copy(msgId = 0), hb.copy(msgId = 0))
        }

        fun traceRun(datagram: ByteArray): Pair<List<Int>, List<TestMesh.TraceEvent>> {
            val m = lineMesh()
            m.nextId = 0x777001
            val events = mutableListOf<TestMesh.TraceEvent>()
            m.onTrace = { events.add(it) }
            m.inject("A", datagram)
            val counts = mutableListOf<Int>()
            repeat(10) {
                val n = m.step()
                counts.add(n)
                if (n == 0) return counts to events
            }
            return counts to events
        }
        val (countsA, traceA) = traceRun(checkIn)
        val (countsB, traceB) = traceRun(panic)
        assertEquals(countsA, countsB)
        assertEquals(traceA, traceB)
    }

    // -- T9a/T10: blindness and memory-only, enforced on the sources. --

    private fun meshSourceDir(): File {
        val candidates = listOf(
            File("src/main/java/com/altradits/kengele/mesh"),
            File("message/src/main/java/com/altradits/kengele/mesh"),
            File("../message/src/main/java/com/altradits/kengele/mesh"),
            File("../../android/message/src/main/java/com/altradits/kengele/mesh"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("mesh sources not found under android/message")
    }

    @Test
    fun relayPathCannotNameTheDecryptor() {
        // Import lines only (like the Go test's ImportsOnly parse): comments
        // may describe the rule, but no import may name the decryptor.
        val files = meshSourceDir().listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val imports = f.readText().lines().filter { it.trimStart().startsWith("import ") }
            assertFalse(
                "${f.name} imports the message layer: the relay path must stay blind",
                imports.any { it.contains("kengele.message") || it.contains("KengeleMessage") },
            )
        }
    }

    @Test
    fun carryPathIsMemoryOnlyAndNeverTouchesGps() {
        // No exceptions in this home: the carry path — wire core, radio
        // queues, duty cycle, permission map — keeps everything in memory.
        // (The activist app's MeshService reads the watch window through
        // AppState, but that lifecycle file lives in :app, not here.)
        val files = meshSourceDir().listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            val imports = text.lines().filter { it.trimStart().startsWith("import ") }
            assertFalse(
                "${f.name} imports location: no GPS anywhere",
                imports.any { it.contains("android.location") } ||
                    text.contains("LocationManager") ||
                    text.contains("FusedLocation"),
            )
            assertFalse(
                "${f.name} touches storage: carry state must stay in memory",
                text.contains("SharedPreferences") ||
                    text.contains("EncryptedPrefs") ||
                    imports.any { it.contains("java.io") } ||
                    text.contains("openFileOutput") ||
                    text.contains("getDatabasePath"),
            )
        }
    }
}
