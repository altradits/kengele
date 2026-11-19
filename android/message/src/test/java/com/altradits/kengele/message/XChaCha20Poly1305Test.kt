package com.altradits.kengele.message

import com.altradits.kengele.message.nostr.NostrJson
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Loads the Go-sealed XChaCha20-Poly1305 vectors (`xchacha-vectors.json`). */
object XChaChaVectors {
    data class Vec(
        val name: String,
        val key: ByteArray,
        val nonce: ByteArray,
        val pt: ByteArray,
        val ad: ByteArray,
        val ct: ByteArray,
    )

    private val text: String = run {
        val viaClasspath = XChaChaVectors::class.java.getResourceAsStream("/xchacha-vectors.json")
            ?.use { String(it.readBytes(), Charsets.UTF_8) }
        if (viaClasspath != null) return@run viaClasspath
        sequenceOf(
            File("android/message/src/test/resources/xchacha-vectors.json"),
            File("../message/src/test/resources/xchacha-vectors.json"),
            File("src/test/resources/xchacha-vectors.json"),
        ).firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("xchacha-vectors.json not found")
    }

    val all: List<Vec> = run {
        val root = NostrJson.parse(text) as NostrJson.Obj
        val arr = root.fields["vectors"] as? NostrJson.Arr
            ?: throw AssertionError("xchacha-vectors.json has no vectors array")
        arr.items.map { item ->
            val o = item as NostrJson.Obj
            fun hex(key: String): ByteArray {
                val s = o.str(key)
                require(s.length % 2 == 0) { "odd hex" }
                return ByteArray(s.length / 2) { i ->
                    s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }
            Vec(o.str("name"), hex("key_hex"), hex("nonce_hex"), hex("pt_hex"), hex("ad_hex"), hex("ct_hex"))
        }
    }
}

/**
 * XChaCha20-Poly1305 against Go-sealed vectors, the RFC 8439 section A.5
 * vector (through the IETF core), and tamper cases.
 */
class XChaCha20Poly1305Test {
    @Test
    fun opensGoSealedVectors() {
        for (v in XChaChaVectors.all) {
            assertArrayEquals(
                "${v.name}: open",
                v.pt,
                XChaCha20Poly1305.open(v.key, v.nonce, v.ct, v.ad),
            )
        }
    }

    @Test
    fun sealsGoSealedVectors() {
        for (v in XChaChaVectors.all) {
            assertArrayEquals(
                "${v.name}: seal",
                v.ct,
                XChaCha20Poly1305.seal(v.key, v.nonce, v.pt, v.ad),
            )
        }
    }

    @Test
    fun matchesRfc8439A5() {
        // RFC 8439 section A.5, through the IETF core with the XChaCha step
        // bypassed; the expected bytes are x/crypto's own seal of these
        // inputs (which starts with the RFC's well-known d31a8d34 prefix).
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray(Charsets.UTF_8)
        val ad = hex("50515253c0c1c2c3c4c5c6c7")
        val ct = hex("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691")
        assertArrayEquals(ct, XChaCha20Poly1305.sealIetf(key, nonce, pt, ad))
        assertArrayEquals(pt, XChaCha20Poly1305.openIetf(key, nonce, ct, ad))
    }

    @Test
    fun rejectsTampering() {
        val v = XChaChaVectors.all.first { it.pt.isNotEmpty() }
        val badTag = v.ct.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val badCt = v.ct.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val badAd = v.ad.copyOf().also {
            if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte()
        }
        val badKey = v.key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val badNonce = v.nonce.copyOf().also { it[0] = (it[0] + 1).toByte() }
        for ((what, args) in listOf(
            "tag" to Triple(badTag, v.ad, v.key),
            "ciphertext" to Triple(badCt, v.ad, v.key),
            "key" to Triple(v.ct, v.ad, badKey),
        )) {
            try {
                XChaCha20Poly1305.open(args.third, v.nonce, args.first, args.second)
                fail("open with bad $what must fail")
            } catch (e: MessageException) {
                // Expected.
            }
        }
        if (v.ad.isNotEmpty()) {
            try {
                XChaCha20Poly1305.open(v.key, v.nonce, v.ct, badAd)
                fail("open with bad ad must fail")
            } catch (e: MessageException) {
                // Expected.
            }
        }
        try {
            XChaCha20Poly1305.open(v.key, badNonce, v.ct, v.ad)
            fail("open with bad nonce must fail")
        } catch (e: MessageException) {
            // Expected.
        }
        try {
            XChaCha20Poly1305.open(v.key, v.nonce, ByteArray(15), v.ad)
            fail("open with short ciphertext must fail")
        } catch (e: MessageException) {
            // Expected.
        }
    }

    @Test
    fun roundtrips() {
        val v = XChaChaVectors.all.first { it.pt.isNotEmpty() }
        val ct = XChaCha20Poly1305.seal(v.key, v.nonce, v.pt, v.ad)
        assertArrayEquals(v.pt, XChaCha20Poly1305.open(v.key, v.nonce, ct, v.ad))
        assertEquals(v.pt.size + 16, ct.size)
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
