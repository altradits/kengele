package com.altradits.kengele.message.nostr

import com.altradits.kengele.message.MessageException
import com.altradits.kengele.message.Nip44
import java.security.SecureRandom
import java.util.Base64

/**
 * The NIP-44 string layer: standard-base64 payloads exactly as go-nostr's
 * `nip44.Encrypt`/`Decrypt` produce them. The raw payload format underneath
 * is [Nip44]'s; this only adds the base64 step the gift wrap needs.
 */
object Nip44Strings {
    private val random = SecureRandom()

    /** NIP-44 conversation key between our [priv] and the peer's x-only key. */
    fun conversationKey(priv: ByteArray, peerX: ByteArray): ByteArray =
        Nip44.conversationKey(priv, peerX)

    /**
     * Encrypts [plaintext] (already UTF-8 bytes) and returns the standard
     * base64 payload. [nonce] fixes the 32-byte nonce for tests; pass null
     * for a fresh random one.
     */
    fun encryptToString(plaintext: ByteArray, conversationKey: ByteArray, nonce: ByteArray? = null): String {
        val useNonce = nonce ?: ByteArray(32).also(random::nextBytes)
        try {
            return Base64.getEncoder().encodeToString(Nip44.encrypt(plaintext, conversationKey, useNonce))
        } catch (e: MessageException) {
            throw NostrException(e.message ?: "NIP-44 encrypt failed")
        }
    }

    /** Decrypts a standard-base64 NIP-44 payload; throws [NostrException]. */
    fun decryptString(payloadB64: String, conversationKey: ByteArray): ByteArray {
        val payload = try {
            Base64.getDecoder().decode(payloadB64.trim())
        } catch (e: IllegalArgumentException) {
            throw NostrException("bad NIP-44 base64")
        }
        try {
            return Nip44.decrypt(payload, conversationKey)
        } catch (e: MessageException) {
            throw NostrException(e.message ?: "NIP-44 decrypt failed")
        }
    }
}
