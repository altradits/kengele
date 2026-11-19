package com.altradits.kengele.guardian.confirm

import com.altradits.kengele.message.nostr.NostrException
import com.altradits.kengele.message.nostr.NostrJson

/**
 * The versioned encoding of a [TallySnapshot] for encrypted prefs. Version 1
 * is the first persisted schema; anything older (no saved state) starts
 * fresh, and anything newer or malformed is ignored rather than crashing,
 * so a downgrade or a corrupt write can never brick the tally. Android-free
 * so plain JUnit covers it.
 */
object TallyStore {
    /** The current snapshot schema. Bump when the encoding changes. */
    const val SCHEMA_VERSION = 1

    fun serialize(snap: TallySnapshot): String {
        val out = StringBuilder()
        out.append("{\"v\":").append(SCHEMA_VERSION)
        out.append(",\"cur\":").append(snap.cur)
        out.append(",\"released\":").append(snap.releasedEpoch)
        out.append(",\"spent\":").append(if (snap.spent) "true" else "false")
        out.append(",\"confirmed\":").append(snap.releaseConfirmed)
        out.append(",\"epochs\":{")
        snap.epochs.entries.sortedBy { it.key }.forEachIndexed { ei, (ep, es) ->
            if (ei > 0) out.append(',')
            out.append(NostrJson.quote(ep.toString())).append(":{")
            out.append("\"confirms\":{")
            es.confirms.entries.sortedBy { it.key }.forEachIndexed { ci, (g, v) ->
                if (ci > 0) out.append(',')
                out.append(NostrJson.quote(g)).append(":[")
                    .append(v.atSecs).append(',')
                    .append(NostrJson.quote(v.shareHex)).append(']')
            }
            out.append("},\"clears\":{")
            es.clears.entries.sortedBy { it.key }.forEachIndexed { ci, (g, at) ->
                if (ci > 0) out.append(',')
                out.append(NostrJson.quote(g)).append(':').append(at)
            }
            out.append("},\"activist_clear\":")
            if (es.activistClearSecs == null) out.append("null") else out.append(es.activistClearSecs)
            out.append('}')
        }
        out.append("}}")
        return out.toString()
    }

    /**
     * Parses a snapshot, or null when [text] is empty, from another schema
     * version, or malformed. Unknown fields are ignored so newer writers
     * stay readable.
     */
    fun parse(text: String): TallySnapshot? {
        if (text.isEmpty()) return null
        return try {
            parseStrict(text)
        } catch (e: NostrException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun parseStrict(text: String): TallySnapshot? {
        val root = NostrJson.parse(text) as? NostrJson.Obj ?: return null
        if (num(root, "v") != SCHEMA_VERSION.toLong()) return null
        val cur = num(root, "cur") ?: return null
        val released = num(root, "released") ?: return null
        val spent = (root.opt("spent") as? NostrJson.Bool)?.v ?: return null
        val confirmed = num(root, "confirmed") ?: return null
        if (cur < 1 || released < 0 || confirmed < 0) return null
        val epochsObj = root.opt("epochs") as? NostrJson.Obj ?: return null
        val epochs = mutableMapOf<Long, EpochSnapshot>()
        for ((epKey, epVal) in epochsObj.fields) {
            val ep = epKey.toLongOrNull() ?: return null
            if (ep < 1 || epochs.containsKey(ep)) return null
            val eo = epVal as? NostrJson.Obj ?: return null
            val confirmsObj = eo.opt("confirms") as? NostrJson.Obj ?: return null
            val confirms = mutableMapOf<String, VoteSnapshot>()
            for ((g, v) in confirmsObj.fields) {
                val pair = v as? NostrJson.Arr ?: return null
                if (pair.items.size != 2) return null
                val at = (pair.items[0] as? NostrJson.Num)?.long() ?: return null
                val share = (pair.items[1] as? NostrJson.Str)?.v ?: return null
                if (at < 0 || confirms.containsKey(g)) return null
                confirms[g] = VoteSnapshot(at, share)
            }
            val clearsObj = eo.opt("clears") as? NostrJson.Obj ?: return null
            val clears = mutableMapOf<String, Long>()
            for ((g, v) in clearsObj.fields) {
                val at = (v as? NostrJson.Num)?.long() ?: return null
                if (at < 0 || clears.containsKey(g)) return null
                clears[g] = at
            }
            val activistClear = when (val a = eo.opt("activist_clear")) {
                null, NostrJson.Null -> null
                is NostrJson.Num -> a.long().also { if (it < 0) return null }
                else -> return null
            }
            epochs[ep] = EpochSnapshot(confirms, clears, activistClear)
        }
        return TallySnapshot(cur, released, spent, confirmed.toInt(), epochs)
    }

    private fun num(o: NostrJson.Obj, key: String): Long? = (o.opt(key) as? NostrJson.Num)?.long()
}
