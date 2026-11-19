package com.altradits.kengele.message.nostr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** The NIP-44 base64 string layer against the Go vector. */
class Nip44StringsTest {
    @Test
    fun stringVectorMatchesGo() {
        val v = NostrVectors.obj("nip44")
        fun s(k: String) = (v.fields[k] as NostrJson.Str).v
        val ck = NostrVectors.hexToBytes(s("ck"))
        val nonce = NostrVectors.hexToBytes(s("nonce"))
        val plain = s("plaintext").toByteArray(Charsets.UTF_8)
        assertEquals(s("payload_b64"), Nip44Strings.encryptToString(plain, ck, nonce))
        assertArrayEquals(plain, Nip44Strings.decryptString(s("payload_b64"), ck))
    }

    @Test
    fun roundTripAndRejects() {
        val ck = NostrVectors.hexToBytes((NostrVectors.obj("nip44").fields["ck"] as NostrJson.Str).v)
        val plain = "Habari za Kisumu".toByteArray(Charsets.UTF_8)
        val b64 = Nip44Strings.encryptToString(plain, ck)
        assertArrayEquals(plain, Nip44Strings.decryptString(b64, ck))
        for (bad in listOf("!!!", b64.dropLast(4) + "AAAA", "")) {
            try {
                Nip44Strings.decryptString(bad, ck)
                fail("accepted $bad")
            } catch (e: NostrException) {
                // Expected.
            }
        }
    }
}
