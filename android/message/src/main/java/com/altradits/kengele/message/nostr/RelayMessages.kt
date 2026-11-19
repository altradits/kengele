package com.altradits.kengele.message.nostr

/**
 * The NIP-01 relay envelopes this transport speaks: `EVENT` (publish),
 * `REQ`/`CLOSE` (subscribe), and the relay's `OK`/`EVENT`/`EOSE`/`CLOSED`/
 * `NOTICE` replies. Anything malformed parses as [Incoming.Unknown] and is
 * ignored, like go-nostr's `ParseMessage` returning nil.
 */
object RelayMessages {
    sealed interface Incoming {
        data class Ok(val eventId: String, val ok: Boolean, val reason: String) : Incoming
        data class RelayEvent(val subId: String, val event: NostrEvent) : Incoming
        /** A client publishing: `["EVENT",{…}]`. Only test relays observe it. */
        data class Publish(val event: NostrEvent) : Incoming
        data class Eose(val subId: String) : Incoming
        data class Closed(val subId: String, val reason: String) : Incoming
        data class Notice(val text: String) : Incoming
        data class Unknown(val raw: String) : Incoming
    }

    /** `["EVENT",{…}]`: publishes an event. */
    fun publish(event: NostrEvent): String = "[\"EVENT\",${event.toJson()}]"

    /** `["REQ","<sub>",{…}]`: subscribes to gift wraps for [pTagHex] since [sinceSecs]. */
    fun req(subId: String, kinds: List<Int>, pTagHex: String, sinceSecs: Long): String {
        val filter = StringBuilder()
        filter.append("{\"kinds\":[")
        kinds.forEachIndexed { i, k ->
            if (i > 0) filter.append(',')
            filter.append(k)
        }
        filter.append("],\"#p\":[\"").append(pTagHex).append("\"],\"since\":").append(sinceSecs).append('}')
        return "[\"REQ\",${NostrJson.quote(subId)},$filter]"
    }

    /** `["CLOSE","<sub>"]`: ends a subscription. */
    fun close(subId: String): String = "[\"CLOSE\",${NostrJson.quote(subId)}]"

    /** Parses one relay message; never throws. */
    fun parse(raw: String): Incoming {
        try {
            val arr = NostrJson.parse(raw) as? NostrJson.Arr ?: return Incoming.Unknown(raw)
            val label = (arr.items.firstOrNull() as? NostrJson.Str)?.v ?: return Incoming.Unknown(raw)
            return when (label) {
                "OK" -> {
                    if (arr.items.size < 4) return Incoming.Unknown(raw)
                    val id = (arr.items[1] as? NostrJson.Str)?.v ?: return Incoming.Unknown(raw)
                    val ok = (arr.items[2] as? NostrJson.Bool)?.v ?: return Incoming.Unknown(raw)
                    val reason = (arr.items[3] as? NostrJson.Str)?.v ?: ""
                    Incoming.Ok(id, ok, reason)
                }
                "EVENT" -> {
                    if (arr.items.size == 2) {
                        val obj = arr.items[1] as? NostrJson.Obj ?: return Incoming.Unknown(raw)
                        return Incoming.Publish(eventFrom(obj))
                    }
                    if (arr.items.size != 3) return Incoming.Unknown(raw)
                    val sub = (arr.items[1] as? NostrJson.Str)?.v ?: return Incoming.Unknown(raw)
                    val obj = arr.items[2] as? NostrJson.Obj ?: return Incoming.Unknown(raw)
                    Incoming.RelayEvent(sub, eventFrom(obj))
                }
                "EOSE" -> {
                    if (arr.items.size != 2) return Incoming.Unknown(raw)
                    val sub = (arr.items[1] as? NostrJson.Str)?.v ?: return Incoming.Unknown(raw)
                    Incoming.Eose(sub)
                }
                "CLOSED" -> {
                    if (arr.items.size < 2) return Incoming.Unknown(raw)
                    val sub = (arr.items[1] as? NostrJson.Str)?.v ?: return Incoming.Unknown(raw)
                    val reason = if (arr.items.size > 2) (arr.items[2] as? NostrJson.Str)?.v ?: "" else ""
                    Incoming.Closed(sub, reason)
                }
                "NOTICE" -> {
                    val text = if (arr.items.size > 1) (arr.items[1] as? NostrJson.Str)?.v ?: "" else ""
                    Incoming.Notice(text)
                }
                else -> Incoming.Unknown(raw)
            }
        } catch (e: Exception) {
            return Incoming.Unknown(raw)
        }
    }

    private fun eventFrom(obj: NostrJson.Obj): NostrEvent {
        val tags = when (val t = obj.opt("tags")) {
            null, NostrJson.Null -> emptyList()
            is NostrJson.Arr -> t.items.map { item ->
                val a = item as? NostrJson.Arr ?: throw NostrException("bad tags")
                a.items.map { s -> (s as? NostrJson.Str)?.v ?: throw NostrException("bad tag") }
            }
            else -> throw NostrException("bad tags")
        }
        return NostrEvent(
            id = obj.str("id"),
            pubkey = obj.str("pubkey"),
            createdAt = (obj.opt("created_at") as? NostrJson.Num)?.long() ?: throw NostrException("bad created_at"),
            kind = (obj.opt("kind") as? NostrJson.Num)?.int() ?: throw NostrException("bad kind"),
            tags = tags,
            content = obj.str("content"),
            sig = obj.str("sig"),
        )
    }
}
