package com.altradits.kengele.message

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * secp256k1 field/point arithmetic plus BIP-340 Schnorr signing and
 * verification, in plain JDK code (BigInteger) so the port has no native or
 * provider dependencies. Interop is pinned by the Go-generated vectors in
 * vectors.json: x-only public keys, the ECDH conversation key, the tagged
 * signed hash and the golden signature.
 *
 * Signing follows BIP-340 with fresh random aux by default; pass [aux] to
 * make a signature deterministic in tests.
 */
object Secp256k1 {
    val p: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val n: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val gx: BigInteger = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val gy: BigInteger = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)

    /** An affine point; null means the point at infinity. */
    data class Point(val x: BigInteger, val y: BigInteger)

    private val G = Point(gx, gy)

    private fun isEven(y: BigInteger): Boolean = !y.testBit(0)

    fun toBytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        when {
            raw.size == 32 -> raw.copyInto(out)
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyInto(out, 0, 1, 33)
            raw.size < 32 -> raw.copyInto(out, 32 - raw.size)
            else -> throw MessageException("integer does not fit 32 bytes")
        }
        return out
    }

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x) {
            if ((a.y + b.y).mod(p) == BigInteger.ZERO) return null
            return double(a)
        }
        val lam = ((b.y - a.y).mod(p) * (b.x - a.x).mod(p).modInverse(p)).mod(p)
        val x = (lam * lam - a.x - b.x).mod(p)
        val y = (lam * (a.x - x) - a.y).mod(p)
        return Point(x, y)
    }

    private fun double(a: Point): Point? {
        if (a.y == BigInteger.ZERO) return null
        val lam = ((BigInteger.valueOf(3) * a.x * a.x).mod(p) *
            (a.y * BigInteger.valueOf(2)).mod(p).modInverse(p)).mod(p)
        val x = (lam * lam - a.x * BigInteger.valueOf(2)).mod(p)
        val y = (lam * (a.x - x) - a.y).mod(p)
        return Point(x, y)
    }

    private fun multiply(k: BigInteger, point: Point): Point? {
        var result: Point? = null
        var addend: Point? = point
        for (i in k.bitLength() - 1 downTo 0) {
            result = add(result, result)
            if (k.testBit(i)) result = add(result, addend)
        }
        return result
    }

    /**
     * Lifts an x-only public key to the curve point with even Y, per BIP-340.
     * Used for signature verification and for NIP-44 ECDH (go-nostr decodes
     * the peer key with the even-Y prefix 0x02, which is the same point).
     */
    fun liftX(x: BigInteger): Point {
        if (x < BigInteger.ZERO || x >= p) throw MessageException("x out of field range")
        val y2 = (x.modPow(BigInteger.valueOf(3), p) + BigInteger.valueOf(7)).mod(p)
        var y = y2.modPow((p + BigInteger.ONE).divide(BigInteger.valueOf(4)), p)
        if ((y * y).mod(p) != y2) throw MessageException("x is not on the curve")
        if (!isEven(y)) y = p - y
        return Point(x, y)
    }

    fun liftX(xBytes: ByteArray): Point {
        if (xBytes.size != 32) throw MessageException("public key must be 32 bytes")
        return liftX(BigInteger(1, xBytes))
    }

    private fun secretInt(priv: ByteArray): BigInteger {
        if (priv.size != 32) throw MessageException("private key must be 32 bytes")
        val d = BigInteger(1, priv)
        if (d == BigInteger.ZERO || d >= n) throw MessageException("private key out of range")
        return d
    }

    /** x-only (Nostr-style) public key of a private key. */
    fun publicKey(priv: ByteArray): ByteArray {
        val point = multiply(secretInt(priv), G) ?: throw MessageException("unreachable")
        return toBytes32(point.x)
    }

    /** NIP-44 ECDH: x-coordinate of priv * liftX(peer), 32 bytes big-endian. */
    fun ecdh(priv: ByteArray, peerX: ByteArray): ByteArray {
        val shared = multiply(secretInt(priv), liftX(peerX)) ?: throw MessageException("ECDH failed")
        return toBytes32(shared.x)
    }

    /** BIP-340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || msg...). */
    fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val tagHash = md.digest(tag.toByteArray(Charsets.UTF_8))
        md.reset()
        md.update(tagHash)
        md.update(tagHash)
        for (part in parts) md.update(part)
        return md.digest()
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size) { "xor length mismatch" }
        return ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
    }

    private val random = SecureRandom()

    /**
     * BIP-340 sign [msg] (32 bytes) with [priv]. Random aux unless [aux] is
     * given; the result is verified before return so a bug fails loudly.
     */
    fun schnorrSign(priv: ByteArray, msg: ByteArray, aux: ByteArray? = null): ByteArray {
        require(msg.size == 32) { "message must be 32 bytes" }
        val d = secretInt(priv)
        val pub = multiply(d, G) ?: throw MessageException("unreachable")
        val dPrime = if (isEven(pub.y)) d else n - d
        val auxBytes = aux ?: ByteArray(32).also(random::nextBytes)
        require(auxBytes.size == 32) { "aux must be 32 bytes" }
        val t = xor(toBytes32(dPrime), taggedHash("BIP0340/aux", auxBytes))
        val pubX = toBytes32(pub.x)
        val k0 = BigInteger(1, taggedHash("BIP0340/nonce", t, pubX, msg)).mod(n)
        if (k0 == BigInteger.ZERO) throw MessageException("nonce is zero")
        val rPoint = multiply(k0, G) ?: throw MessageException("unreachable")
        val k = if (isEven(rPoint.y)) k0 else n - k0
        val rX = toBytes32(rPoint.x)
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rX, pubX, msg)).mod(n)
        val s = (k + e * dPrime).mod(n)
        val sig = rX + toBytes32(s)
        if (!schnorrVerify(publicKey(priv), msg, sig)) throw MessageException("signer produced a bad signature")
        return sig
    }

    /** BIP-340 verify [sig] (64 bytes) over [msg] for x-only key [pubX]. */
    fun schnorrVerify(pubX: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (pubX.size != 32 || msg.size != 32 || sig.size != 64) return false
        val pointP: Point
        try {
            pointP = liftX(pubX)
        } catch (e: MessageException) {
            return false
        }
        val r = BigInteger(1, sig.copyOfRange(0, 32))
        val s = BigInteger(1, sig.copyOfRange(32, 64))
        if (r >= p || s >= n) return false
        val e = BigInteger(1, taggedHash("BIP0340/challenge", sig.copyOfRange(0, 32), pubX, msg)).mod(n)
        val sG = multiply(s, G)
        val eP = multiply(e, pointP) ?: return false
        // R = s*G - e*P
        val negEP = Point(eP.x, (p - eP.y).mod(p))
        val rPoint = add(sG, negEP) ?: return false
        if (!isEven(rPoint.y)) return false
        return rPoint.x == r
    }
}
