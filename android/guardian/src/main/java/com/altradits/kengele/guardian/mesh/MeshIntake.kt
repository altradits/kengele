package com.altradits.kengele.guardian.mesh

import com.altradits.kengele.guardian.confirm.IgnoreReason
import com.altradits.kengele.guardian.confirm.Outcome
import com.altradits.kengele.guardian.confirm.Tally
import com.altradits.kengele.guardian.confirm.TallyException
import com.altradits.kengele.message.KengeleMessage
import com.altradits.kengele.message.MessageException

/**
 * Feeds mesh-carried datagrams into the tally: the off-grid receive path
 * (docs/offgrid-comms.md, threat model A6). Each completed 228-byte
 * datagram is opened with this phone's guardian key against the enrolled
 * senders and counted exactly like the paste and Nostr paths; anything the
 * tally ignores is reported, not thrown. Android-free so plain JUnit
 * covers it.
 *
 * This is the endpoint, not the relay path: opening here is what the blind
 * carrier in `:message` must never do. The caller owns persistence: save
 * the tally after every [Result.Counted], like the Nostr path does.
 */
object MeshIntake {
    sealed interface Result {
        /** The event counted; [outcome] says what the app must do. */
        data class Counted(val outcome: Outcome) : Result

        /**
         * The datagram counted for nothing. A null [reason] means the bytes
         * did not open against any enrolled sender — crowd traffic for
         * someone else — and the caller drops it silently; a set reason
         * means this phone's event was ignored, and the caller shows it.
         */
        data class Ignored(val reason: IgnoreReason?, val message: String?) : Result
    }

    fun intake(
        datagram: ByteArray,
        ownPriv: ByteArray,
        senders: List<ByteArray>,
        tally: Tally,
        nowSecs: Long,
    ): Result {
        val event = try {
            KengeleMessage.open(datagram, ownPriv, senders, nowSecs)
        } catch (e: MessageException) {
            return Result.Ignored(null, e.message)
        } catch (e: IllegalArgumentException) {
            return Result.Ignored(null, e.message)
        }
        return try {
            Result.Counted(tally.handle(event, nowSecs))
        } catch (e: TallyException) {
            Result.Ignored(e.reason, e.message)
        }
    }
}
