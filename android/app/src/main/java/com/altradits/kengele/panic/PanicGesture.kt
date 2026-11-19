package com.altradits.kengele.panic

/**
 * Five-press panic gesture detector (threat model docs/threat-model.md,
 * section 7: the panic trigger needs a deliberate gesture that drills
 * show is hard to fire by accident; docs/shutdown-mode.md "the moment
 * of capture").
 *
 * Counts screen on/off transitions: 5 presses within [windowMs] fires.
 * The window slides: each press drops every press older than [windowMs]
 * (a press exactly [windowMs] old still counts), then appends itself.
 * After firing the counter resets, so a second gesture needs 5 fresh
 * presses.
 *
 * Deliberately Android-free so JUnit tests cover every edge; the thin
 * [PanicGestureReceiver] feeds it `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF`
 * transitions (no special permission) and [PanicGestureService] keeps
 * that receiver registered during the watch window.
 */
class PanicGesture(
    val requiredPresses: Int = REQUIRED_PRESSES,
    val windowMs: Long = WINDOW_MS,
) {
    companion object {
        /** Presses needed to fire. */
        const val REQUIRED_PRESSES = 5

        /** Sliding window in which the presses must land. */
        const val WINDOW_MS = 10_000L
    }

    private val presses = ArrayDeque<Long>()

    /**
     * Records one screen on/off transition at [nowMs] (monotonic clock,
     * e.g. `SystemClock.elapsedRealtime()`). Returns true exactly when
     * this press completes the gesture.
     */
    fun press(nowMs: Long): Boolean {
        evict(nowMs)
        presses.addLast(nowMs)
        if (presses.size >= requiredPresses) {
            presses.clear()
            return true
        }
        return false
    }

    /** Presses still inside the window at [nowMs] (for status display). */
    fun pendingCount(nowMs: Long): Int {
        evict(nowMs)
        return presses.size
    }

    /** Drops every pending press without firing. */
    fun reset() {
        presses.clear()
    }

    private fun evict(nowMs: Long) {
        while (presses.isNotEmpty() && nowMs - presses.first() > windowMs) {
            presses.removeFirst()
        }
    }
}

/**
 * The gesture's on/off switch. Default ON with an in-app explanation
 * (see `PanicGestureActivity`); the activist can disable it. Plain
 * (not encrypted) storage: the flag is not sensitive.
 */
interface PanicGestureStore {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

/** Android-free store; the maintained tests pin its default. */
class InMemoryPanicGestureStore(enabled: Boolean = DEFAULT_ENABLED) : PanicGestureStore {
    private var enabled = enabled
    override fun isEnabled(): Boolean = enabled
    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
}

/** Shared default so the app and the tests cannot drift apart. */
const val DEFAULT_ENABLED = true

/**
 * Dispatch rule, Android-free so JUnit tests cover it: a disabled
 * gesture never fires and its presses are dropped (re-enabling starts
 * from zero pending presses).
 */
object PanicGestureDispatch {
    fun onToggle(store: PanicGestureStore, detector: PanicGesture, nowMs: Long): Boolean {
        if (!store.isEnabled()) {
            detector.reset()
            return false
        }
        return detector.press(nowMs)
    }
}
