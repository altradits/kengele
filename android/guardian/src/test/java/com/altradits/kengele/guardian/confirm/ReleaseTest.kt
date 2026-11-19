package com.altradits.kengele.guardian.confirm

import com.altradits.kengele.message.KengeleMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The authenticated release path: with the sealed kit enrolled the tally
 * tries every k-sized share group, so one bad share neither forges a
 * release nor blocks one, mirroring Go's `TestBadShareCannotForgeOrBlock`
 * and `TestKitTampering`.
 */
class ReleaseTest {
    private val now = 1_800_000_000L
    private val vec = KitOpenVectors.kitNamed("kit-2of3")
    private val activist = Hex.fromHex(vec.activistHex)
    private val guardians = vec.guardiansHex.map { Hex.fromHex(it) }
    private var seq = 0L

    private fun sealedKit(): SealedKit = SealedKit(
        version = vec.version,
        activistHex = vec.activistHex,
        alertPubHex = vec.alertPubHex,
        guardiansHex = vec.guardiansHex,
        threshold = vec.threshold,
        index = 0,
        ciphertext = vec.ciphertext,
    )

    private fun tally(): Tally = Tally(activist, guardians, guardians[0], vec.threshold, sealedKit = sealedKit())

    private fun confirm(signer: ByteArray, share: ByteArray, epoch: Long = 1): KengeleMessage.Event =
        KengeleMessage.Event(
            kind = KengeleMessage.Kind.GUARDIAN_CONFIRM,
            createdAtSecs = now,
            seq = ++seq,
            body = ConfirmBodies.encodeConfirm(activist, epoch, share),
            signer = signer,
        )

    private fun clear(signer: ByteArray, epoch: Long = 1): KengeleMessage.Event =
        KengeleMessage.Event(
            kind = KengeleMessage.Kind.ALL_CLEAR,
            createdAtSecs = now,
            seq = ++seq,
            body = ConfirmBodies.encodeAllClear(activist, epoch),
            signer = signer,
        )

    @Test
    fun twoHonestGuardiansReleaseTheAlert() {
        val t = tally()
        val first = t.handle(confirm(guardians[0], vec.shares[0]), now)
        assertEquals(Action.NONE, first.action)
        val out = t.handle(confirm(guardians[1], vec.shares[1]), now)
        assertEquals(Action.RELEASE, out.action)
        assertEquals(2, out.confirmed)
        assertEquals(vec.alert, out.alert)
        assertArrayEquals(vec.alertKey, out.alertKey)
        assertTrue(t.released())
        assertTrue(t.spent())
        val pending = t.pendingRelease()
        assertNotNull(pending)
        assertEquals(Action.RELEASE, pending!!.action)
        assertEquals(vec.alert, pending.alert)
        assertArrayEquals(vec.alertKey, pending.alertKey)
        KitOpen.wipe(out.alertKey!!)
        KitOpen.wipe(pending.alertKey)
    }

    @Test
    fun badShareNeitherForgesNorBlocks() {
        val t = tally()
        // Guardian 1 sends a garbage share: no release with only it and one other...
        val bad = ByteArray(33).also { it[32] = 7 }
        val none1 = t.handle(confirm(guardians[1], bad), now)
        assertEquals(Action.NONE, none1.action)
        val none2 = t.handle(confirm(guardians[0], vec.shares[0]), now)
        assertEquals(Action.NONE, none2.action)
        assertFalse(t.released())
        // ...but two honest guardians still release, skipping the bad group.
        val out = t.handle(confirm(guardians[2], vec.shares[2]), now)
        assertEquals(Action.RELEASE, out.action)
        assertEquals(3, out.confirmed)
        assertEquals(vec.alert, out.alert)
        assertArrayEquals(vec.alertKey, out.alertKey)
        KitOpen.wipe(out.alertKey!!)
    }

    @Test
    fun badShareAloneWithOneHonestNeverReleases() {
        val t = tally()
        val bad = ByteArray(33).also { it[32] = 7 }
        // Every k-sized group contains the bad share: nothing opens.
        t.handle(confirm(guardians[1], bad), now)
        val out = t.handle(confirm(guardians[0], vec.shares[0]), now)
        assertEquals(Action.NONE, out.action)
        assertFalse(t.released())
        assertFalse(t.spent())
        assertNull(t.pendingRelease())
    }

    @Test
    fun tamperedKitNeverReleases() {
        val badCt = vec.ciphertext.copyOf().also { it[30] = (it[30] + 1).toByte() }
        val t = Tally(activist, guardians, guardians[0], vec.threshold, sealedKit = sealedKit().copy(ciphertext = badCt))
        t.handle(confirm(guardians[0], vec.shares[0]), now)
        val out = t.handle(confirm(guardians[1], vec.shares[1]), now)
        assertEquals(Action.NONE, out.action)
        assertFalse(t.released())
    }

    @Test
    fun mismatchedKitIsRejected() {
        try {
            Tally(activist, guardians, guardians[0], vec.threshold, sealedKit = sealedKit().copy(version = 99))
            fail("bad kit version must be rejected")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
        try {
            Tally(activist, guardians, guardians[0], vec.threshold, sealedKit = sealedKit().copy(threshold = 3))
            fail("mismatched threshold must be rejected")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun foundSafeCarriesAlertAndKeyThenWipes() {
        val t = tally()
        t.handle(confirm(guardians[0], vec.shares[0]), now)
        val rel = t.handle(confirm(guardians[1], vec.shares[1]), now)
        KitOpen.wipe(rel.alertKey!!)
        t.handle(clear(guardians[0]), now)
        val safe = t.handle(clear(guardians[1]), now)
        assertEquals(Action.FOUND_SAFE, safe.action)
        assertEquals(vec.alert, safe.alert)
        assertArrayEquals(vec.alertKey, safe.alertKey)
        val pending = t.pendingRelease()
        assertNotNull(pending)
        assertEquals(Action.FOUND_SAFE, pending!!.action)
        assertArrayEquals(vec.alertKey, pending.alertKey)
        KitOpen.wipe(safe.alertKey!!)
        KitOpen.wipe(pending.alertKey)
        // Once the found-safe note is published the key is gone.
        t.clearPendingRelease()
        assertNull(t.pendingRelease())
        try {
            t.handle(confirm(guardians[0], vec.shares[0], epoch = 2), now)
            fail("spent kit must refuse new alarms")
        } catch (e: TallyException) {
            assertEquals(IgnoreReason.SPENT, e.reason)
        }
    }

    @Test
    fun threeOfFiveSkipsBadShare() {
        val v = KitOpenVectors.kitNamed("kit-3of5")
        val act = Hex.fromHex(v.activistHex)
        val gs = v.guardiansHex.map { Hex.fromHex(it) }
        val kit = SealedKit(v.version, v.activistHex, v.alertPubHex, v.guardiansHex, v.threshold, 0, v.ciphertext)
        val t = Tally(act, gs, gs[0], v.threshold, sealedKit = kit)
        fun conf(i: Int, share: ByteArray): KengeleMessage.Event = KengeleMessage.Event(
            kind = KengeleMessage.Kind.GUARDIAN_CONFIRM,
            createdAtSecs = now,
            seq = ++seq,
            body = ConfirmBodies.encodeConfirm(act, 1, share),
            signer = gs[i],
        )
        val bad = ByteArray(33).also { it[32] = 9 }
        t.handle(conf(0, bad), now)
        t.handle(conf(1, v.shares[1]), now)
        t.handle(conf(2, v.shares[2]), now)
        assertFalse(t.released())
        val out = t.handle(conf(3, v.shares[3]), now)
        assertEquals(Action.RELEASE, out.action)
        assertEquals(4, out.confirmed)
        assertEquals(v.alert, out.alert)
        KitOpen.wipe(out.alertKey!!)
    }
}
