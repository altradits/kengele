package com.altradits.kengele.guardian.confirm

/**
 * The verify-by-voice/video checklist that gates a guardian's all-clear
 * (docs/guardian-confirmation.md: "Voice or video first"). The all-clear
 * screen ticks these boxes from its checkboxes; the event builds only when
 * every box is ticked and a call method is picked. Android-free so plain
 * JUnit covers the gating.
 */
class AllClearChecklist {
    /** The guardian spoke to the activist by a voice or video call. */
    var spokeToActivist: Boolean = false

    /** The activist confirmed they are free and safe. */
    var activistIsFree: Boolean = false

    /** The guardian saw no signs of coercion on the call. */
    var noSignsOfCoercion: Boolean = false

    fun isComplete(): Boolean = spokeToActivist && activistIsFree && noSignsOfCoercion

    /**
     * The verification for [ConfirmBodies.guardianAllClearEvent]. Throws
     * [ConfirmException] unless every box is ticked and [method] is "voice"
     * or "video".
     */
    fun verification(method: String, nowSecs: Long): ConfirmBodies.Verification {
        if (!isComplete()) {
            throw ConfirmException("complete the voice/video checklist before sending an all-clear")
        }
        if (method != ConfirmBodies.METHOD_VOICE && method != ConfirmBodies.METHOD_VIDEO) {
            throw ConfirmException("pick whether the check was a voice or a video call")
        }
        return ConfirmBodies.Verification(method, nowSecs)
    }
}
