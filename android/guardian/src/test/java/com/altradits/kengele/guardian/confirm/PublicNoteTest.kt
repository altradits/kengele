package com.altradits.kengele.guardian.confirm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The public notes match the Go-built vectors byte-for-byte, and sign cleanly. */
class PublicNoteTest {
    private val alertKey = KitOpenVectors.kitNamed("kit-2of3").alertKey
    private val alertPub = KitOpenVectors.kitNamed("kit-2of3").alertPubHex

    @Test
    fun alertNoteMatchesGo() {
        val v = KitOpenVectors.noteNamed("alert")
        val unsigned = PublicNote.buildAlert(v.alert, v.epoch, v.confirmed, v.lastCheckin, v.place, v.now)
        assertEquals(1, unsigned.kind)
        assertEquals(v.now, unsigned.createdAt)
        assertEquals(v.tags, unsigned.tags)
        assertEquals(v.content, unsigned.content)
        val signed = unsigned.sign(alertKey)
        assertEquals(alertPub, signed.pubkey)
        assertEquals(v.tags, signed.tags)
        assertEquals(v.content, signed.content)
        assertTrue(signed.checkId())
        assertTrue(signed.checkSignature())
    }

    @Test
    fun alertNoteWithoutStatusMatchesGo() {
        // The guardian view: no watcher status, so no time and no place.
        val v = KitOpenVectors.noteNamed("alert-no-status")
        val unsigned = PublicNote.buildAlert(v.alert, v.epoch, v.confirmed, null, "", v.now)
        assertEquals(v.tags, unsigned.tags)
        assertEquals(v.content, unsigned.content)
        val signed = unsigned.sign(alertKey)
        assertTrue(signed.checkId())
        assertTrue(signed.checkSignature())
    }

    @Test
    fun foundSafeNoteMatchesGo() {
        val v = KitOpenVectors.noteNamed("found-safe")
        val unsigned = PublicNote.buildFoundSafe(v.alert, v.epoch, v.alertId, v.now)
        assertEquals(1, unsigned.kind)
        assertEquals(v.tags, unsigned.tags)
        assertEquals(v.content, unsigned.content)
        assertTrue(unsigned.tags.contains(listOf("e", v.alertId)))
        val signed = unsigned.sign(alertKey)
        assertTrue(signed.checkId())
        assertTrue(signed.checkSignature())
    }

    @Test
    fun foundSafeWithoutAlertIdOmitsETag() {
        val v = KitOpenVectors.noteNamed("found-safe")
        val unsigned = PublicNote.buildFoundSafe(v.alert, v.epoch, "", v.now)
        assertEquals(
            listOf(listOf("t", "kengele"), listOf("kengele", "found-safe", v.epoch.toString())),
            unsigned.tags,
        )
    }

    @Test
    fun refusesLocationsAndBadIds() {
        val v = KitOpenVectors.noteNamed("alert")
        try {
            PublicNote.buildAlert(v.alert, v.epoch, v.confirmed, v.lastCheckin, "-1.292066, 36.821945", v.now)
            fail("coordinates as place must be refused")
        } catch (e: PublicNoteException) {
            // Expected.
        }
        try {
            PublicNote.buildAlert(v.alert, v.epoch, v.confirmed, v.lastCheckin, "see https://maps.app.goo.gl/x", v.now)
            fail("map link as place must be refused")
        } catch (e: PublicNoteException) {
            // Expected.
        }
        try {
            PublicNote.buildFoundSafe(v.alert, v.epoch, "not-hex", v.now)
            fail("bad alert id must be refused")
        } catch (e: PublicNoteException) {
            // Expected.
        }
        try {
            PublicNote.buildAlert(v.alert, 0, v.confirmed, v.lastCheckin, v.place, v.now)
            fail("epoch 0 must be refused")
        } catch (e: PublicNoteException) {
            // Expected.
        }
    }
}
