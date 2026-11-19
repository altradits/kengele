package com.altradits.kengele.nostr

/**
 * Android-free seam: whether the activist app currently treats the
 * internet path (Nostr relays) as down. [com.altradits.kengele.sms.SmsSender]
 * reads this to decide whether ordinary check-ins go out by SMS for
 * guardians on the Shutdown-check-ins profile (docs/sms-transport.md,
 * section 1); the main screen reads it for the shutdown banner.
 */
interface ShutdownModeSource {
    val shutdownMode: Boolean
}

/**
 * One Nostr publish observation. [NostrCheckInSender] reports every gift
 * wrap it publishes — fresh sends and outbox retries alike — so the
 * shutdown signal watches the same Nostr reachability the outbox already
 * sees, with no new permissions and no active probing.
 */
fun interface NostrReachabilitySink {
    /**
     * Records one gift-wrap publish round. [reachable] is true when at
     * least one relay answered, whether it accepted the event or refused
     * it: a refusal still proves the internet path is up.
     */
    fun onPublish(reachable: Boolean)
}

/**
 * Debounced shutdown-mode signal (docs/shutdown-mode.md, principle 1).
 *
 * The activist side enters shutdown mode after [ENTER_FAILURES]
 * consecutive all-failed publishes, or once [ENTER_UNREACHABLE_SECS]
 * seconds pass with no relay reachable since the first consecutive
 * failure — whichever comes first. The time leg is checked lazily, so a
 * single failure followed by silence still enters shutdown without
 * waiting for another send. The first reachable relay exits at once:
 * ordinary check-ins stop going by SMS the moment Nostr works again.
 *
 * A Nostr round that contacts no relay (a retry whose relays all accepted
 * already, under a minAccepted above the relay count) reports nothing and
 * moves neither leg: below-quorum queueing is not evidence the network
 * is down.
 *
 * Thread-safe: [NostrCheckInSender] reports from its background threads
 * while activities read [shutdownMode] on the UI thread.
 */
class ShutdownModeMonitor(
    private val clockSecs: () -> Long = { System.currentTimeMillis() / 1000 },
    private val enterFailures: Int = ENTER_FAILURES,
    private val enterUnreachableSecs: Long = ENTER_UNREACHABLE_SECS,
) : ShutdownModeSource, NostrReachabilitySink {
    companion object {
        /** Consecutive all-failed publishes that enter shutdown mode. */
        const val ENTER_FAILURES = 3

        /** Seconds with no reachable relay that enter shutdown mode. */
        const val ENTER_UNREACHABLE_SECS = 300L
    }

    private val lock = Any()
    private var consecutiveFailures = 0
    private var streakStartSecs: Long? = null
    private var shutdown = false

    override val shutdownMode: Boolean
        get() = synchronized(lock) {
            maybeEnter(clockSecs())
            shutdown
        }

    override fun onPublish(reachable: Boolean) {
        synchronized(lock) {
            val now = clockSecs()
            if (reachable) {
                consecutiveFailures = 0
                streakStartSecs = null
                shutdown = false
                return
            }
            if (consecutiveFailures == 0) streakStartSecs = now
            consecutiveFailures++
            maybeEnter(now)
        }
    }

    private fun maybeEnter(now: Long) {
        if (shutdown) return
        if (consecutiveFailures >= enterFailures) {
            shutdown = true
            return
        }
        val start = streakStartSecs
        if (start != null && now - start >= enterUnreachableSecs) shutdown = true
    }
}

/**
 * Process-wide shutdown signal. The senders feed [shared] and the SMS
 * sender and the main screen read it; senders are built fresh per batch,
 * so only a shared monitor sees consecutive failures across batches.
 * Unit tests use their own [ShutdownModeMonitor] instances.
 */
object ShutdownModes {
    var shared: ShutdownModeMonitor = ShutdownModeMonitor()
}
