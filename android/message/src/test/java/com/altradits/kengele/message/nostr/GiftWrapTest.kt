package com.altradits.kengele.message.nostr

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Vectors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Gift wrap/unwrap against the Go vectors, plus round-trips and rejects. */
class GiftWrapTest {
    private val senderPriv = NostrVectors.hex("sender_priv")
    private val senderPub = NostrVectors.hex("sender_pub")
    private val recipientPriv = NostrVectors.hex("recipient_priv")
    private val recipientPub = NostrVectors.hex("recipient_pub")
    private val now = NostrVectors.num("now")

    @Test
    fun unwrapGoWrapAndOpenMessage() {
        val gw = NostrEvent.parseJson(NostrVectors.str("wrap_json"))
        val open = GiftWrap.unwrap(gw, recipientPriv)
        assertEquals(NostrVectors.str("wire_text"), KengeleMessage.encodeText(open.wire))
        assertArrayEquals(senderPub, open.sealer)
        // The wire message itself opens: a panic, seq 7, from the sender.
        val event = KengeleMessage.open(open.wire, recipientPriv, listOf(open.sealer), now)
        assertEquals(KengeleMessage.Kind.PANIC, event.kind)
        assertEquals(7L, event.seq)
        assertEquals("Kenyatta Ave", String(event.body, Charsets.UTF_8))
        assertArrayEquals(senderPub, event.signer)
    }

    @Test
    fun goLayersHaveTheRightShape() {
        val seal = NostrEvent.parseJson(NostrVectors.str("seal_json"))
        assertEquals(GiftWrap.KIND_SEAL, seal.kind)
        assertTrue(seal.tags.isEmpty())
        assertEquals(NostrVectors.str("sender_pub"), seal.pubkey)
        val rumor = NostrEvent.parseJson(NostrVectors.str("rumor_json"))
        assertEquals(GiftWrap.KIND_RUMOR, rumor.kind)
        assertEquals(NostrVectors.str("sender_pub"), rumor.pubkey)
        assertEquals(now, rumor.createdAt)
        assertEquals(listOf(listOf("p", NostrVectors.str("recipient_pub"))), rumor.tags)
        assertEquals(NostrVectors.str("wire_text"), rumor.content)
        // Both timestamps are backdated, never the real time or the future.
        assertTrue(seal.createdAt in (now - GiftWrap.MAX_JITTER_SECS) until now)
        val gw = NostrEvent.parseJson(NostrVectors.str("wrap_json"))
        assertTrue(gw.createdAt in (now - GiftWrap.MAX_JITTER_SECS) until now)
    }

    @Test
    fun fixedInputWrapMatchesGoByteForByteExceptSignatures() {
        val fixed = NostrVectors.obj("wrap_fixed")
        fun s(k: String) = (fixed.fields[k] as NostrJson.Str).v
        fun n(k: String) = (fixed.fields[k] as NostrJson.Num).long()
        val wire = KengeleMessage.decodeText(NostrVectors.str("wire_text"))
        val jitters = ArrayDeque(listOf(now - n("seal_at"), now - n("wrap_at")))
        val mine = GiftWrap.wrap(
            wire = wire,
            senderPriv = senderPriv,
            recipientX = recipientPub,
            nowSecs = now,
            jitter = { jitters.removeFirst() },
            oneTimePriv = NostrVectors.hexToBytes(s("onetime_priv")),
            sealNonce = NostrVectors.hexToBytes(s("seal_nonce")),
            wrapNonce = NostrVectors.hexToBytes(s("wrap_nonce")),
        )
        val go = NostrEvent.parseJson(s("wrap_json"))
        // Same timestamps and one-time key; the ciphertexts and ids differ
        // only because the seal inside carries a fresh random signature.
        assertEquals(go.createdAt, mine.createdAt)
        assertEquals(go.pubkey, mine.pubkey)
        assertEquals(go.tags, mine.tags)
        assertEquals(go.kind, mine.kind)
        assertTrue(mine.checkSignature())
        assertTrue(go.checkSignature())
        // The seal matches except its random signature ...
        val mySeal = NostrEvent.parseJson(decryptLayer(mine.content, mine.pubkey))
        val goSeal = NostrEvent.parseJson(s("seal_json"))
        assertEquals(goSeal.kind, mySeal.kind)
        assertEquals(goSeal.pubkey, mySeal.pubkey)
        assertEquals(goSeal.createdAt, mySeal.createdAt)
        assertEquals(goSeal.tags, mySeal.tags)
        assertEquals(goSeal.content, mySeal.content)
        assertEquals(goSeal.id, mySeal.id)
        assertTrue(mySeal.checkSignature())
        // ... and the unsigned rumor matches byte for byte.
        val myRumor = NostrEvent.parseJson(decryptLayer(mySeal.content, mySeal.pubkey))
        assertEquals(s("rumor_json"), myRumor.toJson())
    }

    @Test
    fun roundTripWithFreshRandomness() {
        val wire = KengeleMessage.decodeText(NostrVectors.str("wire_text"))
        val gw = GiftWrap.wrap(wire, senderPriv, recipientPub, now)
        val open = GiftWrap.unwrap(gw, recipientPriv)
        assertArrayEquals(wire, open.wire)
        assertArrayEquals(senderPub, open.sealer)
    }

    @Test
    fun outerEventHidesSenderTimeAndContent() {
        val wire = KengeleMessage.decodeText(NostrVectors.str("wire_text"))
        val text = KengeleMessage.encodeText(wire)
        val senderHex = NostrVectors.str("sender_pub")
        val seenKeys = mutableSetOf<String>()
        repeat(10) {
            val gw = GiftWrap.wrap(wire, senderPriv, recipientPub, now)
            val raw = gw.toJson()
            assertTrue(senderHex !in raw)
            assertTrue(text !in raw && text.take(40) !in raw)
            assertEquals(GiftWrap.KIND_GIFT_WRAP, gw.kind)
            assertTrue(gw.pubkey != senderHex && seenKeys.add(gw.pubkey))
            assertTrue(gw.createdAt in (now - GiftWrap.MAX_JITTER_SECS) until now)
            assertEquals(listOf(listOf("p", NostrVectors.str("recipient_pub"))), gw.tags)
        }
    }

    @Test
    fun rejects() {
        val good = NostrEvent.parseJson(NostrVectors.str("wrap_json"))
        // Wrong recipient.
        try {
            GiftWrap.unwrap(good, Vectors.testPriv("nostr-gw-stranger"))
            fail("accepted for wrong recipient")
        } catch (e: NostrException) {
            assertTrue(e.notForUs)
        }
        // Tampered content breaks the signature.
        try {
            GiftWrap.unwrap(good.copy(content = good.content.dropLast(4) + "AAAA"), recipientPriv)
            fail("accepted tampered wrap")
        } catch (e: NostrException) {
            // Expected.
        }
        // Wrong kind.
        try {
            GiftWrap.unwrap(good.copy(kind = GiftWrap.KIND_RUMOR), recipientPriv)
            fail("accepted wrong kind")
        } catch (e: NostrException) {
            // Expected.
        }
        // Seal signed by someone else than the rumor author.
        try {
            GiftWrap.unwrap(NostrEvent.parseJson(NostrVectors.str("forged_json")), recipientPriv)
            fail("accepted forged seal")
        } catch (e: NostrException) {
            // Expected.
        }
        // Refuses to wrap anything that is not a Kengele message.
        for (bad in listOf("hello".toByteArray(), ByteArray(KengeleMessage.WIRE_SIZE))) {
            try {
                GiftWrap.wrap(bad, senderPriv, recipientPub, now)
                fail("wrapped a non-Kengele payload")
            } catch (e: NostrException) {
                // Expected.
            }
        }
    }

    private fun decryptLayer(content: String, peerHex: String): String {
        val ck = Nip44Strings.conversationKey(recipientPriv, NostrVectors.hexToBytes(peerHex))
        return Nip44Strings.decryptString(content, ck).toString(Charsets.UTF_8)
    }
}
