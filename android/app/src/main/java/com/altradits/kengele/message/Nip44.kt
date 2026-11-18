package com.altradits.kengele.message

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-44 v2 encryption, byte-for-byte compatible with go-nostr v0.52.3 as
 * used by `internal/message`:
 *
 * - conversation key = HKDF-Extract(salt "nip44-v2", ECDH x)
 * - message keys = HKDF-Expand(ck, info = nonce, 76 bytes): ChaCha key (32),
 *   ChaCha nonce (12), HMAC key (32)
 * - padded = u16be(len) || plaintext || zeroes to 2 + calcPadding(len)
 * - MAC = HMAC-SHA256(hmacKey, nonce || ciphertext)
 * - payload = 0x02 || nonce || ciphertext || MAC
 */
internal object Nip44 {
    const val VERSION: Byte = 0x02
    private const val MIN_PLAINTEXT = 1
    private const val MAX_PLAINTEXT = 0xFFFF

    class Keys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray)

    /** NIP-44 conversation key between our [priv] and the peer's x-only key. */
    fun conversationKey(priv: ByteArray, peerX: ByteArray): ByteArray {
        val shared = Secp256k1.ecdh(priv, peerX)
        return Hkdf.extract("nip44-v2".toByteArray(Charsets.UTF_8), shared)
    }

    fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): Keys {
        require(conversationKey.size == 32) { "conversation key must be 32 bytes" }
        require(nonce.size == 32) { "nonce must be 32 bytes" }
        val okm = Hkdf.expand(conversationKey, nonce, 76)
        return Keys(
            okm.copyOfRange(0, 32),
            okm.copyOfRange(32, 44),
            okm.copyOfRange(44, 76),
        )
    }

    /**
     * NIP-44 padding size, integer form of the spec's calc_padded_len (the Go
     * side spells the same function with floats; both agree on every size,
     * and Kengele only ever encrypts 160-byte plaintext, already a boundary).
     */
    fun calcPadding(size: Int): Int {
        if (size <= 32) return 32
        var power = 1
        while (power < size) power *= 2
        val chunk = maxOf(32, power / 8)
        return chunk * ((size - 1) / chunk + 1)
    }

    private fun hmac(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(nonce)
        mac.update(ciphertext)
        return mac.doFinal()
    }

    /** Encrypts [plaintext] with [conversationKey] and [nonce]; raw payload bytes. */
    fun encrypt(plaintext: ByteArray, conversationKey: ByteArray, nonce: ByteArray): ByteArray {
        if (plaintext.size < MIN_PLAINTEXT || plaintext.size > MAX_PLAINTEXT) {
            throw MessageException("plaintext must be 1..65535 bytes")
        }
        if (nonce.size != 32) throw MessageException("nonce must be 32 bytes")
        val keys = messageKeys(conversationKey, nonce)
        val padded = ByteArray(2 + calcPadding(plaintext.size))
        padded[0] = ((plaintext.size ushr 8) and 0xFF).toByte()
        padded[1] = (plaintext.size and 0xFF).toByte()
        plaintext.copyInto(padded, 2)
        val ciphertext = ChaCha20.xor(keys.chachaKey, keys.chachaNonce, padded)
        val mac = hmac(keys.hmacKey, nonce, ciphertext)
        val out = ByteArray(1 + 32 + ciphertext.size + 32)
        out[0] = VERSION
        nonce.copyInto(out, 1)
        ciphertext.copyInto(out, 33)
        mac.copyInto(out, 33 + ciphertext.size)
        return out
    }

    /** Decrypts a raw NIP-44 payload; throws [MessageException] on any failure. */
    fun decrypt(payload: ByteArray, conversationKey: ByteArray): ByteArray {
        if (payload.size < 99 || payload.size > 65603) throw MessageException("bad NIP-44 size")
        if (payload[0] != VERSION) throw MessageException("unknown NIP-44 version")
        val nonce = payload.copyOfRange(1, 33)
        val ciphertext = payload.copyOfRange(33, payload.size - 32)
        val givenMac = payload.copyOfRange(payload.size - 32, payload.size)
        val keys = messageKeys(conversationKey, nonce)
        val wantMac = hmac(keys.hmacKey, nonce, ciphertext)
        if (!MessageDigest.isEqual(givenMac, wantMac)) throw MessageException("bad NIP-44 MAC")
        val padded = ChaCha20.xor(keys.chachaKey, keys.chachaNonce, ciphertext)
        val unpaddedLen = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        if (unpaddedLen < MIN_PLAINTEXT || unpaddedLen > MAX_PLAINTEXT ||
            padded.size != 2 + calcPadding(unpaddedLen)
        ) {
            throw MessageException("bad NIP-44 padding")
        }
        return padded.copyOfRange(2, 2 + unpaddedLen)
    }
}
