package com.altradits.kengele.guardian.nostr

import com.altradits.kengele.guardian.confirm.IgnoreReason
import com.altradits.kengele.guardian.confirm.Outcome
import com.altradits.kengele.guardian.confirm.Tally
import com.altradits.kengele.guardian.confirm.TallyException

/**
 * Feeds live Nostr deliveries into the tally, exactly like the paste path:
 * each opened event counts once; anything the tally ignores is reported, not
 * thrown. Android-free so plain JUnit covers it.
 */
object NostrIntake {
    sealed interface Result {
        /** The event counted; [outcome] says what the app must do. */
        data class Counted(val outcome: Outcome) : Result

        /** The tally ignored the event; show it, do not crash. */
        data class Ignored(val reason: IgnoreReason, val message: String?) : Result
    }

    fun intake(delivery: NostrTransport.Delivery, tally: Tally, nowSecs: Long): Result {
        return try {
            Result.Counted(tally.handle(delivery.event, nowSecs))
        } catch (e: TallyException) {
            Result.Ignored(e.reason, e.message)
        }
    }
}
