package com.altradits.kengele.sms

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.pin.PinResult

/**
 * Which event kind one outgoing batch carries, so [SmsSender] can apply
 * the per-profile gating ([SmsProfile.sends]) the same way on the
 * check-in path and the panic path. Pure, so unit tests pin the mapping.
 */
object SmsBatchKind {
    /**
     * The batch kind for a check-in. WRONG maps to CHECK_IN like REAL:
     * CheckInManager sends nothing for a wrong PIN, so the value is
     * unused — but it must still be a valid kind, never null.
     */
    fun forCheckIn(pinResult: PinResult): KengeleMessage.Kind = when (pinResult) {
        PinResult.REAL -> KengeleMessage.Kind.CHECK_IN
        PinResult.DURESS -> KengeleMessage.Kind.DURESS_CHECK_IN
        PinResult.WRONG -> KengeleMessage.Kind.CHECK_IN
    }

    /** A panic batch always carries PANIC, an alarm kind. */
    fun forPanic(): KengeleMessage.Kind = KengeleMessage.Kind.PANIC
}
