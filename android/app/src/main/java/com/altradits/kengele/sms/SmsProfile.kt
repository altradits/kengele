package com.altradits.kengele.sms

import com.altradits.kengele.message.KengeleMessage

/**
 * Per-guardian SMS fallback opt-in (docs/sms-transport.md, section 1).
 * SMS is OFF by default: every text tells the phone company that the
 * activist's number texted the guardian's number (threat model A1/A6).
 */
enum class SmsProfile {
    /** No SMS to this guardian. */
    OFF,

    /** Alarm kinds only, always alongside Nostr. */
    ALARM_ONLY,

    /** Alarm kinds always, plus ordinary check-ins while in shutdown mode. */
    SHUTDOWN_CHECKINS;

    /** Whether a batch of [kind] goes out by SMS under this profile. */
    fun sends(kind: KengeleMessage.Kind, shutdownMode: Boolean): Boolean = when (this) {
        OFF -> false
        ALARM_ONLY -> kind in ALARM_KINDS
        SHUTDOWN_CHECKINS -> kind in ALARM_KINDS || (shutdownMode && kind in SHUTDOWN_KINDS)
    }

    companion object {
        /** Panic, duress check-in, going dark, all clear (spec section 1). */
        val ALARM_KINDS: Set<KengeleMessage.Kind> = setOf(
            KengeleMessage.Kind.PANIC,
            KengeleMessage.Kind.DURESS_CHECK_IN,
            KengeleMessage.Kind.GOING_DARK,
            KengeleMessage.Kind.ALL_CLEAR,
        )

        /**
         * Ordinary check-ins, plus guardian-to-guardian missed and
         * guardian-confirm, which the spec sends "under the same
         * per-guardian opt-in" during a shutdown.
         */
        val SHUTDOWN_KINDS: Set<KengeleMessage.Kind> = setOf(
            KengeleMessage.Kind.CHECK_IN,
            KengeleMessage.Kind.MISSED,
            KengeleMessage.Kind.GUARDIAN_CONFIRM,
        )
    }
}
