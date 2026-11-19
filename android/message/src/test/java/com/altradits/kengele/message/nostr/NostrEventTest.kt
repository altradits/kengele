package com.altradits.kengele.message.nostr

import com.altradits.kengele.message.Vectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Nostr events against the Go-pinned id, JSON and signature vectors. */
class NostrEventTest {
    @Test
    fun canonicalSerializationAndIdsMatchGo() {
        for (item in NostrVectors.arr("event_ids")) {
            val v = item as NostrJson.Obj
            val tagsField = v.fields["tags"]
            val tags = if (tagsField is NostrJson.Arr) {
                tagsField.items.map { t -> (t as NostrJson.Arr).items.map { (it as NostrJson.Str).v } }
            } else {
                emptyList() // Go marshals nil tags as null; both serialize as [].
            }
            val ev = NostrEvent(
                pubkey = (v.fields["pubkey"] as NostrJson.Str).v,
                createdAt = (v.fields["created_at"] as NostrJson.Num).long(),
                kind = (v.fields["kind"] as NostrJson.Num).int(),
                tags = tags,
                content = (v.fields["content"] as NostrJson.Str).v,
            )
            val name = (v.fields["name"] as NostrJson.Str).v
            assertEquals("$name canonical", (v.fields["canonical_hex"] as NostrJson.Str).v, NostrVectors.bytesToHex(ev.canonicalBytes()))
            assertEquals("$name id", (v.fields["id"] as NostrJson.Str).v, ev.computeId())
        }
    }

    @Test
    fun goWrapSealAndRumorVerify() {
        val gw = NostrEvent.parseJson(NostrVectors.str("wrap_json"))
        assertEquals(GiftWrap.KIND_GIFT_WRAP, gw.kind)
        assertTrue(gw.checkId())
        assertTrue(gw.checkSignature())
        val seal = NostrEvent.parseJson(nested("seal_json"))
        assertEquals(GiftWrap.KIND_SEAL, seal.kind)
        assertTrue(seal.checkId())
        assertTrue(seal.checkSignature())
        // The rumor is unsigned by design, but its id still checks.
        val rumor = NostrEvent.parseJson(nested("rumor_json"))
        assertEquals(GiftWrap.KIND_RUMOR, rumor.kind)
        assertEquals("", rumor.sig)
        assertTrue(rumor.checkId())
        assertFalse(rumor.checkSignature())
    }

    @Test
    fun signRoundTrip() {
        val priv = Vectors.testPriv("nostr-event-sign")
        val ev = NostrEvent(
            kind = GiftWrap.KIND_GIFT_WRAP,
            createdAt = 1_800_000_000L,
            tags = listOf(listOf("p", NostrVectors.str("recipient_pub"))),
            content = "hello",
        ).sign(priv)
        assertTrue(ev.checkId())
        assertTrue(ev.checkSignature())
        assertEquals(64, ev.id.length)
        // Any change breaks the signature.
        assertFalse(ev.copy(content = "hellp").checkSignature())
        assertFalse(ev.copy(createdAt = ev.createdAt + 1).checkSignature())
        assertFalse(ev.copy(tags = listOf(listOf("p", NostrVectors.str("sender_pub")))).checkSignature())
        // A wrong id field alone fails the id check.
        assertFalse(ev.copy(id = "0".repeat(64)).checkId())
        // Garbage keys never verify.
        assertFalse(ev.copy(pubkey = "zz").checkSignature())
        assertFalse(ev.copy(sig = "00".repeat(64)).checkSignature())
    }

    @Test
    fun jsonShapeOmitsEmptyFieldsAndRoundTrips() {
        val rumor = NostrEvent(
            kind = GiftWrap.KIND_RUMOR,
            pubkey = NostrVectors.str("sender_pub"),
            createdAt = 1_800_000_100L,
            tags = listOf(listOf("p", NostrVectors.str("recipient_pub"))),
            content = "abc",
        )
        val withId = rumor.copy(id = rumor.computeId())
        val json = withId.toJson()
        assertTrue(json.startsWith("{\"kind\":14,\"id\":\""))
        assertTrue("\"sig\":" !in json)
        assertEquals(withId, NostrEvent.parseJson(json))
        // A signed event carries the sig and parses back identically.
        val signed = withId.sign(Vectors.testPriv("nostr-event-json"))
        assertTrue("\"sig\":\"" in signed.toJson())
        assertEquals(signed, NostrEvent.parseJson(signed.toJson()))
    }

    @Test
    fun parseRejectsMalformed() {
        for (bad in listOf("", "{", "[]", "{\"kind\":}", "{\"kind\":1}", "null", "[\"EVENT\"]")) {
            try {
                NostrEvent.parseJson(bad)
                fail("accepted $bad")
            } catch (e: NostrException) {
                // Expected.
            }
        }
        try {
            NostrEvent.parseJson("{\"kind\":1,\"created_at\":\"x\",\"tags\":[],\"content\":\"\"}")
            fail("accepted bad created_at")
        } catch (e: NostrException) {
            // Expected.
        }
    }

    private fun nested(key: String): String = NostrVectors.str(key)
}
