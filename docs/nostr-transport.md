# Nostr transport

How Kengele messages ([message format](message-format.md)) travel over
Nostr relays without a Kengele server. Code: `internal/transport/nostr`.

Status: design stage. Not for real use until the [threat model](threat-model.md)
review and an independent security review are done.

## What goes on a relay

Each wire message is wrapped three times, following
[NIP-17](https://github.com/nostr-protocol/nips/blob/master/17.md) and
[NIP-59](https://github.com/nostr-protocol/nips/blob/master/59.md):

| Layer | Kind | Signed by | Encrypted to | `created_at` |
|---|---|---|---|---|
| Rumor | 14 | nobody (unsigned) | — | real time |
| Seal | 13 | the sender's key | recipient (NIP-44) | real time minus a random 0–48 h |
| Gift wrap | 1059 | a new one-time key | recipient (NIP-44) | real time minus a random 0–48 h |

The rumor's content is the 304-character text form of the Kengele message,
which is itself signed and NIP-44 encrypted to the guardian. So a guardian
checks two signatures: the seal (Nostr) and the Kengele event inside it.

Unwrapping rejects a gift wrap if any signature fails, a kind is wrong, the
rumor's author is not the seal's signer (NIP-17's rule against impersonation),
it is not addressed to us, or the content is not a well-formed Kengele message.
Receive then drops anything whose sealer is not in the guardian's list of
people they watch, and anything that `message.Open` rejects.

## Sending

- `Send` wraps the message and publishes the same gift wrap to every
  configured relay (plus the recipient's inbox relays, if known), in parallel.
- Only an explicit `OK true` from a relay counts as accepted. A timeout or a
  dropped connection does not.
- If at least `MinAccepted` relays accept (default 1), it is sent. Otherwise
  the wrap goes to the **outbox** and `Send` reports `Queued`. The message is
  not lost.
- `Run` retries the outbox: right away when something is queued, then with
  backoff from `RetryMin` to `RetryMax`. A retry republishes the **same** gift
  wrap, and only to relays that have not accepted it yet.

## Receiving

- `Receive` subscribes on every configured relay for kind 1059 events tagged
  with the guardian's key, reconnecting with backoff when a relay drops.
- Because gift wraps are backdated up to 48 hours, every subscription looks
  back that far from the last time it was connected.
- Each message is delivered **once**, however many relays carry it and even
  if the sender re-wrapped it: the transport remembers recent gift-wrap IDs
  and recent message hashes (default 10,000).
- Replay protection across restarts belongs to the watcher (#7), which keeps
  the highest sequence number seen per sender.

## Connectivity signal

`Config.Connectivity` receives `Status{Reachable, Total, At}` after every
publish round and whenever a receive connection opens or drops. The watcher
(#7) uses it to tell "the activist went quiet" apart from "we can't reach any
relay" (shutdown mode). The interface lives here so this package does not
depend on the watcher.

## Inbox relays (kind 10050)

`InboxRelayList` builds a guardian's signed kind 10050 list, `ParseInboxRelays`
checks one, and `LookupInboxRelays` fetches the newest one from the configured
relays. `SetInboxRelays` adds them to where that guardian's messages are sent.

Publishing a kind 10050 list is public: it links the guardian's Kengele key to
those relays. Guardians should use a key made only for Kengele, never their
everyday Nostr identity.

## Relay selection

Kengele ships **no default relays**. The activist and guardians choose them
together. Guidance, pending the threat-model review:

- Use at least three relays run by different operators in different
  countries, ideally outside the country whose government is the threat.
- Prefer relays that accept kind 1059 from unknown keys and keep events for at
  least a few days, so messages sent during an outage are still there later.
- Relays reachable over Tor help when a relay's address is blocked.
- Nobody, including Altradits, needs to run a relay for Kengele to work.

## What a relay or observer can still see

- **The recipient's key** (the `p` tag): needed for delivery. Using a
  Kengele-only key keeps it from linking to a public identity.
- **That a gift wrap was published, its size and when it arrived** at the
  relay. The `created_at` field is randomised, but the relay's own receive
  time is not. During a watch window, a relay could see a regular rhythm of
  wraps to one key.
- **The IP address** of whoever publishes or subscribes, unless they use Tor
  or a VPN.
- **How many gift wraps** a guardian receives: one per event per relay.

A relay **cannot** see who sent it, the real time inside, the event kind
(panic and check-in are the same size) or any content.

## Implementation notes

- The relay client in `client.go` is a small NIP-01 client on
  `github.com/coder/websocket`. go-nostr's `Relay` is not used: in v0.52.3 its
  `Close` races with its own goroutines (seen with `go test -race`), and its
  `Publish` returns success if the connection drops before the relay answers.
  go-nostr is still used for events, signatures, NIP-44 and envelopes.
- Timestamp jitter uses `crypto/rand`, not `math/rand`.
- Nothing in this package logs, and relay notices are ignored.

## Open questions

1. **Persistent outbox.** The default outbox is in memory, so a phone restart
   loses queued messages. It should be stored encrypted on the device; how
   that fits the "nothing to find on a seized phone" rule needs the threat
   model's input.
2. **Publishing after success.** Once `MinAccepted` relays accept, the others
   are not retried. For alarms it may be better to keep trying all relays.
3. **Timing rhythm.** Should check-ins be sent with a random delay, or should
   cover traffic be added, to hide the watch-window rhythm from relays?
4. **Relay auth (NIP-42).** Some inbox relays only serve kind 1059 to the
   recipient after authentication. Not supported yet.
5. **Dedup across restarts.** Dedup is in memory; after a restart repeats are
   possible and are left to the watcher's sequence check.
