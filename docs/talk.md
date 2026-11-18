# Talk over Nostr: no company server in the middle

> There is no company server in the middle. Messages are encrypted and
> travel over Nostr, an open network that no single company runs. So
> nobody can switch Kengele off, and taking the activist's phone stops
> nothing.

## Why the claim holds

- **No default relays.** `internal/transport/nostr.New` refuses to start
  with no relays (`ErrNoRelays`); `cmd/kengele-watcher` live mode and
  `cmd/kengele-exit` both require `-relays`; `cmd/kengele-talk` below
  requires it too. The only `wss://` strings in non-test Go code are
  `wss://a,wss://b` usage placeholders. The activist and the guardians
  choose the relays together (see
  [Nostr transport](nostr-transport.md#relay-selection)).
- **Keys are user-held.** Every command reads keys from files the user
  made (`-key`, `-key-file`); nothing fetches, registers, or escrows a
  key anywhere. A world-readable key file earns a warning, not an upload.
- **Relays see almost nothing.** Each message is a NIP-17 gift wrap:
  signed inside, NIP-44 encrypted twice, backdated up to 48 h. A relay
  sees only the recipient's key, the size, and its own receive time —
  never the sender, the kind, or the content.
- **No other company endpoint in the message path.** The only other
  network defaults in the repo are the public OpenTimestamps calendars
  (`internal/ots.DefaultCalendars`), which timestamp court-evidence
  hashes, never carry messages, and are passed in explicitly by the
  caller (`ots.Open` accepts an empty list). Nothing else dials out:
  `fund`, `packet`, `confirm`, `schedule`, and `message` are offline.

## Try it: two terminals, real public relays

Build once. Keys below are throwaways made for the demo; never reuse
them, and never show a `.hex` file to anyone.

```sh
go build -o /tmp/kengele-talk ./cmd/kengele-talk

# Terminal 1 and 2, one key each:
kengele-talk keygen -out alice.hex   # prints alice's pubkey + fingerprint
kengele-talk keygen -out bob.hex     # prints bob's pubkey + fingerprint
```

Pick any relays run by different operators. If one rejects the message,
another accepts it — that choice is yours, not Kengele's:

```sh
RELAYS=wss://relay.damus.io,wss://nos.lol

# Terminal 2 (bob listens for alice; paste alice's pubkey):
kengele-talk listen -key bob.hex -relays $RELAYS -senders <alice-pubkey>

# Terminal 1 (alice sends; paste bob's pubkey):
kengele-talk send -key alice.hex -to <bob-pubkey> -relays $RELAYS \
  -note "tuko salama"
```

Bob's terminal prints the note with alice's fingerprint and the signed
time, e.g. `2026-11-18T19:33:34Z from 9996a500: tuko salama`. The note
travels inside an ordinary check-in event (max 86 bytes); no new message
kind was added for this demo.

Observed 2026-11-18 (throwaway keys, fingerprints only): send accepted
by 1 of 2 relays, and the listener printed both the probe and
`kengele talk proof 002` from `9996a500`. The first send round reached
0 of 2 relays and reported `queued for retry` — relay flakiness, not a
block: the retry round succeeded.

## Seizure proof: taking the phone stops nothing

The guardian side needs no message at all to raise the alarm. With zero
activist messages, the watcher fires `missed` on the first deadline:

```sh
go build -o /tmp/kengele-watcher ./cmd/kengele-watcher
export KENGELE_PASSPHRASE="a demo passphrase, 12+ characters"
kengele-watcher seal-plan -in plan.json -out plan.kgp
kengele-watcher run -plan plan.kgp -tick 5s   # no -relays: offline demo
```

Observed output (demo plan, activist silent): the banner
`watching 1, no transport yet (not for real use)` followed by
`... missed DEMO-A deadline ... (no check-in by ...)`. Silence is the
signal: seizing the activist's phone cannot stop the guardians' alarm.

Locked in by Go tests: `TestHookInvokedOnMissed` (a silent plan fires
exactly one `missed` and runs the alert hook once),
`TestWatchLoopGoingDark` (silence past the return window misses),
`TestWatchLoopNostrDown` (a paused activist never misses — only a live,
silent one does), plus the `internal/schedule` engine suite. Run:

```sh
go test ./cmd/kengele-watcher/ ./internal/schedule/
```
