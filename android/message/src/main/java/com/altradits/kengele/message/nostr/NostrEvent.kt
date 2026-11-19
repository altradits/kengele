package com.altradits.kengele.message.nostr

import com.altradits.kengele.message.Secp256k1
import java.security.MessageDigest

/**
 * A Nostr event (NIP-01), byte-compatible with go-nostr v0.52.3 as used by
 * `internal/transport/nostr`: the canonical serialization, the id, the JSON
 * shape and the BIP-340 signature all match, pinned by the shared vectors in
 * `nostr-vectors.json`.
 */
data class NostrEvent(
    val id: String = "",
    val pubkey: String = "",
    val createdAt: Long = 0,
    val kind: Int = 0,
    val tags: List<List<String>> = emptyList(),
    val content: String = "",
    val sig: String = "",
) {
    /**
     * The canonical serialization whose SHA-256 is the event id: exactly
     * `[0,"<pubkey>",<created_at>,<kind>,<tags>,"<content>"]` with go-nostr's
     * string escaping (quotes, backslashes and control characters escaped,
     * everything else — including UTF-8 — raw).
     */
    fun canonicalBytes(): ByteArray {
        val out = ByteArrayBuilder()
        out.append("[0,\"")
        out.appendAscii(pubkey)
        out.append("\",")
        out.appendAscii(createdAt.toString())
        out.appendAscii(",")
        out.appendAscii(kind.toString())
        out.appendAscii(",[")
        tags.forEachIndexed { ti, tag ->
            if (ti > 0) out.appendAscii(",")
            out.appendAscii("[")
            tag.forEachIndexed { si, s ->
                if (si > 0) out.appendAscii(",")
                out.appendEscaped(s)
            }
            out.appendAscii("]")
        }
        out.appendAscii("],")
        out.appendEscaped(content)
        out.appendAscii("]")
        return out.toBytes()
    }

    /** The event id: lowercase hex SHA-256 of [canonicalBytes]. */
    fun computeId(): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(canonicalBytes())
        return hash.toHex()
    }

    /** Whether [id] is the 64-hex id of these fields. */
    fun checkId(): Boolean = id.length == 64 && id == computeId()

    /**
     * Signs a copy with [priv]: fills in the x-only public key, the id and
     * the BIP-340 signature over the id hash.
     */
    fun sign(priv: ByteArray): NostrEvent {
        val pub = Secp256k1.publicKey(priv)
        val withKey = copy(pubkey = pub.toHex())
        val idBytes = withKey.computeId().fromHex()
        val sigBytes = Secp256k1.schnorrSign(priv, idBytes)
        return withKey.copy(id = idBytes.toHex(), sig = sigBytes.toHex())
    }

    /**
     * Whether the signature is a valid BIP-340 signature by [pubkey] over the
     * id hash of these fields. Like go-nostr it checks the signature against
     * the recomputed serialization, not against the [id] field.
     */
    fun checkSignature(): Boolean {
        val pub: ByteArray
        val sig: ByteArray
        val hash: ByteArray
        try {
            pub = pubkey.fromHex()
            sig = this.sig.fromHex()
            hash = MessageDigest.getInstance("SHA-256").digest(canonicalBytes())
        } catch (e: Exception) {
            return false
        }
        if (pub.size != 32 || sig.size != 64) return false
        return try {
            Secp256k1.schnorrVerify(pub, hash, sig)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The go-nostr JSON shape: `{"kind":…,"id":…,"pubkey":…,"created_at":…,
     * "tags":…,"content":…,"sig":…}`, with empty id/pubkey/sig left out.
     */
    fun toJson(): String {
        val out = StringBuilder()
        out.append("{\"kind\":").append(kind)
        if (id.isNotEmpty()) out.append(",\"id\":").append(NostrJson.quote(id))
        if (pubkey.isNotEmpty()) out.append(",\"pubkey\":").append(NostrJson.quote(pubkey))
        out.append(",\"created_at\":").append(createdAt)
        out.append(",\"tags\":[")
        tags.forEachIndexed { ti, tag ->
            if (ti > 0) out.append(',')
            out.append('[')
            tag.forEachIndexed { si, s ->
                if (si > 0) out.append(',')
                out.append(NostrJson.quote(s))
            }
            out.append(']')
        }
        out.append("],\"content\":").append(NostrJson.quote(content))
        if (sig.isNotEmpty()) out.append(",\"sig\":").append(NostrJson.quote(sig))
        out.append('}')
        return out.toString()
    }

    companion object {
        /** Parses event JSON; throws [NostrException] when malformed. */
        fun parseJson(text: String): NostrEvent {
            val obj = NostrJson.parse(text) as? NostrJson.Obj ?: throw NostrException("event is not an object")
            val tags = when (val t = obj.opt("tags")) {
                null, NostrJson.Null -> emptyList()
                is NostrJson.Arr -> t.items.map { item ->
                    when (item) {
                        is NostrJson.Arr -> item.items.map { s ->
                            (s as? NostrJson.Str)?.v ?: throw NostrException("bad event tag")
                        }
                        else -> throw NostrException("bad event tags")
                    }
                }
                else -> throw NostrException("bad event tags")
            }
            return NostrEvent(
                id = obj.str("id"),
                pubkey = obj.str("pubkey"),
                createdAt = (obj.opt("created_at") as? NostrJson.Num)?.long()
                    ?: throw NostrException("bad event created_at"),
                kind = (obj.opt("kind") as? NostrJson.Num)?.int()
                    ?: throw NostrException("bad event kind"),
                tags = tags,
                content = obj.str("content"),
                sig = obj.str("sig"),
            )
        }
    }
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val chars = CharArray(size * 2)
    for (i in indices) {
        chars[i * 2] = digits[(this[i].toInt() ushr 4) and 0xF]
        chars[i * 2 + 1] = digits[this[i].toInt() and 0xF]
    }
    return String(chars)
}

internal fun String.fromHex(): ByteArray {
    if (length % 2 != 0) throw NostrException("not hex")
    return ByteArray(length / 2) { i ->
        substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: throw NostrException("not hex")
    }
}

/** Byte-exact port of go-nostr's serialization escaping. */
private class ByteArrayBuilder {
    private var buf = ByteArray(256)
    private var n = 0

    private fun need(extra: Int) {
        if (n + extra > buf.size) {
            var size = buf.size * 2
            while (n + extra > size) size *= 2
            buf = buf.copyOf(size)
        }
    }

    fun appendAscii(s: String) {
        need(s.length)
        for (c in s) buf[n++] = c.code.toByte()
    }

    fun append(s: String) = appendAscii(s)

    fun appendEscaped(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        need(bytes.size * 6 + 2)
        buf[n++] = '"'.code.toByte()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when {
                c == '"'.code -> {
                    buf[n++] = '\\'.code.toByte()
                    buf[n++] = '"'.code.toByte()
                }
                c == '\\'.code -> {
                    buf[n++] = '\\'.code.toByte()
                    buf[n++] = '\\'.code.toByte()
                }
                c >= 0x20 -> buf[n++] = b
                c == 0x08 -> {
                    buf[n++] = '\\'.code.toByte()
                    buf[n++] = 'b'.code.toByte()
                }
                c < 0x09 -> {
                    buf[n++] = '\\'.code.toByte()
                    buf[n++] = 'u'.code.toByte()
                    buf[n++] = '0'.code.toByte()
                    buf[n++] = '0'.code.toByte()
                    buf[n++] = '0'.code.toByte()
                    buf[n++] = ('0'.code + c).toByte()
                }
                c == 0x09 -> {
                    buf[n++] = '\\'.code.toByte()
                    buf[n++] = 't'.code.toByte()
                }
                c == 0x0a -> {
                    buf[n++] = '\\'.code.toByte()
                    buf[n++] = 'n'.code.toByte()
                }
                c == 0x0c -> {
                    buf[n++] = '\\'.code.toByte()
                    buf[n++] = 'f'.code.toByte()
                }
                c == 0x0d -> {
                    buf[n++] = '\\'.code.toByte()
                    buf[n++] = 'r'.code.toByte()
                }
                c < 0x10 -> {
                    buf[n++] = '\\'.code.toByte()
                    buf[n++] = 'u'.code.toByte()
                    buf[n++] = '0'.code.toByte()
                    buf[n++] = '0'.code.toByte()
                    buf[n++] = '0'.code.toByte()
                    buf[n++] = (0x57 + c).toByte()
                }
                c < 0x1a -> {
                    buf[n++] = '\\'.code.toByte()
                    buf[n++] = 'u'.code.toByte()
                    buf[n++] = '0'.code.toByte()
                    buf[n++] = '0'.code.toByte()
                    buf[n++] = '1'.code.toByte()
                    buf[n++] = (0x20 + c).toByte()
                }
                else -> {
                    buf[n++] = '\\'.code.toByte()
                    buf[n++] = 'u'.code.toByte()
                    buf[n++] = '0'.code.toByte()
                    buf[n++] = '0'.code.toByte()
                    buf[n++] = '1'.code.toByte()
                    buf[n++] = (0x47 + c).toByte()
                }
            }
        }
        buf[n++] = '"'.code.toByte()
    }

    fun toBytes(): ByteArray = buf.copyOf(n)
}
