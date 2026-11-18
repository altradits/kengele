package com.altradits.kengele.message.nostr

import java.io.File

/**
 * Reads the Nostr cross-compat vectors made by the repo's own Go transport
 * (`nostr-vectors.json`, generated from `internal/transport/nostr` plus
 * go-nostr). All keys in the file are made up.
 */
object NostrVectors {
    private val text: String = run {
        val viaClasspath = NostrVectors::class.java.getResourceAsStream("/nostr-vectors.json")
            ?.use { String(it.readBytes(), Charsets.UTF_8) }
        if (viaClasspath != null) return@run viaClasspath
        sequenceOf(
            File("android/message/src/test/resources/nostr-vectors.json"),
            File("../message/src/test/resources/nostr-vectors.json"),
            File("src/test/resources/nostr-vectors.json"),
        ).firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("nostr-vectors.json not found")
    }

    private val root = NostrJson.parse(text) as NostrJson.Obj

    fun str(key: String): String = (root.fields[key] as? NostrJson.Str)?.v
        ?: throw AssertionError("vectors: missing string $key")

    fun num(key: String): Long = (root.fields[key] as? NostrJson.Num)?.long()
        ?: throw AssertionError("vectors: missing number $key")

    fun hex(key: String): ByteArray = hexToBytes(str(key))

    fun obj(key: String): NostrJson.Obj = root.fields[key] as? NostrJson.Obj
        ?: throw AssertionError("vectors: missing object $key")

    fun arr(key: String): List<NostrJson.Value> = (root.fields[key] as? NostrJson.Arr)?.items
        ?: throw AssertionError("vectors: missing array $key")

    fun hexToBytes(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex" }
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(b: ByteArray): String = buildString {
        for (x in b) append(String.format("%02x", x))
    }
}
