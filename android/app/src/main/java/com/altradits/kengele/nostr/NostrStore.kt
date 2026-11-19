package com.altradits.kengele.nostr

import android.content.SharedPreferences
import com.altradits.kengele.message.nostr.RelayUrl

/**
 * The activist's Nostr relays: the addresses check-ins, duress check-ins
 * and panics publish to, plus how many of them must accept a gift wrap for
 * a send to count as sent. Kengele ships no default relays; the activist
 * and guardians choose them together (docs/nostr-transport.md).
 */
interface NostrStore {
    /** Normalized relay URLs, sorted; empty until configured. */
    fun relays(): List<String>

    /** Stores [urls] after normalizing; invalid addresses are dropped. */
    fun setRelays(urls: List<String>)

    /** How many relays must accept a gift wrap. Always at least 1. */
    fun minAccepted(): Int

    fun setMinAccepted(n: Int)
}

/** In-memory [NostrStore] for tests; the app uses encrypted storage. */
class InMemoryNostrStore : NostrStore {
    private var relays: List<String> = emptyList()
    private var minAccepted: Int = 1

    override fun relays(): List<String> = relays

    override fun setRelays(urls: List<String>) {
        relays = RelayUrl.normalizeAll(urls).sorted()
    }

    override fun minAccepted(): Int = minAccepted

    override fun setMinAccepted(n: Int) {
        minAccepted = n.coerceAtLeast(1)
    }
}

/** [NostrStore] backed by encrypted preferences. */
class EncryptedNostrStore(private val prefs: SharedPreferences) : NostrStore {
    override fun relays(): List<String> =
        (prefs.getStringSet(RELAYS, emptySet()) ?: emptySet()).sorted()

    override fun setRelays(urls: List<String>) {
        prefs.edit().putStringSet(RELAYS, RelayUrl.normalizeAll(urls).toSet()).apply()
    }

    override fun minAccepted(): Int = prefs.getInt(MIN_ACCEPTED, 1).coerceAtLeast(1)

    override fun setMinAccepted(n: Int) {
        prefs.edit().putInt(MIN_ACCEPTED, n.coerceAtLeast(1)).apply()
    }

    companion object {
        private const val RELAYS = "nostr_relays"
        private const val MIN_ACCEPTED = "nostr_min_accepted"
    }
}
