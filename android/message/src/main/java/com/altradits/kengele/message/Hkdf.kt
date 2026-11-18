package com.altradits.kengele.message

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF with SHA-256 (RFC 5869), matching the NIP-44 key schedule used by
 * `internal/message` and go-nostr: Extract(salt "nip44-v2", IKM) then
 * Expand(info = nonce, length 76).
 */
internal object Hkdf {
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-Extract: PRK = HMAC-SHA256(salt, ikm). */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    /** HKDF-Expand: OKM of exactly [length] bytes. */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "bad HKDF length $length" }
        val out = ByteArray(length)
        var filled = 0
        var previous = ByteArray(0)
        var counter = 1
        while (filled < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - filled)
            previous.copyInto(out, filled, 0, take)
            filled += take
            counter++
        }
        return out
    }
}
