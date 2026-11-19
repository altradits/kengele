package com.altradits.kengele.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Watch-window validation, mirroring schedule.Window in Go. */
class WatchWindowTest {
    private val good = WatchWindow(
        startSecs = 1_768_486_020,
        endSecs = 1_768_486_020 + 6 * 3600,
        intervalSecs = 90 * 60,
        graceSecs = 10 * 60,
        reminderSecs = 5 * 60,
    )

    @Test
    fun validWindow() {
        assertNull(good.validate())
        assertTrue(good.isValid())
        assertEquals(good.startSecs + 90 * 60 + 10 * 60, good.deadlineAfter(good.startSecs))
        assertEquals(good.startSecs + 90 * 60 - 5 * 60, good.reminderAt(good.startSecs + 90 * 60))
        assertTrue(good.isActive(good.startSecs))
        assertTrue(good.isActive(good.endSecs))
        assertFalse(good.isActive(good.startSecs - 1))
        assertFalse(good.isActive(good.endSecs + 1))
    }

    @Test
    fun rejectsBadWindows() {
        val cases = listOf(
            "window_needs_start_end" to good.copy(startSecs = 0),
            "window_needs_start_end" to good.copy(endSecs = 0),
            "window_end_after_start" to good.copy(endSecs = good.startSecs),
            "window_interval_positive" to good.copy(intervalSecs = 0),
            "window_interval_positive" to good.copy(intervalSecs = -60),
            "window_grace_reminder_negative" to good.copy(graceSecs = -1),
            "window_grace_reminder_negative" to good.copy(reminderSecs = -1),
            "window_reminder_shorter" to good.copy(reminderSecs = good.intervalSecs),
            "window_reminder_shorter" to good.copy(reminderSecs = good.intervalSecs + 1),
        )
        assertEquals(9, cases.size)
        for ((want, window) in cases) assertEquals(want, window.validate())
    }
}
