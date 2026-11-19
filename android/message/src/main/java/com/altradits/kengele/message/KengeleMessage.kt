package com.altradits.kengele.message

import java.security.SecureRandom
import java.util.Base64

/** Every Kengele failure to seal, open or decode a message. */
class MessageException(message: String) : Exception(message)

/**
 * Kotlin port of `internal/message` (docs/message-format.md, format v1).
 *
 * Every event — check-in, duress check-in, panic, missed, guardian
 * confirmation, all clear, going dark — is signed with the sender's BIP-340
 * key, encrypted separately to each recipient with NIP-44 v2, and always
 * exactly [WIRE_SIZE] bytes on the wire / [TEXT_SIZE] characters as SMS
 * text, so a carrier cannot tell a duress check-in or panic from a normal
 * check-in (threat model docs/threat-model.md, sections 4.1-4.2).
 *
 * Byte compatibility with the Go package is pinned by the shared vectors in
 * internal/message/testdata/vectors.json, which both sides test against.
 */
object KengeleMessage {
    const val VERSION: Byte = 0x01
    const val MAX_BODY_SIZE = 86
    const val INNER_SIZE = 160
    const val SIGNED_SIZE = 96
    const val NIP44_SIZE = 227
    const val WIRE_SIZE = 228
    const val TEXT_SIZE = 304
    const val MAX_SMS_CHARS = 306
    const val MAX_CLOCK_SKEW_SECS = 10 * 60L
    const val SIGN_TAG = "kengele/event/v1"

    /** Event kinds. Values are part of the wire format and must never change. */
    enum class Kind(val value: Int) {
        CHECK_IN(1),
        DURESS_CHECK_IN(2),
        PANIC(3),
        MISSED(4),
        GUARDIAN_CONFIRM(5),
        ALL_CLEAR(6),
        GOING_DARK(7);

        companion object {
            fun fromValue(v: Int): Kind =
                values().firstOrNull { it.value == v }
                    ?: throw MessageException("unknown kind $v")
        }
    }

    /**
     * One Kengele event. [createdAtSecs] is whole Unix seconds, [seq]
     * increases with every event a sender creates, [body] is at most
     * [MAX_BODY_SIZE] bytes. [signer] is filled in by [open] and ignored by
     * [seal].
     */
    data class Event(
        val kind: Kind,
        val createdAtSecs: Long,
        val seq: Long,
        val body: ByteArray = ByteArray(0),
        val signer: ByteArray? = null,
    )

    private val random = SecureRandom()

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFF).toByte()
        b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    private fun getU32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or
            ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or
            (b[o + 3].toLong() and 0xFF)

    /** x-only (Nostr-style) public key of a private key. */
    fun publicKey(priv: ByteArray): ByteArray = Secp256k1.publicKey(priv)

    /** BIP-340 tagged hash of the signed part of the plaintext. */
    fun signedHash(signedPart: ByteArray): ByteArray {
        require(signedPart.size == SIGNED_SIZE) { "signed part must be $SIGNED_SIZE bytes" }
        return Secp256k1.taggedHash(SIGN_TAG, signedPart)
    }

    /**
     * Builds the fixed-size signed plaintext. [aux] fixes the signature
     * nonce for tests; pass null for a fresh random nonce.
     */
    fun sign(event: Event, senderPriv: ByteArray, aux: ByteArray? = null): ByteArray {
        if (event.body.size > MAX_BODY_SIZE) throw MessageException("body too large")
        if (event.createdAtSecs <= 0 || event.createdAtSecs > 0xFFFFFFFFL) {
            throw MessageException("invalid timestamp")
        }
        if (event.seq < 0 || event.seq > 0xFFFFFFFFL) throw MessageException("invalid seq")
        val inner = ByteArray(INNER_SIZE)
        inner[0] = event.kind.value.toByte()
        putU32(inner, 1, event.createdAtSecs)
        putU32(inner, 5, event.seq)
        inner[9] = event.body.size.toByte()
        event.body.copyInto(inner, 10)
        val sig = Secp256k1.schnorrSign(
            senderPriv,
            signedHash(inner.copyOfRange(0, SIGNED_SIZE)),
            aux,
        )
        sig.copyInto(inner, SIGNED_SIZE)
        return inner
    }

    /**
     * Seals [event]: signs with [senderPriv], encrypts to [recipientX].
     * [nonce] fixes the NIP-44 nonce for tests; pass null for random.
     */
    fun seal(
        event: Event,
        senderPriv: ByteArray,
        recipientX: ByteArray,
        nonce: ByteArray? = null,
    ): ByteArray = sealInner(sign(event, senderPriv), senderPriv, recipientX, nonce)

    /**
     * Seals an already-signed [inner] plaintext. Used with a pinned inner in
     * tests so the golden vector stays exact however the signer randomises.
     */
    fun sealInner(
        inner: ByteArray,
        senderPriv: ByteArray,
        recipientX: ByteArray,
        nonce: ByteArray? = null,
    ): ByteArray {
        if (inner.size != INNER_SIZE) throw MessageException("inner must be $INNER_SIZE bytes")
        val useNonce = nonce ?: ByteArray(32).also(random::nextBytes)
        val ck = Nip44.conversationKey(senderPriv, recipientX)
        val payload = Nip44.encrypt(inner, ck, useNonce)
        if (payload.size != NIP44_SIZE) throw MessageException("wrong NIP-44 size")
        val wire = ByteArray(WIRE_SIZE)
        wire[0] = VERSION
        payload.copyInto(wire, 1)
        return wire
    }

    /**
     * Opens [wire] with [recipientPriv], trying each known [senders] key in
     * turn (the wire carries no sender key). Verifies the signature, checks
     * every field strictly and rejects events dated more than 10 minutes
     * after [nowSecs].
     */
    fun open(
        wire: ByteArray,
        recipientPriv: ByteArray,
        senders: List<ByteArray>,
        nowSecs: Long,
    ): Event {
        if (wire.size != WIRE_SIZE) throw MessageException("wrong wire size")
        if (wire[0] != VERSION) throw MessageException("unknown version")
        val payload = wire.copyOfRange(1, wire.size)
        for (sender in senders) {
            val inner: ByteArray
            try {
                val ck = Nip44.conversationKey(recipientPriv, sender)
                inner = Nip44.decrypt(payload, ck)
            } catch (e: MessageException) {
                continue // Wrong sender or altered message: try the next key.
            }
            return parse(inner, sender, nowSecs)
        }
        throw MessageException("not addressed to this key by any known sender")
    }

    /** Parses and strictly checks a decrypted inner plaintext. */
    fun parse(inner: ByteArray, signer: ByteArray, nowSecs: Long): Event {
        if (inner.size != INNER_SIZE) throw MessageException("wrong inner size")
        if (signer.size != 32) throw MessageException("signer must be 32 bytes")
        val sig = inner.copyOfRange(SIGNED_SIZE, INNER_SIZE)
        if (!Secp256k1.schnorrVerify(signer, signedHash(inner.copyOfRange(0, SIGNED_SIZE)), sig)) {
            throw MessageException("bad signature")
        }
        val kind = Kind.fromValue(inner[0].toInt() and 0xFF)
        val created = getU32(inner, 1)
        if (created == 0L || created > nowSecs + MAX_CLOCK_SKEW_SECS) {
            throw MessageException("invalid or future timestamp")
        }
        val bodyLen = inner[9].toInt() and 0xFF
        if (bodyLen > MAX_BODY_SIZE) throw MessageException("invalid body length")
        for (i in 10 + bodyLen until 10 + MAX_BODY_SIZE) {
            if (inner[i] != 0.toByte()) throw MessageException("non-zero padding")
        }
        return Event(
            kind = kind,
            createdAtSecs = created,
            seq = getU32(inner, 5),
            body = inner.copyOfRange(10, 10 + bodyLen),
            signer = signer.copyOf(),
        )
    }

    /** SMS-safe text form: unpadded base64url of the wire message. */
    fun encodeText(wire: ByteArray): String {
        require(wire.size == WIRE_SIZE) { "wire must be $WIRE_SIZE bytes" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(wire)
    }

    /** Strictly parses the SMS text form. */
    fun decodeText(s: String): ByteArray {
        if (s.length != TEXT_SIZE) throw MessageException("wrong text size")
        for (c in s) {
            val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
            if (!ok) throw MessageException("invalid text encoding")
        }
        val wire = try {
            Base64.getUrlDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            throw MessageException("invalid text encoding")
        }
        if (wire.size != WIRE_SIZE) throw MessageException("wrong wire size")
        return wire
    }
}
