package com.altradits.kengele.panic

import com.altradits.kengele.message.KengeleMessage

/**
 * Panic trigger stub (threat model docs/threat-model.md, section 7: the
 * trigger needs a deliberate gesture, e.g. 5 power-button presses, that
 * drills show is hard to fire by accident).
 *
 * This skeleton exposes event building only. Still missing before any real
 * use: the system-level gesture, the no-questions confirmation screen, and
 * the transports that carry the panic out (issues #8, #9).
 */
object PanicTrigger {
    /** Builds the panic event; empty body, like every panic. */
    fun buildEvent(createdSecs: Long, seq: Long): KengeleMessage.Event =
        KengeleMessage.Event(KengeleMessage.Kind.PANIC, createdSecs, seq)
}
