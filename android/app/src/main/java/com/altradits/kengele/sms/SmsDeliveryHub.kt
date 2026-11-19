package com.altradits.kengele.sms

/**
 * Routes per-part send results from the Android sent-receiver back to the
 * [SmsSender] that issued the request, aggregating a multi-part SMS into
 * one result: OK only when every part is OK, otherwise the first failure
 * in arrival order. Unknown ids (e.g. after a process restart) are
 * ignored. Pure Kotlin so unit tests cover the aggregation.
 */
class SmsDeliveryHub {
    private class Pending(
        val parts: Int,
        val onDone: (SendResult) -> Unit,
        var arrived: Int = 0,
        var firstFailure: SendResult? = null,
    )

    private val pending = LinkedHashMap<Long, Pending>()

    fun register(id: Long, parts: Int, onDone: (SendResult) -> Unit) {
        require(parts > 0) { "a send has at least one part" }
        pending[id] = Pending(parts, onDone)
        while (pending.size > MAX_PENDING) {
            val oldest = pending.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    /** Records one part's result, completing the send when all parts arrived. */
    fun deliver(id: Long, result: SendResult) {
        val p = pending[id] ?: return
        p.arrived++
        if (result != SendResult.OK && p.firstFailure == null) p.firstFailure = result
        if (p.arrived >= p.parts) {
            pending.remove(id)
            p.onDone(p.firstFailure ?: SendResult.OK)
        }
    }

    companion object {
        /** Bounds late or orphaned results; the oldest are dropped first. */
        const val MAX_PENDING = 128
    }
}

/** Process-wide hub used by the Android sent-receiver; tests use their own. */
object SmsDeliveryHubs {
    var shared = SmsDeliveryHub()
}
