package com.altradits.kengele.message.nostr

/**
 * One gift wrap waiting for enough relays to accept it. The wrap is already
 * encrypted and signed, so a stored item reveals no more than what a relay
 * would see. Retries publish the same event, so a relay that already has it
 * just answers "duplicate".
 */
data class OutboxItem(
    val event: NostrEvent,
    val relays: List<String>,
    val accepted: MutableSet<String> = mutableSetOf(),
    val queuedSecs: Long = 0,
    var attempts: Int = 0,
) {
    fun copy(): OutboxItem = OutboxItem(event, relays.toList(), accepted.toMutableSet(), queuedSecs, attempts)
}

/**
 * In-memory outbox: queued wraps are lost if the process stops, like the Go
 * transport's MemoryOutbox. Safe for concurrent use; [put] replaces an item
 * with the same event id.
 */
class MemoryOutbox {
    private val items = LinkedHashMap<String, OutboxItem>()

    @Synchronized
    fun put(item: OutboxItem) {
        items[item.event.id] = item.copy()
    }

    /** The queued items, oldest first. */
    @Synchronized
    fun list(): List<OutboxItem> = items.values.map { it.copy() }.sortedBy { it.queuedSecs }

    @Synchronized
    fun remove(id: String) {
        items.remove(id)
    }

    @Synchronized
    fun size(): Int = items.size
}

/** Remembers the last [n] keys; [seen] reports whether [k] was already recorded, and records it. */
class SeenSet(private val n: Int) {
    private val set = HashSet<String>()
    private val ring = ArrayList<String>()
    private var next = 0

    @Synchronized
    fun seen(k: String): Boolean {
        if (set.contains(k)) return true
        if (ring.size < n) {
            ring.add(k)
        } else if (n > 0) {
            set.remove(ring[next])
            ring[next] = k
            next = (next + 1) % n
        }
        set.add(k)
        return false
    }

    @Synchronized
    fun size(): Int = set.size
}

/** Exponential backoff between retries, doubling from min to max. */
object NostrBackoff {
    /** The delay after [currentMs]: doubled but never above [maxMs]. */
    fun nextMs(currentMs: Long, maxMs: Long): Long {
        if (currentMs >= maxMs) return maxMs
        val doubled = currentMs * 2
        return if (doubled < 0 || doubled > maxMs) maxMs else doubled
    }
}
