package com.altradits.kengele.panic

import com.altradits.kengele.message.KengeleMessage

/**
 * Panic event builder (threat model docs/threat-model.md, section 7).
 *
 * The system-level trigger is the five-press gesture ([PanicGesture],
 * fed by [PanicGestureReceiver] while [PanicGestureService] is armed);
 * both it and the Panic button land on the confirmation dialog in
 * `MainActivity`, which sends over Nostr plus the SMS fallback.
 */
object PanicTrigger {
    /** Builds the panic event; empty body, like every panic. */
    fun buildEvent(createdSecs: Long, seq: Long): KengeleMessage.Event =
        KengeleMessage.Event(KengeleMessage.Kind.PANIC, createdSecs, seq)
}
