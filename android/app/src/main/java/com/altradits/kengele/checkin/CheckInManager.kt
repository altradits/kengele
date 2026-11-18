package com.altradits.kengele.checkin

import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.pin.CheckInUiState
import com.altradits.kengele.pin.PinResult
import com.altradits.kengele.pin.PinStore

/**
 * Sends sealed check-in texts. Real transports (Nostr relay publishing, SMS)
 * are out of scope for the skeleton (issues #8, #9): implementations queue
 * the texts until a transport lands.
 */
interface CheckInSender {
    fun send(texts: List<String>)
}

/** Stub sender that records what would go out. Transport: not yet. */
class RecordingSender : CheckInSender {
    val batches = mutableListOf<List<String>>()
    override fun send(texts: List<String>) {
        batches.add(texts.toList())
    }
}

/** The screen plus what was (stub-)sent. [kind] is null when nothing went out. */
data class CheckInOutcome(
    val ui: CheckInUiState,
    val kind: KengeleMessage.Kind?,
    val texts: List<String>,
)

/**
 * Turns a PIN verification into a sealed check-in for every guardian.
 * DURESS builds a duress check-in but returns the identical success UI
 * (threat model docs/threat-model.md, section 4.1).
 */
class CheckInManager(private val sender: CheckInSender) {
    /**
     * Checks in. [place] is the activist-typed place (never GPS, by design;
     * threat model section 4.4), at most 86 bytes.
     */
    fun checkIn(
        pinResult: PinResult,
        createdSecs: Long,
        seq: Long,
        place: ByteArray,
        senderPriv: ByteArray,
        guardians: List<ByteArray>,
    ): CheckInOutcome {
        val ui = PinStore.uiState(pinResult)
        if (pinResult == PinResult.WRONG) return CheckInOutcome(ui, null, emptyList())
        require(guardians.isNotEmpty()) { "no guardians set up" }
        val kind = when (pinResult) {
            PinResult.REAL -> KengeleMessage.Kind.CHECK_IN
            PinResult.DURESS -> KengeleMessage.Kind.DURESS_CHECK_IN
            PinResult.WRONG -> error("unreachable")
        }
        val event = KengeleMessage.Event(kind, createdSecs, seq, place)
        // Same signature for every guardian; only the encryption differs.
        val inner = KengeleMessage.sign(event, senderPriv)
        val texts = guardians.map { g ->
            KengeleMessage.encodeText(KengeleMessage.sealInner(inner, senderPriv, g))
        }
        sender.send(texts)
        return CheckInOutcome(ui, kind, texts)
    }

    /** Panic trigger: an empty-body panic to every guardian. */
    fun panic(
        createdSecs: Long,
        seq: Long,
        senderPriv: ByteArray,
        guardians: List<ByteArray>,
    ): CheckInOutcome {
        require(guardians.isNotEmpty()) { "no guardians set up" }
        val event = KengeleMessage.Event(KengeleMessage.Kind.PANIC, createdSecs, seq)
        val inner = KengeleMessage.sign(event, senderPriv)
        val texts = guardians.map { g ->
            KengeleMessage.encodeText(KengeleMessage.sealInner(inner, senderPriv, g))
        }
        sender.send(texts)
        return CheckInOutcome(CheckInUiState(true, "panic_sent"), KengeleMessage.Kind.PANIC, texts)
    }
}
