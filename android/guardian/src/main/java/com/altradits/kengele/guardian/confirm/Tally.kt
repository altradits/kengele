package com.altradits.kengele.guardian.confirm

import com.altradits.kengele.message.KengeleMessage

/** Why [Tally.handle] ignored an event. Every reason means "ignore this event". */
enum class IgnoreReason {
    NOT_GUARDIAN,
    NOT_ACTIVIST,
    STALE,
    TOO_FAR_AHEAD,
    EXPIRED,
    DUPLICATE,
    ALREADY_RELEASED,
    SPENT,
    IGNORED_KIND,
    BAD_BODY,
}

/** Thrown by [Tally.handle] for an event that must be ignored. */
class TallyException(val reason: IgnoreReason, message: String) : Exception(message)

/** What the guardian's app must do after handling an event. */
enum class Action {
    /** Nothing changes in public. */
    NONE,

    /** k guardians confirmed. Publish the public alert once. */
    RELEASE,

    /** The alarm ended before anything went public. Tell the guardians privately. */
    STAND_DOWN,

    /** The alarm ended after the public alert. Publish a "found safe" update. */
    FOUND_SAFE,
}

/** The result of handling one event. */
data class Outcome(
    val action: Action = Action.NONE,
    val epoch: Long = 0,
    /** How many distinct guardians the release counted (release only). */
    val confirmed: Int = 0,
    /**
     * Set when this event was the activist's own all-clear. It ends nothing:
     * the app must tell guardians to verify by voice or video.
     */
    val activistSaysSafe: Boolean = false,
    /**
     * The rebuilt sealing key K (release and found-safe only). Opening the
     * sealed alert with K (XChaCha20-Poly1305) and publishing are follow-ups;
     * see the module README note in [Tally]. Never log or store it.
     */
    val key: ByteArray? = null,
)

/** What a guardian's app shows before its user may send an all-clear. */
data class ClearStatus(
    val epoch: Long,
    /** The activist signed an all-clear for this alarm. On its own it proves nothing. */
    val activistSaysSafe: Boolean,
    /** How many distinct, fresh guardian all-clears count so far. */
    val guardianClears: Int,
    /** The threshold k. */
    val needed: Int,
    /** Always true for an open alarm: verify by voice or video first. */
    val verifyFirst: Boolean = true,
)

/** Threshold progress for confirmations, for the guardian's screen. */
data class ConfirmStatus(
    val epoch: Long,
    /** How many distinct, fresh guardian confirmations count so far. */
    val confirms: Int,
    /** The threshold k. */
    val needed: Int,
    val released: Boolean,
)

/**
 * Kotlin port of `internal/confirm.Tally` (docs/guardian-confirmation.md): one
 * per watched activist, on each guardian's device. Counts guardian
 * confirmations and all-clears and says when to publish.
 *
 * A confirmation counts only if it is signed by an enrolled guardian, names
 * this activist and the current (or a near-future) epoch, is fresh (signed
 * within [ttlSecs], default 24h, and not from the future), and is the first
 * from that guardian for that epoch. The activist's own all-clear is recorded
 * and shown but never counted towards k. Only k guardians together end an
 * alarm. Android-free so plain JUnit covers it; safe for concurrent use.
 *
 * Release scope in this round: with k fresh shares [Tally] rebuilds the
 * sealing key K via [Shamir] and returns it on the [Outcome]. Decrypting the
 * sealed alert and alert key with K (XChaCha20-Poly1305 kit open) and
 * publishing the public notes are the follow-up; without the authenticated
 * open this port also cannot yet try every k-sized group to skip a bad share,
 * so it combines the first k fresh shares in x-coordinate order.
 */
class Tally(
    activist: ByteArray,
    guardians: List<ByteArray>,
    private val self: ByteArray,
    private val threshold: Int,
    private val ttlSecs: Long = DEFAULT_CONFIRM_TTL_SECS,
) {
    private val activistHex = Hex.toHex(activist.require32("activist"))
    private val guardianHex = guardians.map { Hex.toHex(it.require32("guardian")) }
    private val enrolled = guardianHex.toSet()
    private val selfHex = Hex.toHex(self.require32("self"))

    private data class Vote(val atSecs: Long, val share: ByteArray)
    private class EpochState {
        val confirms = mutableMapOf<String, Vote>()
        val clears = mutableMapOf<String, Long>()
        var activistClearSecs: Long? = null
    }

    private val byEpoch = mutableMapOf<Long, EpochState>()
    private var cur: Long = 1
    private var releasedEpoch: Long = 0
    private var spent = false
    private var releaseKey: ByteArray? = null
    private var releaseConfirmed = 0

    init {
        Enrollment.checkEnrollment(activist, guardians, threshold)
        require(enrolled.contains(selfHex)) { "this guardian is not enrolled" }
        require(ttlSecs > 0) { "ttl must be positive" }
    }

    /** The number of the current alarm for this activist. */
    @Synchronized
    fun epoch(): Long = cur

    /** Whether the public alert has gone out. */
    @Synchronized
    fun released(): Boolean = releasedEpoch != 0L

    /** Whether the kit's sealing key has been used. A spent kit needs replacing. */
    @Synchronized
    fun spent(): Boolean = spent

    /**
     * The guardians a confirmation must be sealed to: every guardian except
     * this one. The activist is deliberately not included.
     */
    fun recipients(): List<ByteArray> =
        guardianHex.filter { it != selfHex }.map { Hex.fromHex(it) }

    /**
     * The keys whose events this tally accepts: the activist and every
     * guardian. Pass it to [KengeleMessage.open].
     */
    fun senders(): List<ByteArray> =
        listOf(Hex.fromHex(activistHex)) + guardianHex.map { Hex.fromHex(it) }

    /**
     * Counts one opened event. The guardian's own confirmation and all-clear
     * must be passed in too. Throws [TallyException] for an ignored event.
     */
    @Synchronized
    fun handle(event: KengeleMessage.Event, nowSecs: Long): Outcome {
        val signer = event.signer ?: throw TallyException(IgnoreReason.BAD_BODY, "event has no signer")
        return when (event.kind) {
            KengeleMessage.Kind.GUARDIAN_CONFIRM -> confirm(event, Hex.toHex(signer), nowSecs)
            KengeleMessage.Kind.ALL_CLEAR -> allClear(event, Hex.toHex(signer), nowSecs)
            else -> throw TallyException(
                IgnoreReason.IGNORED_KIND,
                "event kind is not a confirmation or all-clear",
            )
        }
    }

    /** The all-clear state of the current alarm. */
    @Synchronized
    fun clearStatus(nowSecs: Long): ClearStatus {
        val st = byEpoch[cur]
        var n = 0
        if (st != null) {
            for ((g, at) in st.clears.toMap()) {
                if (fresh(at, nowSecs)) n++ else st.clears.remove(g)
            }
        }
        return ClearStatus(
            epoch = cur,
            activistSaysSafe = st?.activistClearSecs != null,
            guardianClears = n,
            needed = threshold,
        )
    }

    /** The confirmation progress of the current alarm. */
    @Synchronized
    fun confirmStatus(nowSecs: Long): ConfirmStatus {
        val st = byEpoch[cur]
        var n = 0
        if (st != null) {
            for ((g, v) in st.confirms.toMap()) {
                if (fresh(v.atSecs, nowSecs)) n++ else st.confirms.remove(g)
            }
        }
        return ConfirmStatus(epoch = cur, confirms = n, needed = threshold, released = releasedEpoch == cur)
    }

    private fun confirm(event: KengeleMessage.Event, signerHex: String, nowSecs: Long): Outcome {
        if (!enrolled.contains(signerHex)) {
            throw TallyException(IgnoreReason.NOT_GUARDIAN, "signer is not an enrolled guardian")
        }
        val c = try {
            ConfirmBodies.decodeConfirm(event.body)
        } catch (e: ConfirmException) {
            throw TallyException(IgnoreReason.BAD_BODY, e.message ?: "invalid confirmation body")
        }
        if (Hex.toHex(c.activist) != activistHex) {
            throw TallyException(IgnoreReason.NOT_ACTIVIST, "event is about a different activist")
        }
        checkEpoch(c.epoch)
        if (releasedEpoch == c.epoch) {
            throw TallyException(IgnoreReason.ALREADY_RELEASED, "alert already released for this alarm")
        }
        if (spent) {
            throw TallyException(
                IgnoreReason.SPENT,
                "this kit's sealing key has already been used; the activist must make a new kit",
            )
        }
        if (!fresh(event.createdAtSecs, nowSecs)) {
            throw TallyException(IgnoreReason.EXPIRED, "confirmation is too old")
        }
        val st = state(c.epoch)
        if (st.confirms.containsKey(signerHex)) {
            throw TallyException(IgnoreReason.DUPLICATE, "this guardian already counted for this alarm")
        }
        st.confirms[signerHex] = Vote(event.createdAtSecs, c.share)
        return tryRelease(c.epoch, nowSecs)
    }

    private fun tryRelease(epoch: Long, nowSecs: Long): Outcome {
        val st = state(epoch)
        for ((g, v) in st.confirms.toMap()) {
            if (!fresh(v.atSecs, nowSecs)) st.confirms.remove(g) // expired: no longer counts
        }
        val freshShares = st.confirms.values.map { it.share }
        if (freshShares.size < threshold) return Outcome(epoch = epoch)
        // Deterministic order; the authenticated kit open that lets the Go
        // side try every k-sized group is the follow-up (see class note).
        val ordered = freshShares.sortedBy { it.last().toInt() and 0xFF }.take(threshold)
        val key = try {
            Shamir.combine(ordered)
        } catch (e: ShamirException) {
            throw TallyException(IgnoreReason.BAD_BODY, e.message ?: "bad share")
        }
        releasedEpoch = epoch
        spent = true
        releaseKey = key.copyOf()
        releaseConfirmed = freshShares.size
        if (epoch > cur) cur = epoch
        return Outcome(
            action = Action.RELEASE,
            epoch = epoch,
            confirmed = freshShares.size,
            key = key,
        )
    }

    private fun allClear(event: KengeleMessage.Event, signerHex: String, nowSecs: Long): Outcome {
        val c = try {
            ConfirmBodies.decodeAllClear(event.body)
        } catch (e: ConfirmException) {
            throw TallyException(IgnoreReason.BAD_BODY, e.message ?: "invalid all-clear body")
        }
        if (Hex.toHex(c.activist) != activistHex) {
            throw TallyException(IgnoreReason.NOT_ACTIVIST, "event is about a different activist")
        }
        checkEpoch(c.epoch)
        if (signerHex == activistHex) {
            // Captors holding the activist can force the real PIN, so the
            // activist's own all-clear never ends an alarm and is not counted
            // towards k. It is recorded and shown to guardians, who must
            // verify by voice or video. It never blocks or delays a release.
            val st = state(c.epoch)
            if (st.activistClearSecs != null) {
                throw TallyException(IgnoreReason.DUPLICATE, "activist already sent an all-clear for this alarm")
            }
            st.activistClearSecs = event.createdAtSecs
            return Outcome(epoch = c.epoch, activistSaysSafe = true)
        }
        if (!enrolled.contains(signerHex)) {
            throw TallyException(IgnoreReason.NOT_GUARDIAN, "signer is not an enrolled guardian")
        }
        if (!fresh(event.createdAtSecs, nowSecs)) {
            throw TallyException(IgnoreReason.EXPIRED, "all-clear is too old")
        }
        val st = state(c.epoch)
        if (st.clears.containsKey(signerHex)) {
            throw TallyException(IgnoreReason.DUPLICATE, "this guardian already cleared this alarm")
        }
        st.clears[signerHex] = event.createdAtSecs
        var n = 0
        for ((g, at) in st.clears.toMap()) {
            if (fresh(at, nowSecs)) n++ else st.clears.remove(g)
        }
        if (n < threshold) return Outcome(epoch = c.epoch)
        return end(c.epoch)
    }

    private fun end(epoch: Long): Outcome {
        val out = if (releasedEpoch == epoch) {
            Outcome(
                action = Action.FOUND_SAFE,
                epoch = epoch,
                confirmed = releaseConfirmed,
                key = releaseKey?.copyOf(),
            )
        } else {
            Outcome(action = Action.STAND_DOWN, epoch = epoch)
        }
        cur = epoch + 1
        byEpoch.keys.toList().forEach { if (it < cur) byEpoch.remove(it) }
        return out
    }

    private fun checkEpoch(ep: Long) {
        if (ep < cur) throw TallyException(IgnoreReason.STALE, "event belongs to an earlier alarm")
        if (ep > cur + MAX_AHEAD) {
            throw TallyException(IgnoreReason.TOO_FAR_AHEAD, "event is for an alarm too far ahead")
        }
    }

    private fun state(ep: Long): EpochState = byEpoch.getOrPut(ep) { EpochState() }

    private fun fresh(atSecs: Long, nowSecs: Long): Boolean {
        if (atSecs > nowSecs + KengeleMessage.MAX_CLOCK_SKEW_SECS) return false
        return nowSecs - atSecs <= ttlSecs
    }

    private fun ByteArray.require32(what: String): ByteArray {
        require(size == 32) { "$what key must be 32 bytes" }
        return this
    }

    companion object {
        /** How long a confirmation stays valid. An old one must not combine with a new one days later. */
        const val DEFAULT_CONFIRM_TTL_SECS = 24L * 3600

        /**
         * How far ahead of the current alarm a confirmation may be kept, so a
         * misbehaving guardian cannot fill memory.
         */
        const val MAX_AHEAD = 3
    }
}
