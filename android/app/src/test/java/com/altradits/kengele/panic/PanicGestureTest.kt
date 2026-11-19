package com.altradits.kengele.panic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Five-press gesture (threat model, section 7): 5 screen toggles within
 * 10 seconds fire; anything slower or shorter never does. All timestamps
 * are made-up monotonic-clock values.
 */
class PanicGestureTest {
    @Test
    fun exactlyFiveFires() {
        val gesture = PanicGesture()
        assertFalse(gesture.press(0))
        assertFalse(gesture.press(1_000))
        assertFalse(gesture.press(2_000))
        assertFalse(gesture.press(3_000))
        assertTrue(gesture.press(4_000))
    }

    @Test
    fun singlePressNeverFires() {
        val gesture = PanicGesture()
        assertFalse(gesture.press(7_000))
        assertEquals(1, gesture.pendingCount(7_000))
    }

    @Test
    fun fourPressesDoNotFire() {
        val gesture = PanicGesture()
        assertFalse(gesture.press(0))
        assertFalse(gesture.press(1_000))
        assertFalse(gesture.press(2_000))
        assertFalse(gesture.press(3_000))
        assertEquals(4, gesture.pendingCount(3_000))
    }

    @Test
    fun windowEdgeMinusOneSecondFires() {
        // Fifth press lands 9s after the first: inside the 10s window.
        val gesture = PanicGesture()
        assertFalse(gesture.press(0))
        assertFalse(gesture.press(1_000))
        assertFalse(gesture.press(2_000))
        assertFalse(gesture.press(3_000))
        assertTrue(gesture.press(9_000))
    }

    @Test
    fun windowEdgePlusOneSecondDoesNotFire() {
        // Fifth press lands 11s after the first: the first press has
        // lapsed, so only 4 are pending.
        val gesture = PanicGesture()
        assertFalse(gesture.press(0))
        assertFalse(gesture.press(1_000))
        assertFalse(gesture.press(2_000))
        assertFalse(gesture.press(3_000))
        assertFalse(gesture.press(11_000))
        assertEquals(4, gesture.pendingCount(11_000))
    }

    @Test
    fun exactWindowBoundaryStillCounts() {
        // A press exactly 10s old is still inside (inclusive edge).
        val gesture = PanicGesture()
        assertFalse(gesture.press(0))
        assertFalse(gesture.press(2_000))
        assertFalse(gesture.press(4_000))
        assertFalse(gesture.press(6_000))
        assertTrue(gesture.press(10_000))
    }

    @Test
    fun oneMillisecondPastTheWindowLapses() {
        val gesture = PanicGesture()
        assertFalse(gesture.press(0))
        assertFalse(gesture.press(2_000))
        assertFalse(gesture.press(4_000))
        assertFalse(gesture.press(6_000))
        assertFalse(gesture.press(10_001))
        assertEquals(4, gesture.pendingCount(10_001))
    }

    @Test
    fun slowFivePressesDoNotFire() {
        // 5 presses over 40s: the window never holds more than 2.
        val gesture = PanicGesture()
        for (t in longArrayOf(0, 10_000, 20_000, 30_000, 40_000)) {
            assertFalse(gesture.press(t))
        }
        assertEquals(2, gesture.pendingCount(40_000))
    }

    @Test
    fun counterResetsAfterFiring() {
        val gesture = PanicGesture()
        for (t in longArrayOf(0, 1_000, 2_000, 3_000)) gesture.press(t)
        assertTrue(gesture.press(4_000))
        // A second gesture needs 5 fresh presses.
        assertEquals(0, gesture.pendingCount(4_000))
        assertFalse(gesture.press(5_000))
        assertEquals(1, gesture.pendingCount(5_000))
    }

    @Test
    fun counterResetsAfterWindowLapses() {
        val gesture = PanicGesture()
        for (t in longArrayOf(0, 1_000, 2_000, 3_000)) gesture.press(t)
        assertEquals(0, gesture.pendingCount(30_000))
        assertFalse(gesture.press(30_000))
        assertEquals(1, gesture.pendingCount(30_000))
    }

    @Test
    fun settingDefaultsOn() {
        assertTrue(InMemoryPanicGestureStore().isEnabled())
    }

    @Test
    fun settingToggles() {
        val store = InMemoryPanicGestureStore()
        store.setEnabled(false)
        assertFalse(store.isEnabled())
        store.setEnabled(true)
        assertTrue(store.isEnabled())
    }

    @Test
    fun disabledGestureNeverFiresAndDropsPresses() {
        val store = InMemoryPanicGestureStore(enabled = false)
        val gesture = PanicGesture()
        for (t in longArrayOf(0, 1_000, 2_000, 3_000, 4_000)) {
            assertFalse(PanicGestureDispatch.onToggle(store, gesture, t))
        }
        // Re-enabling starts from zero: the disabled presses were dropped.
        store.setEnabled(true)
        assertEquals(0, gesture.pendingCount(4_000))
        assertFalse(PanicGestureDispatch.onToggle(store, gesture, 5_000))
        assertEquals(1, gesture.pendingCount(5_000))
    }

    @Test
    fun enabledDispatchFiresOnTheFifth() {
        val store = InMemoryPanicGestureStore()
        val gesture = PanicGesture()
        for (t in longArrayOf(0, 1_000, 2_000, 3_000)) {
            assertFalse(PanicGestureDispatch.onToggle(store, gesture, t))
        }
        assertTrue(PanicGestureDispatch.onToggle(store, gesture, 4_000))
    }
}
