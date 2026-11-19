package com.altradits.kengele.guardian.confirm

import com.altradits.kengele.message.KengeleMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Snapshot persistence: roundtrips, upgrades and corrupt input. */
class TallyStoreTest {
    private val now = 1_800_000_000L
    private val activist = TestKeys.testPub("store-activist")
    private val guardians = listOf("store-g0", "store-g1", "store-g2").map { TestKeys.testPub(it) }
    private val vec = ShamirVectors.named("k2-of-n3")
    private var seq = 0L

    private fun tally(): Tally = Tally(activist, guardians, guardians[0], 2)

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
    fun roundtripsCountsAcrossARestart() {
        val t = tally()
        t.handle(confirm(guardians[0], vec.shares[0]), now)
        t.handle(clear(guardians[1]), now)
        t.handle(clear(activist), now)

        val saved = TallyStore.serialize(t.snapshot())
        val fresh = tally()
        val parsed = TallyStore.parse(saved)
        assertNotNull(parsed)
        fresh.restore(parsed!!)

        assertEquals(t.epoch(), fresh.epoch())
        assertEquals(t.confirmStatus(now), fresh.confirmStatus(now))
        assertEquals(t.clearStatus(now), fresh.clearStatus(now))
        assertEquals(t.released(), fresh.released())
        assertEquals(t.spent(), fresh.spent())
        // And the restored tally keeps counting: one more confirm releases.
        val out = fresh.handle(confirm(guardians[1], vec.shares[1]), now)
        assertEquals(Action.RELEASE, out.action)
    }

    @Test
    fun roundtripsReleaseAndFoundSafe() {
        val v = KitOpenVectors.kitNamed("kit-2of3")
        val act = Hex.fromHex(v.activistHex)
        val gs = v.guardiansHex.map { Hex.fromHex(it) }
        val kit = SealedKit(v.version, v.activistHex, v.alertPubHex, v.guardiansHex, v.threshold, 0, v.ciphertext)
        val t = Tally(act, gs, gs[0], v.threshold, sealedKit = kit)
        fun conf(i: Int): KengeleMessage.Event = KengeleMessage.Event(
            kind = KengeleMessage.Kind.GUARDIAN_CONFIRM,
            createdAtSecs = now,
            seq = ++seq,
            body = ConfirmBodies.encodeConfirm(act, 1, v.shares[i]),
            signer = gs[i],
        )
        t.handle(conf(0), now)
        val rel = t.handle(conf(1), now)
        assertEquals(Action.RELEASE, rel.action)
        KitOpen.wipe(rel.alertKey!!)

        // A restart re-derives the pending release from the saved shares.
        val restarted = Tally(act, gs, gs[0], v.threshold, sealedKit = kit)
        restarted.restore(TallyStore.parse(TallyStore.serialize(t.snapshot()))!!)
        assertTrue(restarted.released())
        assertTrue(restarted.spent())
        val pending = restarted.pendingRelease()
        assertNotNull(pending)
        assertEquals(Action.RELEASE, pending!!.action)
        assertEquals(v.alert, pending.alert)
        KitOpen.wipe(pending.alertKey)

        // The found-safe path survives a restart too.
        fun clr(i: Int): KengeleMessage.Event = KengeleMessage.Event(
            kind = KengeleMessage.Kind.ALL_CLEAR,
            createdAtSecs = now,
            seq = ++seq,
            body = ConfirmBodies.encodeAllClear(act, 1),
            signer = gs[i],
        )
        restarted.handle(clr(0), now)
        val restarted2 = Tally(act, gs, gs[0], v.threshold, sealedKit = kit)
        restarted2.restore(TallyStore.parse(TallyStore.serialize(restarted.snapshot()))!!)
        val safe = restarted2.handle(clr(1), now)
        assertEquals(Action.FOUND_SAFE, safe.action)
        assertEquals(v.alert, safe.alert)
        KitOpen.wipe(safe.alertKey!!)
    }

    @Test
    fun foundSafeSurvivesRestartBeforePublishing() {
        val v = KitOpenVectors.kitNamed("kit-2of3")
        val act = Hex.fromHex(v.activistHex)
        val gs = v.guardiansHex.map { Hex.fromHex(it) }
        val kit = SealedKit(v.version, v.activistHex, v.alertPubHex, v.guardiansHex, v.threshold, 0, v.ciphertext)
        fun freshTally(): Tally = Tally(act, gs, gs[0], v.threshold, sealedKit = kit)
        fun conf(i: Int): KengeleMessage.Event = KengeleMessage.Event(
            kind = KengeleMessage.Kind.GUARDIAN_CONFIRM,
            createdAtSecs = now,
            seq = ++seq,
            body = ConfirmBodies.encodeConfirm(act, 1, v.shares[i]),
            signer = gs[i],
        )
        fun clr(i: Int): KengeleMessage.Event = KengeleMessage.Event(
            kind = KengeleMessage.Kind.ALL_CLEAR,
            createdAtSecs = now,
            seq = ++seq,
            body = ConfirmBodies.encodeAllClear(act, 1),
            signer = gs[i],
        )
        val t = freshTally()
        t.handle(conf(0), now)
        KitOpen.wipe(t.handle(conf(1), now).alertKey!!)
        t.handle(clr(0), now)
        val safe = t.handle(clr(1), now)
        assertEquals(Action.FOUND_SAFE, safe.action)
        KitOpen.wipe(safe.alertKey!!)

        // The app dies before the found-safe note goes out. After a restart
        // the pending found-safe is back, with the key re-derived.
        val restarted = freshTally()
        restarted.restore(TallyStore.parse(TallyStore.serialize(t.snapshot()))!!)
        assertEquals(2, restarted.epoch())
        val pending = restarted.pendingRelease()
        assertNotNull(pending)
        assertEquals(Action.FOUND_SAFE, pending!!.action)
        assertEquals(v.alert, pending.alert)
        KitOpen.wipe(pending.alertKey)
        // Publishing it drops the kept shares: nothing past lingers.
        restarted.clearPendingRelease()
        assertFalse(restarted.snapshot().epochs.containsKey(1))
    }

    @Test
    fun ignoresMissingNewerAndMalformed() {
        assertNull(TallyStore.parse(""))
        assertNull(TallyStore.parse("not json"))
        assertNull(TallyStore.parse("{\"v\":999,\"cur\":1}"))
        assertNull(TallyStore.parse("{\"v\":0,\"cur\":1}"))
        assertNull(TallyStore.parse("[]"))
        val good = TallyStore.serialize(tally().snapshot())
        // Wrong types and out-of-range values are malformed too.
        assertNull(TallyStore.parse(good.replace("\"cur\":1", "\"cur\":\"1\"")))
        assertNull(TallyStore.parse(good.replace("\"spent\":false", "\"spent\":0")))
        assertNull(TallyStore.parse(good.replace("\"cur\":1", "\"cur\":0")))
        // Unknown fields are ignored, so newer writers stay readable.
        val extended = good.replace("{\"v\":1", "{\"v\":1,\"future\":[1,2],\"x\":null")
        assertNotNull(TallyStore.parse(extended))
    }

    @Test
    fun corruptSnapshotIsRejectedOnRestore() {
        val t = tally()
        t.handle(confirm(guardians[0], vec.shares[0]), now)
        val snap = t.snapshot()
        val g0 = Hex.toHex(guardians[0])
        val corrupt = snap.copy(
            epochs = mapOf(
                1L to EpochSnapshot(
                    confirms = mapOf(g0 to VoteSnapshot(now, "zz")),
                    clears = emptyMap(),
                    activistClearSecs = null,
                ),
            ),
        )
        try {
            tally().restore(corrupt)
            fail("corrupt snapshot must be rejected")
        } catch (e: IllegalArgumentException) {
            // Expected; the app then starts fresh.
        }
        assertFalse(tally().released())
    }

    @Test
    fun serializedFormIsStable() {
        // A fixed encoding, so a stored snapshot survives app upgrades that
        // keep schema 1: field order and shape never change.
        val t = tally()
        t.handle(confirm(guardians[0], vec.shares[0]), now)
        val text = TallyStore.serialize(t.snapshot())
        assertTrue(text.startsWith("{\"v\":1,\"cur\":1,\"released\":0,\"spent\":false,\"confirmed\":0,\"epochs\":{"))
        assertEquals(text, TallyStore.serialize(TallyStore.parse(text)!!.let {
            tally().also { f -> f.restore(it) }.snapshot()
        }))
    }
}
