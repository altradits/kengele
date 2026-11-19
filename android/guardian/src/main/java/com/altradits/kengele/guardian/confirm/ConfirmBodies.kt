package com.altradits.kengele.guardian.confirm

import com.altradits.kengele.message.KengeleMessage

/** Every failure to build or read a guardian-confirm or all-clear body. */
class ConfirmException(message: String) : Exception(message)

/**
 * Kotlin port of `internal/confirm/body.go` (docs/guardian-confirmation.md).
 *
 * Both bodies travel inside the signed, encrypted, fixed-size [KengeleMessage],
 * so carriers cannot tell them from a check-in:
 *
 * guardian-confirm: 0x01 | activist (32) | epoch (4) | share (33) = 70 bytes
 * all-clear:        0x02 | activist (32) | epoch (4)              = 37 bytes
 *
 * The epoch numbers alarms for one activist, starting at 1. Android-free so
 * plain JUnit covers it.
 */
object ConfirmBodies {
    const val BODY_CONFIRM: Byte = 0x01
    const val BODY_ALL_CLEAR: Byte = 0x02
    const val SHARE_SIZE = 33
    const val CONFIRM_BODY_SIZE = 1 + 32 + 4 + SHARE_SIZE // 70
    const val ALL_CLEAR_BODY_SIZE = 1 + 32 + 4 // 37

    const val METHOD_VOICE = "voice"
    const val METHOD_VIDEO = "video"

    data class ConfirmBody(val activist: ByteArray, val epoch: Long, val share: ByteArray)
    data class AllClearBody(val activist: ByteArray, val epoch: Long)

    fun encodeConfirm(activist: ByteArray, epoch: Long, share: ByteArray): ByteArray {
        if (activist.size != 32) throw ConfirmException("activist key must be 32 bytes")
        checkEpoch(epoch)
        if (share.size != SHARE_SIZE) throw ConfirmException("share must be $SHARE_SIZE bytes")
        val b = ByteArray(CONFIRM_BODY_SIZE)
        b[0] = BODY_CONFIRM
        activist.copyInto(b, 1)
        putU32(b, 33, epoch)
        share.copyInto(b, 37)
        return b
    }

    fun decodeConfirm(b: ByteArray): ConfirmBody {
        if (b.size != CONFIRM_BODY_SIZE || b[0] != BODY_CONFIRM) {
            throw ConfirmException("invalid confirmation body")
        }
        val epoch = getU32(b, 33)
        if (epoch == 0L) throw ConfirmException("invalid confirmation body")
        return ConfirmBody(
            activist = b.copyOfRange(1, 33),
            epoch = epoch,
            share = b.copyOfRange(37, CONFIRM_BODY_SIZE),
        )
    }

    fun encodeAllClear(activist: ByteArray, epoch: Long): ByteArray {
        if (activist.size != 32) throw ConfirmException("activist key must be 32 bytes")
        checkEpoch(epoch)
        val b = ByteArray(ALL_CLEAR_BODY_SIZE)
        b[0] = BODY_ALL_CLEAR
        activist.copyInto(b, 1)
        putU32(b, 33, epoch)
        return b
    }

    fun decodeAllClear(b: ByteArray): AllClearBody {
        if (b.size != ALL_CLEAR_BODY_SIZE || b[0] != BODY_ALL_CLEAR) {
            throw ConfirmException("invalid all-clear body")
        }
        val epoch = getU32(b, 33)
        if (epoch == 0L) throw ConfirmException("invalid all-clear body")
        return AllClearBody(activist = b.copyOfRange(1, 33), epoch = epoch)
    }

    /**
     * This guardian's confirmation of the given alarm. It carries the
     * guardian's share, so the caller must seal it only to the other
     * guardians ([Tally.recipients]), never to the activist.
     */
    fun confirmEvent(
        activist: ByteArray,
        epoch: Long,
        share: ByteArray,
        seq: Long,
        nowSecs: Long,
    ): KengeleMessage.Event = KengeleMessage.Event(
        kind = KengeleMessage.Kind.GUARDIAN_CONFIRM,
        createdAtSecs = nowSecs,
        seq = seq,
        body = encodeConfirm(activist, epoch, share),
    )

    /** The activist's own all-clear. Guardians must use [guardianAllClearEvent]. */
    fun activistAllClearEvent(
        activist: ByteArray,
        epoch: Long,
        seq: Long,
        nowSecs: Long,
    ): KengeleMessage.Event = KengeleMessage.Event(
        kind = KengeleMessage.Kind.ALL_CLEAR,
        createdAtSecs = nowSecs,
        seq = seq,
        body = encodeAllClear(activist, epoch),
    )

    /**
     * How a guardian checked that the activist is free before sending an
     * all-clear. It stays on the guardian's device and is not sent on the wire.
     */
    data class Verification(val method: String, val atSecs: Long)

    /**
     * A guardian's all-clear. Refuses unless [v] records a voice or video call
     * with the activist: the hook for the check required by
     * [Tally.clearStatus] (`verifyFirst`).
     */
    fun guardianAllClearEvent(
        activist: ByteArray,
        epoch: Long,
        seq: Long,
        nowSecs: Long,
        v: Verification,
    ): KengeleMessage.Event {
        if (v.method != METHOD_VOICE && v.method != METHOD_VIDEO) {
            throw ConfirmException("speak to the activist by voice or video before sending an all-clear")
        }
        if (v.atSecs == 0L || v.atSecs > nowSecs + KengeleMessage.MAX_CLOCK_SKEW_SECS) {
            throw ConfirmException("speak to the activist by voice or video before sending an all-clear")
        }
        return activistAllClearEvent(activist, epoch, seq, nowSecs)
    }

    private fun checkEpoch(epoch: Long) {
        if (epoch < 1 || epoch > 0xFFFFFFFFL) throw ConfirmException("invalid epoch")
    }

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFF).toByte()
        b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    private fun getU32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or
            ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or
            (b[o + 3].toLong() and 0xFF)
}
