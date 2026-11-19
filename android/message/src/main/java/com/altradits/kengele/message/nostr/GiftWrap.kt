package com.altradits.kengele.message.nostr

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.MessageException
import com.altradits.kengele.message.Secp256k1
import java.security.SecureRandom

/**
 * NIP-59 gift wrapping for Kengele wire messages, mirroring
 * `internal/transport/nostr`'s Wrap/Unwrap: the wire text goes into a kind-14
 * rumor, sealed with the sender's key (kind 13), wrapped with a fresh
 * one-time key (kind 1059) addressed to the recipient. A relay sees only the
 * outer event with a backdated timestamp.
 *
 * Interop with the Go side is pinned by `nostr-vectors.json`: the Kotlin side
 * unwraps Go-made wraps, and Go unwraps Kotlin-made ones (checked during
 * development with fixed keys, jitter and nonces).
 */
object GiftWrap {
    const val KIND_SEAL = 13
    const val KIND_RUMOR = 14
    const val KIND_GIFT_WRAP = 1059
    const val KIND_DM_RELAY_LIST = 10050

    /** How far into the past a seal's and a wrap's timestamp can move (NIP-59 suggests up to two days). */
    const val MAX_JITTER_SECS = 48L * 3600

    /** An opened gift wrap: the Kengele wire message and the seal signer's x-only key. */
    data class Unwrapped(val wire: ByteArray, val sealer: ByteArray)

    private val random = SecureRandom()

    /**
     * Wraps [wire] for [recipientX]. [nowSecs] is the real time; it only
     * appears inside the encrypted rumor. [jitter] draws the backdate in
     * `[0, MAX_JITTER_SECS)`; [oneTimePriv], [sealNonce] and [wrapNonce] fix
     * the randomness for tests and are fresh random when null.
     */
    fun wrap(
        wire: ByteArray,
        senderPriv: ByteArray,
        recipientX: ByteArray,
        nowSecs: Long,
        jitter: (Long) -> Long = ::secureJitter,
        oneTimePriv: ByteArray? = null,
        sealNonce: ByteArray? = null,
        wrapNonce: ByteArray? = null,
    ): NostrEvent {
        if (wire.size != KengeleMessage.WIRE_SIZE || wire[0] != KengeleMessage.VERSION) {
            throw NostrException("wrap needs a well-formed Kengele message")
        }
        if (recipientX.size != 32) throw NostrException("recipient key must be 32 bytes")
        val senderX = try {
            Secp256k1.publicKey(senderPriv)
        } catch (e: MessageException) {
            throw NostrException("bad sender key")
        }
        val recipientHex = recipientX.toHex()

        val rumor = NostrEvent(
            kind = KIND_RUMOR,
            pubkey = senderX.toHex(),
            createdAt = nowSecs,
            tags = listOf(listOf("p", recipientHex)),
            content = KengeleMessage.encodeText(wire),
        )
        val rumorJson = rumor.copy(id = rumor.computeId()).toJson()

        val sealCk = Nip44Strings.conversationKey(senderPriv, recipientX)
        val sealContent = Nip44Strings.encryptToString(rumorJson.toByteArray(Charsets.UTF_8), sealCk, sealNonce)
        val sealAt = nowSecs - jitter(MAX_JITTER_SECS).coerceIn(0, MAX_JITTER_SECS - 1)
        val seal = NostrEvent(
            kind = KIND_SEAL,
            createdAt = sealAt,
            tags = emptyList(),
            content = sealContent,
        ).sign(senderPriv)

        val oneTime = oneTimePriv ?: newPrivKey()
        val wrapCk = Nip44Strings.conversationKey(oneTime, recipientX)
        val wrapContent = Nip44Strings.encryptToString(seal.toJson().toByteArray(Charsets.UTF_8), wrapCk, wrapNonce)
        val wrapAt = nowSecs - jitter(MAX_JITTER_SECS).coerceIn(0, MAX_JITTER_SECS - 1)
        return NostrEvent(
            kind = KIND_GIFT_WRAP,
            createdAt = wrapAt,
            tags = listOf(listOf("p", recipientHex)),
            content = wrapContent,
        ).sign(oneTime)
    }

    /**
     * Opens a gift wrap addressed to [recipientPriv] and returns the wire
     * message and the seal signer's key. It does not check the Kengele
     * signature inside: call [KengeleMessage.open] for that. Anything wrong
     * throws [NostrException] ([notForUs] when the wrap is for another key).
     */
    fun unwrap(gw: NostrEvent, recipientPriv: ByteArray): Unwrapped {
        val ownHex = try {
            Secp256k1.publicKey(recipientPriv).toHex()
        } catch (e: MessageException) {
            throw NostrException("bad recipient key")
        }
        if (gw.kind != KIND_GIFT_WRAP) throw NostrException("not a gift wrap")
        if (!addressedTo(gw.tags, ownHex)) throw NostrException("gift wrap is not addressed to this key", notForUs = true)
        if (!gw.checkSignature()) throw NostrException("bad gift wrap signature")

        val sealJson = decrypt(gw.content, gw.pubkey, recipientPriv)
        val seal = try {
            NostrEvent.parseJson(sealJson)
        } catch (e: NostrException) {
            throw NostrException("bad seal: ${e.message}")
        }
        if (seal.kind != KIND_SEAL) throw NostrException("not a seal")
        if (!seal.checkSignature()) throw NostrException("bad seal signature")

        val rumorJson = decrypt(seal.content, seal.pubkey, recipientPriv)
        val rumor = try {
            NostrEvent.parseJson(rumorJson)
        } catch (e: NostrException) {
            throw NostrException("bad rumor: ${e.message}")
        }
        // NIP-17: the rumor's author must be the seal's signer, or anyone
        // could seal a rumor that claims to come from someone else.
        if (rumor.kind != KIND_RUMOR || rumor.pubkey != seal.pubkey || rumor.sig != "") {
            throw NostrException("rumor author differs from sealer")
        }
        if (!addressedTo(rumor.tags, ownHex)) throw NostrException("gift wrap is not addressed to this key", notForUs = true)
        val wire = try {
            KengeleMessage.decodeText(rumor.content)
        } catch (e: Exception) {
            throw NostrException("rumor is not a Kengele message")
        }
        val sealer = try {
            seal.pubkey.fromHex()
        } catch (e: NostrException) {
            throw NostrException("bad sealer key")
        }
        if (sealer.size != 32) throw NostrException("bad sealer key")
        return Unwrapped(wire, sealer)
    }

    private fun decrypt(content: String, peerHex: String, recipientPriv: ByteArray): String {
        val peer = try {
            peerHex.fromHex()
        } catch (e: NostrException) {
            throw NostrException("bad peer key")
        }
        if (peer.size != 32) throw NostrException("bad peer key")
        val ck = try {
            Nip44Strings.conversationKey(recipientPriv, peer)
        } catch (e: MessageException) {
            throw NostrException("bad conversation key")
        }
        return try {
            Nip44Strings.decryptString(content, ck).toString(Charsets.UTF_8)
        } catch (e: NostrException) {
            throw NostrException("cannot decrypt: ${e.message}")
        }
    }

    private fun addressedTo(tags: List<List<String>>, pubHex: String): Boolean =
        tags.any { it.size >= 2 && it[0] == "p" && it[1] == pubHex }

    private fun secureJitter(bound: Long): Long {
        // Uniform in [0, bound), like Go's rand.Int.
        if (bound <= 1) return 0
        val bits = 63 - java.lang.Long.numberOfLeadingZeros(bound - 1)
        while (true) {
            val cand = ByteArray(8).also(random::nextBytes)
            var v = 0L
            for (b in cand) v = (v shl 8) or (b.toLong() and 0xFF)
            v = v ushr (64 - bits - 1) // keep bits+1 bits
            if (v < bound) return v
        }
    }

    private fun newPrivKey(): ByteArray {
        while (true) {
            val cand = ByteArray(32).also(random::nextBytes)
            try {
                Secp256k1.publicKey(cand)
            } catch (e: Exception) {
                continue
            }
            return cand
        }
    }
}
