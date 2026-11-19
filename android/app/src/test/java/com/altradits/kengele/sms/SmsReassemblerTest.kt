package com.altradits.kengele.sms

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Vectors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Path A / Path B reassembly, caps, expiry and (signer, seq) dedup.
 * Keys are the made-up shared vectors; numbers are fictional 555-01XX.
 */
class SmsReassemblerTest {
    private val activistPriv = Vectors.hex("activist_priv_hex")
    private val guardianPriv = Vectors.hex("guardian_priv_hex")
    private val activistPub = Vectors.hex("activist_pub_hex")
    private val guardianPub = Vectors.hex("guardian_pub_hex")
    private val created = Vectors.num("event_created_unix")
    private val now = created + 3600

    private val events = mutableListOf<KengeleMessage.Event>()

    private fun decoder(): KengeleSmsDecoder =
        KengeleSmsDecoder({ guardianPriv }, { listOf(activistPub) }, { now })

    private fun reassembler() = SmsReassembler(decoder(), events::add)

    private fun sealText(
        kind: KengeleMessage.Kind = KengeleMessage.Kind.CHECK_IN,
        seq: Long = 1,
        body: String = "Kisumu",
    ): String {
        val event = KengeleMessage.Event(kind, created, seq, body.toByteArray(Charsets.UTF_8))
        return KengeleMessage.encodeText(KengeleMessage.seal(event, activistPriv, guardianPub))
    }

    private fun halves(text: String): Pair<String, String> {
        assertEquals(KengeleMessage.TEXT_SIZE, text.length)
        return text.substring(0, 153) to text.substring(153)
    }

    /** 153-char base64url filler that can never open (pairs decode wrong). */
    private fun filler(i: Int): String = ("FILLER$i").padEnd(153, 'x')

    @Test
    fun pathAFullTextOpens() {
        val text = sealText(seq = 7)
        reassembler().onText(NUMBER_A, text, T0)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(KengeleMessage.Kind.CHECK_IN, event.kind)
        assertEquals(7L, event.seq)
        assertEquals("Kisumu", String(event.body, Charsets.UTF_8))
        assertArrayEquals(activistPub, event.signer)
    }

    @Test
    fun pathAIgnoresWhitespaceInsertedByPhones() {
        val text = sealText()
        val broken = text.chunked(50).joinToString("\n") + "  \n"
        reassembler().onText(NUMBER_A, broken, T0)
        assertEquals(1, events.size)
    }

    @Test
    fun pathAIgnoresTextsThatAreNotOurs() {
        val r = reassembler()
        r.onText(NUMBER_A, "hello, this is an ordinary SMS", T0)
        r.onText(NUMBER_A, "A".repeat(304), T0) // decodes, but is not a Kengele message
        r.onText(NUMBER_A, "A".repeat(303) + "!", T0) // not even the alphabet
        r.onText(NUMBER_A, "A".repeat(300), T0) // wrong size
        assertTrue(events.isEmpty())
        assertEquals(0, r.heldCount())
    }

    @Test
    fun pathBPairOpensInEitherArrivalOrder() {
        val (h1, h2) = halves(sealText(seq = 9))
        assertEquals(153, h1.length)
        assertEquals(151, h2.length)

        val first = reassembler()
        first.onText(NUMBER_A, h1, T0)
        assertTrue(events.isEmpty())
        assertEquals(1, first.heldCount())
        first.onText(NUMBER_A, h2, T0 + 1000)
        assertEquals(1, events.size)
        assertEquals(9L, events.single().seq)
        assertEquals(0, first.heldCount())

        events.clear()
        val second = reassembler()
        second.onText(NUMBER_A, h2, T0)
        assertTrue(events.isEmpty())
        second.onText(NUMBER_A, h1, T0 + 1000)
        assertEquals(1, events.size)
        assertEquals(9L, events.single().seq)
    }

    @Test
    fun pathBTriesBothOrders() {
        val tried = mutableListOf<String>()
        // 153/151-char shapes so they are held as parts; content is arbitrary.
        val h1 = "a".repeat(153)
        val h2 = "b".repeat(151)
        val opening = object : SmsDecoder {
            override fun tryOpen(text: String): KengeleMessage.Event? {
                tried.add(text)
                // Only the new-part-first order opens.
                return if (text == h2 + h1) {
                    KengeleMessage.Event(KengeleMessage.Kind.CHECK_IN, created, 5)
                } else {
                    null
                }
            }
        }
        SmsReassembler(opening, events::add).let { r ->
            r.onText(NUMBER_A, h1, T0)
            r.onText(NUMBER_A, h2, T0)
        }
        assertEquals(listOf(h1 + h2, h2 + h1), tried)
        assertEquals(1, events.size)
        assertEquals(5L, events.single().seq)
    }

    @Test
    fun pathBRejectsPartsFromDifferentNumbers() {
        val (h1, h2) = halves(sealText())
        val r = reassembler()
        r.onText(NUMBER_A, h1, T0)
        r.onText(NUMBER_B, h2, T0)
        assertTrue(events.isEmpty())
        assertEquals(2, r.heldCount())
    }

    @Test
    fun pathBAlteredCharacterIsDropped() {
        val (h1, h2) = halves(sealText())
        val altered = h2.substring(0, 100) + (if (h2[100] == 'A') 'B' else 'A') + h2.substring(101)
        val r = reassembler()
        r.onText(NUMBER_A, h1, T0)
        r.onText(NUMBER_A, altered, T0)
        assertTrue(events.isEmpty())
        assertEquals(2, r.heldCount())
    }

    @Test
    fun duplicatesBySignerAndSeqAreDropped() {
        val r = reassembler()
        val text = sealText(seq = 3)
        r.onText(NUMBER_A, text, T0)
        r.onText(NUMBER_A, text, T0 + 1000) // same SMS again
        val (h1, h2) = halves(text)
        r.onText(NUMBER_A, h1, T0 + 2000) // same event later via split delivery
        r.onText(NUMBER_A, h2, T0 + 2000)
        assertEquals(1, events.size)
        // A different seq from the same signer still counts.
        r.onText(NUMBER_A, sealText(seq = 4), T0 + 3000)
        assertEquals(2, events.size)
    }

    @Test
    fun perNumberCapEvictsOldest() {
        val (h1, h2) = halves(sealText())
        val r = reassembler()
        r.onText(NUMBER_A, h1, T0)
        for (i in 0 until 4) r.onText(NUMBER_A, filler(i), T0)
        assertEquals(4, r.heldCount())
        r.onText(NUMBER_A, h2, T0) // h1 was evicted: no partner left
        assertTrue(events.isEmpty())

        // ...but the newest parts survive.
        val r2 = reassembler()
        for (i in 0 until 4) r2.onText(NUMBER_A, filler(i), T0)
        r2.onText(NUMBER_A, h1, T0)
        assertEquals(4, r2.heldCount())
        r2.onText(NUMBER_A, h2, T0)
        assertEquals(1, events.size)
    }

    @Test
    fun totalCapEvictsOldest() {
        val (h1, h2) = halves(sealText())
        val r = reassembler()
        r.onText(NUMBER_A, h1, T0)
        var i = 0
        for (n in 0 until 16) {
            for (k in 0 until 4) r.onText("+1555010${1000 + n * 4 + k}", filler(i++), T0)
        }
        assertEquals(64, r.heldCount())
        r.onText(NUMBER_A, h2, T0)
        assertTrue("h1 must have been evicted", events.isEmpty())

        val r2 = reassembler()
        i = 0
        for (n in 0 until 16) {
            for (k in 0 until 4) r2.onText("+1555010${1000 + n * 4 + k}", filler(i++), T0)
        }
        r2.onText(NUMBER_A, h1, T0)
        r2.onText(NUMBER_A, h2, T0)
        assertEquals(1, events.size)
    }

    @Test
    fun stalePartsExpireAfterTenMinutes() {
        val (h1, h2) = halves(sealText())
        val r = reassembler()
        r.onText(NUMBER_A, h1, T0)
        r.onText(NUMBER_A, h2, T0 + SmsReassembler.PART_WINDOW_MILLIS + 1)
        assertTrue(events.isEmpty())
        assertEquals(1, r.heldCount()) // only h2 remains

        // Exactly at the window edge it still pairs.
        events.clear()
        val r2 = reassembler()
        r2.onText(NUMBER_A, h1, T0)
        r2.onText(NUMBER_A, h2, T0 + SmsReassembler.PART_WINDOW_MILLIS)
        assertEquals(1, events.size)
    }

    @Test
    fun seenSetEvictsOldest() {
        val signer = ByteArray(32) { 0x03 }
        // Deterministic seq per text, so re-feeding the first text replays
        // seq 1 and proves the eviction (a counting stub would mint a new,
        // never-seen seq and pass even without evicting).
        val counting = SmsDecoder { text ->
            val seq = text.substring(1).toLong() + 1
            KengeleMessage.Event(KengeleMessage.Kind.CHECK_IN, created, seq, signer = signer)
        }
        val seen = mutableListOf<KengeleMessage.Event>()
        val r = SmsReassembler(counting, seen::add)
        val texts = (0 until SmsReassembler.MAX_SEEN + 1).map { i -> "T%0303d".format(i) }
        for (text in texts) r.onText(NUMBER_A, text, T0)
        assertEquals(SmsReassembler.MAX_SEEN + 1, seen.size)
        assertEquals(SmsReassembler.MAX_SEEN, r.seenCount())
        // The first event was evicted from the dedup set: it counts again.
        val before = seen.size
        r.onText(NUMBER_A, texts[0], T0)
        assertEquals(before + 1, seen.size)
        assertEquals(1L, seen.last().seq)
    }

    @Test
    fun goldenVectorFromSpecSection10() {
        // The worked example in docs/sms-transport.md, section 10, with the
        // made-up test keys (activist 32 x 0xa1, guardian 32 x 0xb2).
        assertEquals(153, GOLDEN_PART1.length)
        assertEquals(151, GOLDEN_PART2.length)
        val full = GOLDEN_PART1 + GOLDEN_PART2

        val r = reassembler()
        r.onText(NUMBER_A, full, T0)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(KengeleMessage.Kind.CHECK_IN, event.kind)
        assertEquals(1L, event.seq)
        assertEquals("Kisumu", String(event.body, Charsets.UTF_8))
        assertEquals(1768486020L, event.createdAtSecs)
        assertArrayEquals(activistPub, event.signer)

        // Split delivery: parts arrive separately and still open.
        events.clear()
        val r2 = reassembler()
        r2.onText(NUMBER_A, GOLDEN_PART1, T0)
        assertTrue(events.isEmpty())
        r2.onText(NUMBER_A, GOLDEN_PART2, T0)
        assertEquals(1, events.size)

        // Wrong order as a single text is rejected...
        events.clear()
        val r3 = reassembler()
        r3.onText(NUMBER_A, GOLDEN_PART2 + GOLDEN_PART1, T0)
        assertTrue(events.isEmpty())
        // ...but as two parts the reassembler tries both orders.
        r3.onText(NUMBER_A, GOLDEN_PART2, T0)
        r3.onText(NUMBER_A, GOLDEN_PART1, T0)
        assertEquals(1, events.size)

        // Part 1 alone is held, never misread.
        events.clear()
        val r4 = reassembler()
        r4.onText(NUMBER_A, GOLDEN_PART1, T0)
        assertTrue(events.isEmpty())
        assertEquals(1, r4.heldCount())
    }

    companion object {
        const val NUMBER_A = "+15550100001"
        const val NUMBER_B = "+15550100002"
        const val T0 = 1780000000000L

        const val GOLDEN_PART1 =
            "AQIBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAUyZvPhQzh4ngEDhik-Aoido1CqOVI9_xNhkd6hmipmQnA2x0QkKn0zUw8Jg5DvBZU1e8gdqmMXi65zaDewNNnqYJ5Dd1i1gqcxEKeClRfmUg"
        const val GOLDEN_PART2 =
            "0R9Z_yvuO9ry8VPUB8faT5H2FQlDjEZmQ0CZnh9qSR3rFFD8EDFPQvS0aS1xXXTX5YKaCFjdWmWfDJe1cJ3Ui0bn4W0ObVUFwP3AODrSYCA95H6CVCASuZbGR9E3bLRszlh_vE7gvgVg6XCpukuGZEx"
    }
}
