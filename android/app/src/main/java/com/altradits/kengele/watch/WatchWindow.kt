package com.altradits.kengele.watch

/**
 * One watch window: from [startSecs] to [endSecs] (Unix seconds), a check-in
 * every [intervalSecs], late [graceSecs] after it is due, with a reminder
 * [reminderSecs] before it is due. Mirrors schedule.Window validation in Go
 * (watch windows are off most of the time, so Kengele cannot become another
 * way to be tracked; threat model docs/threat-model.md, section 5).
 */
data class WatchWindow(
    val startSecs: Long,
    val endSecs: Long,
    val intervalSecs: Long,
    val graceSecs: Long,
    val reminderSecs: Long,
) {
    /**
     * Returns null when the window is valid, else a stable message key the
     * Activity maps to the EN/SW string resource.
     */
    fun validate(): String? = when {
        startSecs <= 0 || endSecs <= 0 -> "window_needs_start_end"
        endSecs <= startSecs -> "window_end_after_start"
        intervalSecs <= 0 -> "window_interval_positive"
        graceSecs < 0 || reminderSecs < 0 -> "window_grace_reminder_negative"
        reminderSecs >= intervalSecs -> "window_reminder_shorter"
        else -> null
    }

    fun isValid(): Boolean = validate() == null

    /** The deadline after a check-in at [lastCheckinSecs]. */
    fun deadlineAfter(lastCheckinSecs: Long): Long = lastCheckinSecs + intervalSecs + graceSecs

    /** When the reminder for a check-in due at [dueSecs] should fire. */
    fun reminderAt(dueSecs: Long): Long = dueSecs - reminderSecs

    fun isActive(nowSecs: Long): Boolean = nowSecs in startSecs..endSecs
}
