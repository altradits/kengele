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
     * The opened alert and the key that signs the public notes (release and
     * found-safe only, when the tally holds the sealed kit). The caller owns
     * this copy of the key: wipe it with [KitOpen.wipe] after use; never log
     * or store it.
     */
    val alert: Alert? = null,
    val alertKey: ByteArray? = null,
    /**
     * The rebuilt sealing key K (release and found-safe only, only when the
     * sealed kit is not enrolled and the tally cannot open the alert). Never
     * log or store it.
     */
    val key: ByteArray? = null,
)

/**
 * A release waiting to be published: the opened alert, the key that signs
 * the public notes, and which note is due. Held in memory only, never
 * persisted; wiped once the found-safe note is published.
 */
data class PendingRelease(
    val action: Action,
    val epoch: Long,
    val confirmed: Int,
    val alert: Alert,
    /** A copy of the key; wipe it with [KitOpen.wipe] after use. */
    val alertKey: ByteArray,
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

/**
 * The persistable tally counts: epochs, votes, clears and the
 * spent/released flags. Shares persist (they are secret pieces, useless
 * below the threshold); the sealing key, the alert and the alert key never
 * do. See [TallyStore] for the versioned encoding.
 */
data class TallySnapshot(
    val cur: Long,
    val releasedEpoch: Long,
    val spent: Boolean,
    val releaseConfirmed: Int,
    val epochs: Map<Long, EpochSnapshot>,
)

data class EpochSnapshot(
    val confirms: Map<String, VoteSnapshot>,
    val clears: Map<String, Long>,
    val activistClearSecs: Long?,
)

data class VoteSnapshot(val atSecs: Long, val shareHex: String)

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
 * When [sealedKit] holds the enrolled sealed alert, a release rebuilds
 * the sealing key K from k fresh shares and opens the kit
 * (XChaCha20-Poly1305), exactly like Go's `tryRelease`: every k-sized share
 * group is tried, so one bad share neither forges a release (a wrong K
 * fails the authenticated open) nor blocks one (another group still opens).
 * The opened alert and alert key come back on the [Outcome] and stay in
 * memory as the [PendingRelease] until the found-safe note is published.
 * Without the sealed kit the tally falls back to combining the first k
 * fresh shares and returns K on the [Outcome], unauthenticated.
 */
class Tally(
    activist: ByteArray,
    guardians: List<ByteArray>,
    private val self: ByteArray,
    private val threshold: Int,
    private val ttlSecs: Long = DEFAULT_CONFIRM_TTL_SECS,
    private val sealedKit: SealedKit? = null,
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
    private var pending: PendingRelease? = null

    init {
        Enrollment.checkEnrollment(activist, guardians, threshold)
        require(enrolled.contains(selfHex)) { "this guardian is not enrolled" }
        require(ttlSecs > 0) { "ttl must be positive" }
        sealedKit?.let { kit ->
            try {
                KitOpen.checkKit(kit)
            } catch (e: KitException) {
                throw IllegalArgumentException(e.message)
            }
            require(kit.activistHex.lowercase() == activistHex) { "sealed kit is for a different activist" }
            require(kit.guardiansHex.map { it.lowercase() } == guardianHex) {
                "sealed kit names different guardians or a different order"
            }
            require(kit.threshold == threshold) { "sealed kit has a different threshold" }
            require(kit.guardiansHex[kit.index].lowercase() == selfHex) { "sealed kit index is not this guardian" }
        }
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
        val kit = sealedKit
        return if (kit != null) tryReleaseWithKit(kit, epoch, nowSecs) else tryReleaseLegacy(epoch, nowSecs)
    }

    /**
     * Rebuilds the sealing key from k fresh shares and opens the sealed kit,
     * mirroring Go's `tryRelease`: every k-sized group is tried, the rebuilt
     * K is wiped after each attempt, and a group whose shares do not combine
     * or open is skipped, never an error. A bad share from a misbehaving
     * guardian fails the authenticated open, so it can delay a release until
     * another honest guardian confirms but can neither forge nor block one.
     */
    private fun tryReleaseWithKit(kit: SealedKit, epoch: Long, nowSecs: Long): Outcome {
        val st = state(epoch)
        for ((g, v) in st.confirms.toMap()) {
            if (!fresh(v.atSecs, nowSecs)) st.confirms.remove(g) // expired: no longer counts
        }
        val freshShares = st.confirms.values.map { it.share }
        if (freshShares.size < threshold) return Outcome(epoch = epoch)
        var out: Outcome? = null
        eachCombination(freshShares.size, threshold) { idx ->
            val parts = idx.map { freshShares[it] }
            val key = try {
                Shamir.combine(parts)
            } catch (e: ShamirException) {
                return@eachCombination true // next group
            }
            val opened = try {
                KitOpen.open(kit, key)
            } catch (e: KitException) {
                KitOpen.wipe(key)
                return@eachCombination true // next group
            }
            KitOpen.wipe(key)
            releasedEpoch = epoch
            spent = true
            releaseConfirmed = freshShares.size
            pending = PendingRelease(Action.RELEASE, epoch, freshShares.size, opened.alert, opened.alertKey.copyOf())
            if (epoch > cur) cur = epoch
            out = Outcome(
                action = Action.RELEASE,
                epoch = epoch,
                confirmed = freshShares.size,
                alert = opened.alert,
                alertKey = opened.alertKey,
            )
            false
        }
        return out ?: Outcome(epoch = epoch)
    }

    /**
     * The fallback without an enrolled sealed kit: combines the first k
     * fresh shares in x-coordinate order and returns K unauthenticated. A bad
     * share here rebuilds the wrong key, so this path needs the sealed alert
     * enrolled to become trustworthy.
     */
    private fun tryReleaseLegacy(epoch: Long, nowSecs: Long): Outcome {
        val st = state(epoch)
        for ((g, v) in st.confirms.toMap()) {
            if (!fresh(v.atSecs, nowSecs)) st.confirms.remove(g) // expired: no longer counts
        }
        val freshShares = st.confirms.values.map { it.share }
        if (freshShares.size < threshold) return Outcome(epoch = epoch)
        val ordered = freshShares.sortedBy { it.last().toInt() and 0xFF }.take(threshold)
        val key = try {
            Shamir.combine(ordered)
        } catch (e: ShamirException) {
            // Shares that do not combine are not an ignore: the vote stays
            // counted and a later confirmation retries the release, like Go.
            // Throwing here used to strand the vote counted-but-unsaved.
            return Outcome(epoch = epoch)
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

    /**
     * Calls [f] with every k-sized subset of 0..n-1 until [f] returns false.
     * Mirrors Go's `combinations`; n is at most 10, so this is small.
     */
    private fun eachCombination(n: Int, k: Int, f: (IntArray) -> Boolean) {
        val idx = IntArray(k)
        fun rec(start: Int, depth: Int): Boolean {
            if (depth == k) return f(idx)
            for (i in start..n - (k - depth)) {
                idx[depth] = i
                if (!rec(i + 1, depth + 1)) return false
            }
            return true
        }
        rec(0, 0)
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

    /** Whether a release is waiting to be published. */
    @Synchronized
    fun hasPendingRelease(): Boolean = pending != null

    /**
     * The release waiting to be published, or null when there is none. The
     * returned key is a copy: wipe it with [KitOpen.wipe] after use.
     */
    @Synchronized
    fun pendingRelease(): PendingRelease? = pending?.copy(alertKey = pending!!.alertKey.copyOf())

    /**
     * Drops the pending release and wipes its key. Call it once the
     * found-safe note is published (or the kit is replaced). It also prunes
     * the released alarm's kept shares (see [end]).
     */
    @Synchronized
    fun clearPendingRelease() {
        pending?.let { KitOpen.wipe(it.alertKey) }
        pending = null
        if (releasedEpoch != 0L && releasedEpoch < cur) byEpoch.remove(releasedEpoch)
    }

    /** The persistable counts; keys and the alert never leave memory. */
    @Synchronized
    fun snapshot(): TallySnapshot = TallySnapshot(
        cur = cur,
        releasedEpoch = releasedEpoch,
        spent = spent,
        releaseConfirmed = releaseConfirmed,
        epochs = byEpoch.mapValues { (_, st) ->
            EpochSnapshot(
                confirms = st.confirms.mapValues { (_, v) -> VoteSnapshot(v.atSecs, Hex.toHex(v.share)) },
                clears = st.clears.toMap(),
                activistClearSecs = st.activistClearSecs,
            )
        },
    )

    /**
     * Restores counts saved with [snapshot], e.g. after a restart. When the
     * saved state had released and this tally holds the sealed kit, the
     * pending release is re-derived by re-opening the kit from the saved
     * shares: restoring an already-made release replays no vote, so expiry
     * is not re-checked for that step. Throws [IllegalArgumentException] for
     * a corrupt snapshot; the caller then starts fresh.
     */
    @Synchronized
    fun restore(snap: TallySnapshot) {
        require(snap.cur >= 1) { "bad snapshot epoch" }
        require(snap.releasedEpoch >= 0) { "bad snapshot release" }
        val states = mutableMapOf<Long, EpochState>()
        for ((ep, es) in snap.epochs) {
            require(ep >= 1) { "bad snapshot epoch" }
            val st = EpochState()
            for ((g, v) in es.confirms) {
                val share = try {
                    Hex.fromHex(v.shareHex)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("bad snapshot share")
                }
                require(share.size == ConfirmBodies.SHARE_SIZE) { "bad snapshot share" }
                st.confirms[g] = Vote(v.atSecs, share)
            }
            st.clears.putAll(es.clears)
            st.activistClearSecs = es.activistClearSecs
            states[ep] = st
        }
        byEpoch.clear()
        byEpoch.putAll(states)
        cur = snap.cur
        releasedEpoch = snap.releasedEpoch
        spent = snap.spent
        releaseConfirmed = snap.releaseConfirmed
        pending?.let { KitOpen.wipe(it.alertKey) }
        pending = null
        releaseKey = null
        reopenReleased()
    }

    /**
     * Rebuilds the in-memory pending release after [restore] from the saved
     * shares, trying every k-sized group like [tryReleaseWithKit]. When it
     * fails the flags stay as saved but there is nothing to publish with.
     */
    private fun reopenReleased() {
        val kit = sealedKit ?: return
        if (releasedEpoch == 0L) return
        val st = byEpoch[releasedEpoch] ?: return
        val shares = st.confirms.values.map { it.share }
        if (shares.size < threshold) return
        eachCombination(shares.size, threshold) { idx ->
            val parts = idx.map { shares[it] }
            val key = try {
                Shamir.combine(parts)
            } catch (e: ShamirException) {
                return@eachCombination true
            }
            val opened = try {
                KitOpen.open(kit, key)
            } catch (e: KitException) {
                KitOpen.wipe(key)
                return@eachCombination true
            }
            KitOpen.wipe(key)
            val action = if (releasedEpoch < cur) Action.FOUND_SAFE else Action.RELEASE
            pending = PendingRelease(action, releasedEpoch, releaseConfirmed, opened.alert, opened.alertKey.copyOf())
            KitOpen.wipe(opened.alertKey)
            false
        }
    }

    private fun end(epoch: Long): Outcome {
        val out = if (releasedEpoch == epoch) {
            val p = pending
            when {
                p != null -> {
                    pending = p.copy(action = Action.FOUND_SAFE)
                    Outcome(
                        action = Action.FOUND_SAFE,
                        epoch = epoch,
                        confirmed = p.confirmed,
                        alert = p.alert,
                        alertKey = p.alertKey.copyOf(),
                    )
                }
                releaseKey != null -> Outcome(
                    action = Action.FOUND_SAFE,
                    epoch = epoch,
                    confirmed = releaseConfirmed,
                    key = releaseKey?.copyOf(),
                )
                else -> Outcome(action = Action.FOUND_SAFE, epoch = epoch, confirmed = releaseConfirmed)
            }
        } else {
            Outcome(action = Action.STAND_DOWN, epoch = epoch)
        }
        cur = epoch + 1
        // A found-safe note still needs its shares after a restart (the key
        // is re-derived from them), so the released alarm's votes stay until
        // clearPendingRelease; past votes never count again (stale).
        val keep = if (out.action == Action.FOUND_SAFE) releasedEpoch else -1
        byEpoch.keys.toList().forEach { if (it < cur && it != keep) byEpoch.remove(it) }
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
