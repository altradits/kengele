package com.altradits.kengele.message.nostr

import java.net.URI

/**
 * Relay URL handling, mirroring go-nostr's NormalizeURL: trims, defaults the
 * scheme (ws for loopback, wss otherwise), maps http(s) to ws(s), lowercases
 * the host and drops trailing slashes, keeping an explicit port and query.
 */
object RelayUrl {
    /** A normalized relay URL split for connecting. */
    data class Parsed(
        val scheme: String,
        val host: String,
        val port: Int,
        val tls: Boolean,
        /** Path plus query for the handshake request, always starting with `/`. */
        val path: String,
    )

    /** Normalizes [url] for dedup and connect; returns "" when invalid. */
    fun normalize(url: String): String {
        var work = url.trim()
        if (work.isEmpty() || work.any { it.isWhitespace() }) return ""
        if ("://" !in work) {
            val hostPart = work.substringBefore('/').substringBefore('?')
            val host = hostPart.substringBefore(':').lowercase()
            work = (if (host == "localhost" || host == "127.0.0.1") "ws://" else "wss://") + work
        }
        val uri = try {
            URI(work)
        } catch (e: Exception) {
            return ""
        }
        var scheme = (uri.scheme ?: "").lowercase()
        scheme = when (scheme) {
            "ws", "wss" -> scheme
            "http" -> "ws"
            "https" -> "wss"
            else -> return ""
        }
        val host = (uri.host ?: "").lowercase()
        if (host.isEmpty()) return ""
        if (uri.userInfo != null) return ""
        val port = uri.port // -1 when absent
        if (port != -1 && (port <= 0 || port > 65535)) return ""
        val path = (uri.rawPath ?: "").trimEnd('/')
        val out = StringBuilder()
        out.append(scheme).append("://").append(host)
        if (port != -1) out.append(':').append(port)
        out.append(path)
        if (uri.rawQuery != null && uri.rawQuery!!.isNotEmpty()) out.append('?').append(uri.rawQuery)
        return out.toString()
    }

    /** Normalizes every URL, dropping invalid ones and duplicates. */
    fun normalizeAll(urls: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (u in urls) {
            val n = normalize(u)
            if (n.isNotEmpty()) seen.add(n)
        }
        return seen.toList()
    }

    /** Splits a normalized URL for connecting; throws [NostrException]. */
    fun parse(normalized: String): Parsed {
        val uri = try {
            URI(normalized)
        } catch (e: Exception) {
            throw NostrException("bad relay URL")
        }
        val scheme = (uri.scheme ?: "").lowercase()
        val tls = when (scheme) {
            "wss" -> true
            "ws" -> false
            else -> throw NostrException("bad relay URL")
        }
        val host = uri.host ?: throw NostrException("bad relay URL")
        if (host.isEmpty()) throw NostrException("bad relay URL")
        val port = if (uri.port != -1) uri.port else if (tls) 443 else 80
        var path = uri.rawPath ?: ""
        if (uri.rawQuery != null && uri.rawQuery!!.isNotEmpty()) path += "?${uri.rawQuery}"
        if (path.isEmpty()) path = "/"
        return Parsed(scheme, host, port, tls, path)
    }
}
