package com.altradits.kengele.guardian.confirm

import com.altradits.kengele.message.KengeleMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The k-of-n rules: threshold releases, duplicates, expiry, the activist's
 * all-clear never counting, stale epochs and clear races. Mirrors the Go
 * `internal/confirm` cases that the tally owns.
 */
class TallyTest {
    private val now = 1_800_000_000L
    private val activist = TestKeys.testPub("tally-activist")
    private val guardians = listOf("tally-g0", "tally-g1", "tally-g2").map { TestKeys.testPub(it) }
    private val stranger = TestKeys.testPub("tally-stranger")
    private val vec = ShamirVectors.named("k2-of-n3")
    private var seq = 0L

    private fun tally(threshold: Int = 2): Tally = Tally(activist, guardians, guardians[0], threshold)

    private fun confirm(
        signer: ByteArray,
        epoch: Long = 1,
        share: ByteArray = vec.shares[0],
        at: Long = now,
    ): KengeleMessage.Event = KengeleMessage.Event(
        kind = KengeleMessage.Kind.GUARDIAN_CONFIRM,
        createdAtSecs = at,
        seq = ++seq,
        body = ConfirmBodies.encodeConfirm(activist, epoch, share),
        signer = signer,
    )

    private fun clear(
        signer: ByteArray,
        epoch: Long = 1,
        at: Long = now,
    ): KengeleMessage.Event = KengeleMessage.Event(
        kind = KengeleMessage.Kind.ALL_CLEAR,
        createdAtSecs = at,
        seq = ++seq,
        body = ConfirmBodies.encodeAllClear(activist, epoch),
        signer = signer,
    )

    private fun expectIgnore(t: Tally, e: KengeleMessage.Event, at: Long, reason: IgnoreReason) {
        try {
            t.handle(e, at)
            fail("expected $reason")
        } catch (ex: TallyException) {
            assertEquals(reason, ex.reason)
        }
    }

    @Test
    fun oneOfThreeDoesNothingPublic() {
        val t = tally()
        val out = t.handle(confirm(guardians[0], share = vec.shares[0]), now)
        assertEquals(Action.NONE, out.action)
        assertEquals(1, out.epoch)
        val c = t.confirmStatus(now)
        assertEquals(1, c.confirms)
        assertEquals(2, c.needed)
        assertFalse(c.released)
        assertFalse(t.released())
    }

    @Test
    fun twoOfThreeReleasesOnceWithTheRightKey() {
        val t = tally()
        t.handle(confirm(guardians[0], share = vec.shares[0]), now)
        val out = t.handle(confirm(guardians[1], share = vec.shares[1]), now)
        assertEquals(Action.RELEASE, out.action)
        assertEquals(2, out.confirmed)
        assertArrayEquals(vec.secret, out.key)
        assertTrue(t.released())
        assertTrue(t.spent())
        assertTrue(t.confirmStatus(now).released)
        // A third confirmation for the same alarm changes nothing.
        expectIgnore(t, confirm(guardians[2], share = vec.shares[2]), now, IgnoreReason.ALREADY_RELEASED)
    }

    @Test
    fun threeOfThreeReleases() {
        val v3 = ShamirVectors.named("k3-of-n5")
        val t = tally(threshold = 3)
        t.handle(confirm(guardians[0], share = v3.shares[0]), now)
        t.handle(confirm(guardians[1], share = v3.shares[1]), now)
        assertFalse(t.released())
        val out = t.handle(confirm(guardians[2], share = v3.shares[2]), now)
        assertEquals(Action.RELEASE, out.action)
        assertArrayEquals(v3.secret, out.key)
    }

    @Test
    fun duplicateConfirmationsIgnored() {
        val t = tally()
        t.handle(confirm(guardians[0], share = vec.shares[0]), now)
        expectIgnore(t, confirm(guardians[0], share = vec.shares[0]), now, IgnoreReason.DUPLICATE)
        // The duplicate did not count: one more distinct guardian releases.
        val out = t.handle(confirm(guardians[1], share = vec.shares[1]), now)
        assertEquals(Action.RELEASE, out.action)
    }

    @Test
    fun expiredConfirmationsIgnored() {
        val t = tally()
        val ttl = Tally.DEFAULT_CONFIRM_TTL_SECS
        // Too old, and from the future past the clock skew.
        expectIgnore(t, confirm(guardians[0], at = now - ttl - 1), now, IgnoreReason.EXPIRED)
        expectIgnore(
            t,
            confirm(guardians[0], at = now + KengeleMessage.MAX_CLOCK_SKEW_SECS + 1),
            now,
            IgnoreReason.EXPIRED,
        )
        // A confirmation that expires while waiting stops counting.
        t.handle(confirm(guardians[0], share = vec.shares[0], at = now), now)
        val later = now + ttl + 1
        val out = t.handle(confirm(guardians[1], share = vec.shares[1], at = later), later)
        assertEquals(Action.NONE, out.action)
        assertEquals(1, t.confirmStatus(later).confirms)
    }

    @Test
    fun forgedConfirmationsRejected() {
        val t = tally()
        // A stranger, and the activist themself: neither is an enrolled guardian.
        expectIgnore(t, confirm(stranger), now, IgnoreReason.NOT_GUARDIAN)
        expectIgnore(t, confirm(activist), now, IgnoreReason.NOT_GUARDIAN)
        // About a different activist.
        val other = TestKeys.testPub("tally-other-activist")
        val wrong = confirm(guardians[0]).copy(body = ConfirmBodies.encodeConfirm(other, 1, vec.shares[0]))
        expectIgnore(t, wrong, now, IgnoreReason.NOT_ACTIVIST)
        // No signer at all.
        expectIgnore(t, confirm(guardians[0]).copy(signer = null), now, IgnoreReason.BAD_BODY)
    }

    @Test
    fun staleAndFarAheadEpochs() {
        val t = tally()
        // Up to 3 epochs ahead are kept; further ahead is refused.
        t.handle(confirm(guardians[0], epoch = 4, share = vec.shares[0]), now)
        expectIgnore(t, confirm(guardians[0], epoch = 5), now, IgnoreReason.TOO_FAR_AHEAD)
        // Stand down alarm 1: its confirmations are now stale.
        t.handle(clear(guardians[0]), now)
        val end = t.handle(clear(guardians[1]), now)
        assertEquals(Action.STAND_DOWN, end.action)
        assertEquals(2, t.epoch())
        expectIgnore(t, confirm(guardians[0], epoch = 1), now, IgnoreReason.STALE)
    }

    @Test
    fun activistAllClearAloneEndsNothing() {
        val t = tally()
        val out = t.handle(clear(activist), now)
        assertEquals(Action.NONE, out.action)
        assertTrue(out.activistSaysSafe)
        assertEquals(1, t.epoch())
        val s = t.clearStatus(now)
        assertTrue(s.activistSaysSafe)
        assertEquals(0, s.guardianClears)
        assertEquals(2, s.needed)
        assertTrue(s.verifyFirst)
        // It never blocks a release either.
        t.handle(confirm(guardians[0], share = vec.shares[0]), now)
        val release = t.handle(confirm(guardians[1], share = vec.shares[1]), now)
        assertEquals(Action.RELEASE, release.action)
        // A second activist all-clear for the same alarm is a duplicate.
        expectIgnore(t, clear(activist), now, IgnoreReason.DUPLICATE)
    }

    @Test
    fun activistPlusOneGuardianIsNotEnough() {
        val t = tally()
        t.handle(clear(activist), now)
        val out = t.handle(clear(guardians[0]), now)
        assertEquals(Action.NONE, out.action)
        assertEquals(1, t.epoch())
        assertEquals(1, t.clearStatus(now).guardianClears)
    }

    @Test
    fun twoGuardiansStandDown() {
        val t = tally()
        t.handle(clear(guardians[0]), now)
        val out = t.handle(clear(guardians[1]), now)
        assertEquals(Action.STAND_DOWN, out.action)
        assertEquals(1, out.epoch)
        assertEquals(2, t.epoch())
        assertEquals(2, t.clearStatus(now).epoch)
    }

    @Test
    fun foundSafeAfterReleaseAndSpentKit() {
        val t = tally()
        t.handle(confirm(guardians[0], share = vec.shares[0]), now)
        t.handle(confirm(guardians[1], share = vec.shares[1]), now)
        t.handle(clear(guardians[0]), now)
        val out = t.handle(clear(guardians[1]), now)
        assertEquals(Action.FOUND_SAFE, out.action)
        assertArrayEquals(vec.secret, out.key)
        assertEquals(2, t.epoch())
        // The kit is spent: no new alarm can use it.
        expectIgnore(t, confirm(guardians[0], epoch = 2), now, IgnoreReason.SPENT)
    }

    @Test
    fun standDownFirstMakesLateConfirmsStale() {
        val t = tally()
        t.handle(clear(guardians[0]), now)
        t.handle(clear(guardians[1]), now)
        expectIgnore(t, confirm(guardians[0], epoch = 1), now, IgnoreReason.STALE)
    }

    @Test
    fun clearRulesMirrorConfirmRules() {
        val t = tally()
        // Stranger and expired clears are ignored; duplicates too.
        expectIgnore(t, clear(stranger), now, IgnoreReason.NOT_GUARDIAN)
        expectIgnore(
            t,
            clear(guardians[0], at = now - Tally.DEFAULT_CONFIRM_TTL_SECS - 1),
            now,
            IgnoreReason.EXPIRED,
        )
        t.handle(clear(guardians[0]), now)
        expectIgnore(t, clear(guardians[0]), now, IgnoreReason.DUPLICATE)
        // An expired clear stops counting in the status.
        assertEquals(0, t.clearStatus(now + Tally.DEFAULT_CONFIRM_TTL_SECS + 1).guardianClears)
    }

    @Test
    fun otherKindsAreIgnored() {
        val t = tally()
        for (kind in listOf(
            KengeleMessage.Kind.CHECK_IN,
            KengeleMessage.Kind.DURESS_CHECK_IN,
            KengeleMessage.Kind.PANIC,
            KengeleMessage.Kind.MISSED,
            KengeleMessage.Kind.GOING_DARK,
        )) {
            val e = KengeleMessage.Event(kind, now, ++seq, ByteArray(0), guardians[0])
            expectIgnore(t, e, now, IgnoreReason.IGNORED_KIND)
        }
    }

    @Test
    fun recipientsExcludeActivistAndSelf() {
        val t = tally()
        val recipients = t.recipients().map { Hex.toHex(it) }
        assertEquals(listOf(Hex.toHex(guardians[1]), Hex.toHex(guardians[2])), recipients)
        val senders = t.senders().map { Hex.toHex(it) }
        assertEquals(
            listOf(activist, guardians[0], guardians[1], guardians[2]).map { Hex.toHex(it) },
            senders,
        )
    }
}
