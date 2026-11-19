package com.altradits.kengele.guardian.mesh

import com.altradits.kengele.guardian.confirm.Action
import com.altradits.kengele.guardian.confirm.ConfirmBodies
import com.altradits.kengele.guardian.confirm.IgnoreReason
import com.altradits.kengele.guardian.confirm.ShamirVectors
import com.altradits.kengele.guardian.confirm.Tally
import com.altradits.kengele.guardian.confirm.TallyStore
import com.altradits.kengele.guardian.confirm.TestKeys
import com.altradits.kengele.mesh.MeshCarry
import com.altradits.kengele.message.KengeleMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The off-grid receive path: sealed datagrams open against the enrolled kit
 * and feed the live tally exactly like the paste and Nostr paths. Made-up
 * test data only; never real people or keys.
 */
class MeshIntakeTest {
    private val now = 1_800_000_000L
    private val activist = TestKeys.testPub("mesh-intake-activist")
    private val activistPriv = TestKeys.testPriv("mesh-intake-activist")
    private val guardians = listOf("mesh-intake-g0", "mesh-intake-g1", "mesh-intake-g2")
        .map { TestKeys.testPub(it) }
    private val guardianPrivs = listOf("mesh-intake-g0", "mesh-intake-g1", "mesh-intake-g2")
        .map { TestKeys.testPriv(it) }
    private val strangerPriv = TestKeys.testPriv("mesh-intake-stranger")
    private val strangerPub = TestKeys.testPub("mesh-intake-stranger")
    private val vec = ShamirVectors.named("k2-of-n3")
    private var seq = 0L

    /** This phone is guardians[0]; the tally holds the enrolled kit's senders. */
    private fun tally(): Tally = Tally(activist, guardians, guardians[0], 2)

    private fun intake(wire: ByteArray, t: Tally): MeshIntake.Result =
        MeshIntake.intake(wire, guardianPrivs[0], t.senders(), t, now)

    private fun sealConfirm(signerPriv: ByteArray, share: ByteArray): ByteArray =
        KengeleMessage.seal(
            KengeleMessage.Event(
                KengeleMessage.Kind.GUARDIAN_CONFIRM, now, ++seq,
                ConfirmBodies.encodeConfirm(activist, 1L, share),
            ),
            signerPriv, guardians[0],
        )

    @Test
    fun sealedConfirmOverMeshCounts() {
        val t = tally()
        val r = intake(sealConfirm(guardianPrivs[1], vec.shares[1]), t)
        assertTrue(r is MeshIntake.Result.Counted)
        assertEquals(Action.NONE, (r as MeshIntake.Result.Counted).outcome.action)
        assertEquals(1, t.confirmStatus(now).confirms)
    }

    @Test
    fun duplicateVoteIsReportedIgnored() {
        val t = tally()
        val wire = sealConfirm(guardianPrivs[1], vec.shares[1])
        assertTrue(intake(wire, t) is MeshIntake.Result.Counted)
        val dup = intake(wire, t)
        assertTrue(dup is MeshIntake.Result.Ignored)
        assertEquals(IgnoreReason.DUPLICATE, (dup as MeshIntake.Result.Ignored).reason)
    }

    @Test
    fun crowdTrafficForOthersIsSilent() {
        // A null reason is the silent-drop contract: the inbox shows only
        // this phone's ignored events, never the crowd's.
        val t = tally()
        val random = intake(ByteArray(MeshCarry.DATAGRAM_SIZE) { it.toByte() }, t)
        assertTrue(random is MeshIntake.Result.Ignored)
        assertNull((random as MeshIntake.Result.Ignored).reason)
        val short = intake(ByteArray(100), t) as MeshIntake.Result.Ignored
        assertNull(short.reason)
        // Sealed by a stranger to a stranger: opens against nobody enrolled.
        val foreign = KengeleMessage.seal(
            KengeleMessage.Event(
                KengeleMessage.Kind.GUARDIAN_CONFIRM, now, ++seq,
                ConfirmBodies.encodeConfirm(activist, 1L, vec.shares[2]),
            ),
            strangerPriv, strangerPub,
        )
        val missed = intake(foreign, t) as MeshIntake.Result.Ignored
        assertNull(missed.reason)
        assertEquals(0, t.confirmStatus(now).confirms)
    }

    @Test
    fun activistCheckInOverMeshIsIgnoredKind() {
        // Opens — the activist is an enrolled sender — but the tally only
        // counts confirmations and all-clears.
        val t = tally()
        val wire = KengeleMessage.seal(
            KengeleMessage.Event(KengeleMessage.Kind.CHECK_IN, now, ++seq, "Mtihani".toByteArray()),
            activistPriv, guardians[0],
        )
        val r = intake(wire, t) as MeshIntake.Result.Ignored
        assertEquals(IgnoreReason.IGNORED_KIND, r.reason)
    }

    @Test
    fun activistAllClearOverMeshCountsButEndsNothing() {
        val t = tally()
        val wire = KengeleMessage.seal(
            ConfirmBodies.activistAllClearEvent(activist, 1L, ++seq, now),
            activistPriv, guardians[0],
        )
        val r = intake(wire, t) as MeshIntake.Result.Counted
        assertEquals(Action.NONE, r.outcome.action)
        assertEquals(true, r.outcome.activistSaysSafe)
    }

    @Test
    fun countedVotesSurviveASaveAfterEachVote() {
        // Regression: the inbox must persist the tally after every counted
        // mesh event, so a restart between two votes loses nothing. Each
        // round trip below stands in for GuardianState.saveTally plus a
        // restart; the second vote lands on the restored tally.
        val t = tally()
        val first = intake(sealConfirm(guardianPrivs[1], vec.shares[1]), t)
        assertTrue(first is MeshIntake.Result.Counted)

        val mid = tally()
        mid.restore(TallyStore.parse(TallyStore.serialize(t.snapshot()))!!)
        assertEquals(1, mid.confirmStatus(now).confirms)

        val second = MeshIntake.intake(
            sealConfirm(guardianPrivs[0], vec.shares[0]),
            guardianPrivs[0], mid.senders(), mid, now,
        ) as MeshIntake.Result.Counted
        assertEquals(Action.RELEASE, second.outcome.action)
        assertArrayEquals(vec.secret, second.outcome.key)

        val last = tally()
        last.restore(TallyStore.parse(TallyStore.serialize(mid.snapshot()))!!)
        assertEquals(2, last.confirmStatus(now).confirms)
        assertTrue(last.confirmStatus(now).released)
        assertTrue(last.released())
        assertTrue(last.spent())
    }

    @Test
    fun sealedConfirmAndAllClearAreIndistinguishableOnTheCarrier() {
        // A relay holding fragments cannot tell a confirmation from an
        // all-clear: same wire size, same fragment sizes, same header shape.
        val confirm = KengeleMessage.seal(
            ConfirmBodies.confirmEvent(activist, 1L, vec.shares[1], ++seq, now),
            guardianPrivs[1], guardians[0],
        )
        val clear = KengeleMessage.seal(
            ConfirmBodies.guardianAllClearEvent(
                activist, 1L, ++seq, now,
                ConfirmBodies.Verification(ConfirmBodies.METHOD_VOICE, now),
            ),
            guardianPrivs[2], guardians[0],
        )
        assertEquals(MeshCarry.DATAGRAM_SIZE, confirm.size)
        assertEquals(MeshCarry.DATAGRAM_SIZE, clear.size)
        val fragsA = MeshCarry.split(confirm, 0xAAAAAA, MeshCarry.DEFAULT_TTL)
        val fragsB = MeshCarry.split(clear, 0xBBBBBB, MeshCarry.DEFAULT_TTL)
        assertEquals(fragsA.size, fragsB.size)
        for (i in fragsA.indices) {
            assertEquals("frag $i size", fragsA[i].size, fragsB[i].size)
            val ha = MeshCarry.parseHeader(fragsA[i]).header
            val hb = MeshCarry.parseHeader(fragsB[i]).header
            assertEquals("frag $i shape", ha.copy(msgId = 0), hb.copy(msgId = 0))
        }
    }
}
