package com.altradits.kengele.message

import java.io.File
import java.security.MessageDigest

/**
 * Reads the shared test vectors owned by the Go package
 * (internal/message/testdata/vectors.json). Both the Go tests and these
 * Kotlin tests check the same file, so the port cannot drift from the
 * format. All keys in the file are made up.
 */
object Vectors {
    private val file: File = sequenceOf(
        File("internal/message/testdata/vectors.json"),
        File("../internal/message/testdata/vectors.json"),
        File("../../internal/message/testdata/vectors.json"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("vectors.json not found under internal/message/testdata")

    private val text: String = file.readText()

    fun str(key: String): String {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"]*)\"").find(text)
            ?: throw AssertionError("vectors.json: missing string $key")
        return m.groupValues[1]
    }

    fun num(key: String): Long {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+)").find(text)
            ?: throw AssertionError("vectors.json: missing number $key")
        return m.groupValues[1].toLong()
    }

    fun hex(key: String): ByteArray = hexToBytes(str(key))

    fun hexToBytes(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex" }
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(b: ByteArray): String = buildString {
        for (x in b) append(String.format("%02x", x))
    }

    /**
     * Deterministic made-up private key for tests that need a second key.
     * Retried with a suffix until valid (always the first try in practice).
     */
    fun testPriv(seed: String): ByteArray {
        var i = 0
        while (true) {
            val label = if (i == 0) seed else "$seed:$i"
            val cand = MessageDigest.getInstance("SHA-256")
                .digest("kengele-test/$label".toByteArray(Charsets.UTF_8))
            try {
                Secp256k1.publicKey(cand)
                return cand
            } catch (e: MessageException) {
                i++
            }
        }
    }

    /** Deterministic made-up on-curve x-only key hex for guardian tests. */
    fun testGuardianHex(seed: String): String = bytesToHex(Secp256k1.publicKey(testPriv(seed)))
}
