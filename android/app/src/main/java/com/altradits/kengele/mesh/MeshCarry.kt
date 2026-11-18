package com.altradits.kengele.mesh

import java.security.MessageDigest
import java.security.SecureRandom

// NOTE (blindness): this file must never import com.altradits.kengele.message
// or name any decryptor. The relay path below forwards bytes it cannot read,
// and MeshCarryTest fails the build if that import ever appears.

/**
 * Every failure to split, parse or reassemble a mesh fragment. Kinds mirror
 * the Go core (`internal/meshcarry`): NOT_KENGELE is other traffic and must
 * be ignored silently, never reported.
 */
class MeshCarryException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { NOT_KENGELE, FRAGMENT, DATAGRAM_SIZE }
}

/**
 * Kotlin port of the `internal/meshcarry` wire core (docs/offgrid-comms.md
 * section 4): a datagram is one sealed opaque 228-byte message carried
 * byte-identical and never parsed on the relay path. Fragment layout:
 *
 * 0..1  magic "KC": anything else on the air is ignored, not an error
 * 2     format version (1)
 * 3     TTL, hops left (sender sets [DEFAULT_TTL]; each relay sends TTL-1)
 * 4     total fragments (a 228-byte datagram always makes 2)
 * 5     this fragment's index (0..total-1)
 * 6..8  message id, 24 bits, sender-fresh random per datagram
 *
 * Fragment 0 carries 141 bytes (a full 150-byte MTU) and fragment 1 carries
 * 87 bytes. All state here is memory-only: nothing touches disk.
 */
object MeshCarry {
    /** Largest on-air fragment in bytes. */
    const val FRAGMENT_MTU = 150

    /** Fragment header size in bytes. */
    const val HEADER_LEN = 9

    /** Largest datagram slice one fragment carries. */
    const val MAX_CHUNK = 141

    /** The only datagram size this layer carries: one sealed 228-byte message. */
    const val DATAGRAM_SIZE = 228

    /** Cap on partial datagrams held at once; the oldest is dropped past this. */
    const val MAX_PENDING_GROUPS = 64

    /** The only fragment version this code speaks. */
    const val VERSION = 1

    /** Hop budget a sender puts on a fresh datagram. */
    const val DEFAULT_TTL = 7

    /** Broadcast TTL is capped once a node holds this many links or more. */
    const val DENSE_CROWD_LINKS = 6

    /** Broadcast TTL cap in a dense crowd (bitchat dense-crowd rule). */
    const val DENSE_CROWD_TTL = 5

    /** How long fragments of one datagram are kept while the rest are on the air. */
    const val REASSEMBLE_TIMEOUT_MS = 10L * 60 * 1000

    /** Cap on completed-datagram hashes remembered for flood dedup. */
    const val SEEN_MAX = 1000

    /** Cap on forwarded (message id, index) pairs remembered. */
    const val RELAYED_MAX = 1000

    /** How long a completed datagram stays deduplicated. */
    const val SEEN_TTL_MS = 5L * 60 * 1000

    /** Largest 24-bit message id. */
    const val MAX_MSG_ID = 0xFFFFFF

    private const val MAGIC0: Byte = 0x4B // 'K'
    private const val MAGIC1: Byte = 0x43 // 'C'

    private val random = SecureRandom()

    /** One parsed fragment header: hop control only. No sender, no kind, no body. */
    data class Header(val ttl: Int, val total: Int, val index: Int, val msgId: Int)

    /** A parsed header plus its datagram slice. */
    data class Parsed(val header: Header, val chunk: ByteArray)

    /**
     * Cuts [datagram], which must be exactly one 228-byte sealed message,
     * into fragments labelled with [msgId] (24 bits) and [ttl]. Anything
     * else is not carried: rejected instead of guessed.
     */
    fun split(datagram: ByteArray, msgId: Int, ttl: Int): List<ByteArray> {
        if (datagram.size != DATAGRAM_SIZE) {
            throw MeshCarryException(
                "datagram is ${datagram.size} bytes, want $DATAGRAM_SIZE",
                MeshCarryException.Kind.DATAGRAM_SIZE,
            )
        }
        if (msgId < 0 || msgId > MAX_MSG_ID) {
            throw MeshCarryException(
                "message id out of 24-bit range",
                MeshCarryException.Kind.FRAGMENT,
            )
        }
        if (ttl < 0 || ttl > 255) {
            throw MeshCarryException(
                "ttl $ttl out of range",
                MeshCarryException.Kind.FRAGMENT,
            )
        }
        val total = (datagram.size + MAX_CHUNK - 1) / MAX_CHUNK
        return List(total) { i ->
            val start = i * MAX_CHUNK
            val end = minOf(start + MAX_CHUNK, datagram.size)
            ByteArray(HEADER_LEN + (end - start)).also { p ->
                p[0] = MAGIC0
                p[1] = MAGIC1
                p[2] = VERSION.toByte()
                p[3] = ttl.toByte()
                p[4] = total.toByte()
                p[5] = i.toByte()
                p[6] = (msgId shr 16).toByte()
                p[7] = (msgId shr 8).toByte()
                p[8] = msgId.toByte()
                datagram.copyInto(p, HEADER_LEN, start, end)
            }
        }
    }

    /**
     * Parses one on-air fragment. Anything without the "KC" magic is other
     * traffic and throws bare NOT_KENGELE; a Kengele fragment that fails any
     * other check throws FRAGMENT.
     */
    fun parseHeader(f: ByteArray): Parsed {
        fun frag(msg: String): Nothing =
            throw MeshCarryException(msg, MeshCarryException.Kind.FRAGMENT)
        if (f.size < 2 || f[0] != MAGIC0 || f[1] != MAGIC1) {
            throw MeshCarryException("not a Kengele fragment", MeshCarryException.Kind.NOT_KENGELE)
        }
        if (f.size < HEADER_LEN + 1 || f.size > HEADER_LEN + MAX_CHUNK) {
            frag("size ${f.size}")
        }
        if (f[2].toInt() != VERSION) frag("version ${f[2].toInt() and 0xFF}")
        val total = f[4].toInt() and 0xFF
        val index = f[5].toInt() and 0xFF
        if (total == 0 || index >= total) frag("part $index of $total")
        val chunk = f.copyOfRange(HEADER_LEN, f.size)
        if (index < total - 1 && chunk.size != MAX_CHUNK) frag("short non-last part")
        return Parsed(
            Header(
                ttl = f[3].toInt() and 0xFF,
                total = total,
                index = index,
                msgId = ((f[6].toInt() and 0xFF) shl 16) or
                    ((f[7].toInt() and 0xFF) shl 8) or
                    (f[8].toInt() and 0xFF),
            ),
            chunk,
        )
    }

    /** Sender-fresh random 24-bit message id, one per datagram. */
    fun freshMsgId(): Int {
        val b = ByteArray(3).also(random::nextBytes)
        return ((b[0].toInt() and 0xFF) shl 16) or
            ((b[1].toInt() and 0xFF) shl 8) or
            (b[2].toInt() and 0xFF)
    }

    /**
     * TTL for a fresh broadcast from a node holding [linkCount] live links:
     * capped at [DENSE_CROWD_TTL] in a dense crowd, else [want].
     */
    fun broadcastTtl(linkCount: Int, want: Int = DEFAULT_TTL): Int =
        if (linkCount >= DENSE_CROWD_LINKS && want > DENSE_CROWD_TTL) DENSE_CROWD_TTL else want

    internal fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}

/**
 * Joins fragments back into datagrams. Groups expire after the timeout so
 * one lost fragment cannot pin memory forever; the sender must send the
 * datagram again under a new message id. Not safe for concurrent use:
 * callers (the radio) guard it with a lock.
 */
class Reassembler(
    private val timeoutMs: Long = MeshCarry.REASSEMBLE_TIMEOUT_MS,
    private val maxGroups: Int = MeshCarry.MAX_PENDING_GROUPS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    sealed interface AddResult {
        data object Ignored : AddResult
        data object Incomplete : AddResult
        data class Complete(val datagram: ByteArray) : AddResult
    }

    private data class Group(val total: Int, val parts: MutableMap<Int, ByteArray>, val first: Long)

    private val groups = mutableMapOf<Int, Group>()
    private val order = ArrayDeque<Int>() // message ids, oldest first, for eviction

    /** How many partial datagrams are held. */
    fun pending(): Int = groups.size

    /**
     * Feeds one on-air fragment in. Non-Kengele traffic is ignored silently.
     * A duplicate fragment is also ignored: the mesh repeats packets. When
     * the last missing fragment arrives the reassembled bytes come back;
     * they must be exactly 228 bytes.
     */
    fun add(f: ByteArray): AddResult {
        val parsed = try {
            MeshCarry.parseHeader(f)
        } catch (e: MeshCarryException) {
            if (e.kind == MeshCarryException.Kind.NOT_KENGELE) return AddResult.Ignored
            throw e
        }
        val h = parsed.header
        // A group whose smallest possible completion already exceeds 228
        // bytes can never become a datagram.
        if ((h.total - 1) * MeshCarry.MAX_CHUNK + 1 > MeshCarry.DATAGRAM_SIZE) {
            throw MeshCarryException(
                "${h.total} parts cannot make 228 bytes",
                MeshCarryException.Kind.DATAGRAM_SIZE,
            )
        }
        var g = groups[h.msgId]
        if (g == null) {
            if (groups.size >= maxGroups) drop(order.first())
            g = Group(h.total, mutableMapOf(), clock())
            groups[h.msgId] = g
            order.addLast(h.msgId)
        }
        if (g.total != h.total) {
            // Two senders picked the same id, or a corrupt total slipped
            // past the radio checksum. Drop the group; the sender retries
            // under a new id.
            drop(h.msgId)
            throw MeshCarryException(
                "conflicting part count",
                MeshCarryException.Kind.FRAGMENT,
            )
        }
        if (g.parts.containsKey(h.index)) return AddResult.Ignored
        g.parts[h.index] = parsed.chunk
        if (g.parts.size != g.total) return AddResult.Incomplete
        val out = ByteArray(g.parts.values.sumOf { it.size })
        var o = 0
        for (i in 0 until g.total) {
            val part = g.parts[i] ?: break
            part.copyInto(out, o)
            o += part.size
        }
        drop(h.msgId)
        if (out.size != MeshCarry.DATAGRAM_SIZE) {
            throw MeshCarryException(
                "completion is ${out.size} bytes, want ${MeshCarry.DATAGRAM_SIZE}",
                MeshCarryException.Kind.DATAGRAM_SIZE,
            )
        }
        return AddResult.Complete(out)
    }

    /** Drops every partial datagram older than the timeout as of [now]. */
    fun evictExpired(now: Long) {
        for ((id, g) in groups.toList()) {
            if (now - g.first > timeoutMs) drop(id)
        }
    }

    private fun drop(id: Int) {
        groups.remove(id)
        order.remove(id)
    }
}

/**
 * One phone in the crowd mesh: blind relay plus reassembly, all in memory.
 * The relay decision uses the header only, never the bytes: the node
 * forwards what it cannot read. Not safe for concurrent use.
 */
class BlindNode(
    val id: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * When true, discards every received fragment. The drill hook that
     * proves the honest limit: with the middle node dropping, nothing
     * arrives and silence fails safe.
     */
    var drop: Boolean = false

    /** One datagram this node reassembled, with links traversed from the source. */
    data class Delivery(val datagram: ByteArray, val hops: Int)

    /**
     * What leaves the node for one received fragment: [relay] is the
     * fragment to rebroadcast (a copy with TTL-1, or null when nothing
     * forwards), [delivery] is set when this fragment completes a datagram
     * the node had not seen before, and [reassemblyError] carries a
     * reassembly failure that still travelled (a blind carrier relays
     * first and lets endpoints argue about reassembly).
     */
    data class ReceiveResult(
        val relay: ByteArray?,
        val delivery: Delivery?,
        val reassemblyError: MeshCarryException? = null,
    )

    private val reasm = Reassembler(clock = clock)

    /** Completed-datagram hashes, deduplicated for [MeshCarry.SEEN_TTL_MS]. */
    private val seen = mutableMapOf<String, Long>()
    private val seenOrder = ArrayDeque<String>()

    /** Forwarded (message id, index) pairs, so floods terminate. */
    private val relayed = mutableSetOf<Long>()
    private val relayedOrder = ArrayDeque<Long>()

    fun pending(): Int = reasm.pending()

    fun receive(frag: ByteArray): ReceiveResult {
        if (drop) return ReceiveResult(null, null)
        val h = try {
            MeshCarry.parseHeader(frag).header
        } catch (e: MeshCarryException) {
            if (e.kind == MeshCarryException.Kind.NOT_KENGELE) return ReceiveResult(null, null)
            throw e
        }
        if (h.ttl == 0) return ReceiveResult(null, null)
        val key = fragKey(h.msgId, h.index)
        if (relayed.contains(key)) return ReceiveResult(null, null)
        val out = frag.copyOf()
        out[3] = (h.ttl - 1).toByte()
        markRelayed(key)

        val addResult = try {
            reasm.add(frag)
        } catch (e: MeshCarryException) {
            // The bytes still travel: a blind carrier relays first and
            // lets endpoints argue about reassembly.
            return ReceiveResult(out, null, e)
        }
        if (addResult !is Reassembler.AddResult.Complete) return ReceiveResult(out, null)
        val sum = MeshCarry.sha256(addResult.datagram).toHex()
        if (seen.containsKey(sum)) return ReceiveResult(out, null)
        markSeen(sum)
        val hops = if (h.ttl <= MeshCarry.DEFAULT_TTL) MeshCarry.DEFAULT_TTL - h.ttl + 1 else 1
        return ReceiveResult(out, Delivery(addResult.datagram, hops))
    }

    /** Records a datagram this node originates as seen, so it never delivers its own broadcast. */
    fun markSeenDatagram(datagram: ByteArray) {
        markSeen(MeshCarry.sha256(datagram).toHex())
    }

    /** Records one (message id, index) pair this node broadcast as forwarded. */
    fun markRelayedFrag(msgId: Int, index: Int) {
        markRelayed(fragKey(msgId, index))
    }

    fun evictExpired(now: Long) {
        reasm.evictExpired(now)
        purgeSeen(now)
    }

    private fun markSeen(sum: String) {
        purgeSeen(clock())
        if (seen.containsKey(sum)) return
        if (seenOrder.size >= MeshCarry.SEEN_MAX) {
            seen.remove(seenOrder.removeFirst())
        }
        seen[sum] = clock()
        seenOrder.addLast(sum)
    }

    private fun purgeSeen(now: Long) {
        while (seenOrder.isNotEmpty()) {
            val oldest = seenOrder.first()
            if (now - (seen[oldest] ?: 0) <= MeshCarry.SEEN_TTL_MS) break
            seenOrder.removeFirst()
            seen.remove(oldest)
        }
    }

    private fun markRelayed(key: Long) {
        if (relayed.contains(key)) return
        if (relayedOrder.size >= MeshCarry.RELAYED_MAX) {
            relayed.remove(relayedOrder.removeFirst())
        }
        relayed.add(key)
        relayedOrder.addLast(key)
    }

    private fun fragKey(msgId: Int, index: Int): Long =
        (msgId.toLong() shl 8) or (index.toLong() and 0xFF)
}

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) append(String.format("%02x", b))
}
