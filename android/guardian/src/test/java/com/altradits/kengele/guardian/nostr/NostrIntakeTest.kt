package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.guardian.confirm.Action
import com.altradits.kengele.guardian.confirm.ConfirmBodies
import com.altradits.kengele.guardian.confirm.IgnoreReason
import com.altradits.kengele.guardian.confirm.ShamirVectors
import com.altradits.kengele.guardian.confirm.Tally
import com.altradits.kengele.guardian.confirm.TestKeys
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.Secp256k1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The intake maps tally outcomes to counted/ignored without ever throwing. */
class NostrIntakeTest {
    private val now = 1_800_000_000L
    private val activist = TestKeys.testPub("intake-activist")
    private val guardians = listOf("intake-g0", "intake-g1", "intake-g2").map { TestKeys.testPub(it) }
    private val stranger = TestKeys.testPub("intake-stranger")

    private fun delivery(event: KengeleMessage.Event): NostrTransport.Delivery =
        NostrTransport.Delivery(event, ByteArray(0))

    @Test
    fun countedAndIgnored() {
        val vec = ShamirVectors.named("k2-of-n3")
        val tally = Tally(activist, guardians, guardians[0], 2)
        fun confirm(signer: ByteArray, seq: Long) = KengeleMessage.Event(
            KengeleMessage.Kind.GUARDIAN_CONFIRM, now, seq,
            ConfirmBodies.encodeConfirm(activist, 1, vec.shares[0]), signer,
        )
        val first = NostrIntake.intake(delivery(confirm(guardians[0], 1)), tally, now)
        assertTrue(first is NostrIntake.Result.Counted)
        assertEquals(Action.NONE, (first as NostrIntake.Result.Counted).outcome.action)
        val dup = NostrIntake.intake(delivery(confirm(guardians[0], 2)), tally, now)
        assertTrue(dup is NostrIntake.Result.Ignored)
        assertEquals(IgnoreReason.DUPLICATE, (dup as NostrIntake.Result.Ignored).reason)
        val strangerVote = NostrIntake.intake(delivery(confirm(stranger, 3)), tally, now)
        assertEquals(IgnoreReason.NOT_GUARDIAN, (strangerVote as NostrIntake.Result.Ignored).reason)
    }

    @Test
    fun activistAllClearCountsAsCountedButEndsNothing() {
        val tally = Tally(activist, guardians, guardians[0], 2)
        val clear = KengeleMessage.Event(
            KengeleMessage.Kind.ALL_CLEAR, now, 1,
            ConfirmBodies.encodeAllClear(activist, 1), activist,
        )
        val r = NostrIntake.intake(delivery(clear), tally, now) as NostrIntake.Result.Counted
        assertEquals(Action.NONE, r.outcome.action)
        assertEquals(true, r.outcome.activistSaysSafe)
    }
}
