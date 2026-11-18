package com.altradits.kengele.guardian.confirm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The all-clear builds only once the whole checklist is ticked. */
class ChecklistTest {
    private val now = 1_800_000_000L

    @Test
    fun emptyChecklistBuildsNothing() {
        val c = AllClearChecklist()
        assertFalse(c.isComplete())
        try {
            c.verification("voice", now)
            fail("empty checklist must fail")
        } catch (e: ConfirmException) {
            // Expected.
        }
    }

    @Test
    fun eachBoxIsRequired() {
        val boxes = listOf<(AllClearChecklist) -> Unit>(
            { it.spokeToActivist = true },
            { it.activistIsFree = true },
            { it.noSignsOfCoercion = true },
        )
        // Every pair without the third box still fails.
        for (skip in boxes.indices) {
            val c = AllClearChecklist()
            boxes.forEachIndexed { i, tick -> if (i != skip) tick(c) }
            assertFalse("skipping box $skip", c.isComplete())
            try {
                c.verification("video", now)
                fail("skipping box $skip must fail")
            } catch (e: ConfirmException) {
                // Expected.
            }
        }
    }

    @Test
    fun completeChecklistNeedsAMethod() {
        val c = AllClearChecklist().apply {
            spokeToActivist = true
            activistIsFree = true
            noSignsOfCoercion = true
        }
        assertTrue(c.isComplete())
        for (bad in listOf("", "sms", "VOICE")) {
            try {
                c.verification(bad, now)
                fail("method $bad must fail")
            } catch (e: ConfirmException) {
                // Expected.
            }
        }
        for (method in listOf("voice", "video")) {
            val v = c.verification(method, now)
            assertEquals(method, v.method)
            assertEquals(now, v.atSecs)
        }
    }
}
