# Bluetooth mesh via bitchat (research v0.1)

How a Kengele message rides the bitchat Bluetooth mesh when the internet
and SMS are both down. Kengele builds **no new mesh**: nearby phones
already running bitchat relay our packets, and any phone that later
reaches a network delivers them.

Status: **research for review.** No Android or BLE code yet; the Go side
is a plan plus an in-memory carry stub (`internal/transport/bitchat`).
Not for real use until the [threat model](threat-model.md) review and an
independent security review are done.

Related: [message-format.md](message-format.md) (the 228-byte wire
message this layer carries), [shutdown-mode.md](shutdown-mode.md) (layer
3 of the offline plan), [sms-transport.md](sms-transport.md) (the
fallback below this one), [prior-art.md](prior-art.md) (why bitchat).

## Why bitchat

bitchat is an open-source, serverless Bluetooth mesh chat app already
used at protests (for example in Nepal). It has no check-ins, no alarm
and no escalation, which is exactly the gap Kengele fills. At a protest
the activist is surrounded by phones; if those phones relay a 228-byte
packet, one of them will carry the alarm out. The threat model counts
this as the answer to total blackouts (A6):
[threat-model.md](threat-model.md), mitigations table, #20.

bitchat itself is **out of scope** for our security claims: we depend on
it and say so ([threat-model.md](threat-model.md), section 1). Its
releases state it has had no external security review. Kengele treats
the mesh as an untrusted carrier: our own signature and encryption
already assume the carrier reads, stores and replays everything.

## What bitchat looks like on the wire

Researched November 2026 from the bitchat-android project, the
`bitchat-protocol` package notes and independent protocol write-ups
(sources at the end). Values marked **confirm** came from secondary
sources and must be checked against the bitchat-android source before
any interop code is written.

### The packet envelope

Every hop carries a `BitchatPacket`: a small header, short peer IDs, a
variable payload and an optional signature.

| Field | Size | Notes |
|---|---|---|
| `version` | 1 byte | `1` for v1; `2` extends the length field for files |
| `type` | 1 byte | Packet kind (below) |
| `ttl` | 1 byte | Hops left; starts at 7 |
| `timestamp` | 8 bytes | Milliseconds since epoch, big-endian |
| `flags` | 1 byte | `HAS_RECIPIENT`, `HAS_SIGNATURE` |
| `payloadLength` | 2 bytes | Big-endian; v1 payloads are at most 64 KiB |
| `senderID` | 8 bytes | Binary peer ID |
| `recipientID` | 8 bytes, if flagged | Target peer ID, or broadcast |
| `payload` | variable | Chat text, TLV data, fragments |
| `signature` | 64 bytes, if flagged | **Confirm** exact coverage per type |

### Payload types

| Value | Type | Meaning |
|---|---|---|
| `0x01` | `ANNOUNCE` | Peer discovery and nickname broadcast |
| **confirm** | `MESSAGE` | Chat text, broadcast orNoise-encrypted private |
| **confirm** | `LEAVE` | Peer disconnect notice |
| **confirm** | `FRAGMENT_START` / `CONTINUE` / `END` | Large message fragments |
| `0x22` | `FILE_TRANSFER` | TLV voice notes and images (v2 length) |

Private messages are encrypted between the two peers (Noise); relays
forward ciphertext they cannot read. File payloads use TLV encoding.

### Size limits

- **Packet payload (v1):** at most 64 KiB (2-byte length). A Kengele
  wire message is 228 bytes: it fits with room to spare.
- **BLE fragments:** large messages are split for Bluetooth LE; the
  cross-platform fragment size is **150 bytes** (bitchat-android 0.7
  release notes). A Kengele message needs 2 fragments (150 + 78).
- **Decode caps** exist per type in at least one port (for example
  MESSAGE 128 KiB); the Android caps are **confirm**.

### Store-and-forward behavior

- **Relay:** every phone re-broadcasts packets it has not seen before
  (deduplicated by packet ID) with `ttl - 1`, up to 7 hops. Broadcasts
  are cut to TTL 5 once a phone holds 6 or more live links
  (**confirm** against source).
- **Store and forward:** a sender-side outbox plus a store-and-forward
  manager cache messages for offline peers and deliver them when the
  peer reconnects. `ANNOUNCE` and `LEAVE` are deliberately **not**
  cached: presence news goes stale.
- **Courier:** any phone that overhears a packet carries it as it moves,
  so a message can travel across a crowd with no live end-to-end path.
- **Kengele fit:** our messages are delay-tolerant by design (signed
  original timestamp, `(signer, seq)` dedup in the watcher), so late
  mesh delivery is a normal case, not an error. See
  [message-format.md](message-format.md), "Replays".

## How Kengele messages ride it

The 228-byte wire message travels **unchanged and opaque**: seal once
per guardian (`message.Seal`), carry the bytes, open on receipt
(`message.Open`). No re-encryption, no Kengele header, no new mesh.

What is still open is **which bitchat payload type** carries those
bytes. Sending them as ordinary chat text would show gibberish on
strangers' bitchat screens, which is rude and leaks that something odd
is moving. The options, in order of preference:

1. **A dedicated type or app tag for Kengele** (for example a registered
   `MessageType` value or TLV app marker) that bitchat clients **relay
   but never display**. Needs maintainer agreement.
2. **A Noise-encrypted private message** to a known nearby Kengele peer.
   Works with no protocol change but only phone-to-phone, not crowd
   relay.
3. **Kengele-to-Kengele only:** our app speaks the bitchat packet format
   to nearby Kengele phones without involving the bitchat app. Fallback
   if the maintainers say no; loses the crowd effect.

Until the maintainers answer, the stub implements option 3 at the
planning level only: raw wire bytes in, raw wire bytes out, tagged
`transport.Mesh`. No BLE, no bitchat dependency, no packets built.

### Recognising a Kengele packet

Same rule as SMS ([sms-transport.md](sms-transport.md)): by **shape**
(228 bytes) plus the fact that it **opens** with a watched sender's
key. The mesh sender ID is never trusted; the signature says who sent
it. Non-Kengele traffic is ignored and relayed untouched per bitchat's
own rules.

### Duplicates, replays and order

Copies of one event arrive by mesh, Nostr and SMS in any order. Every
copy carries the same signed `(signer, seq)` and original `created_at`;
the watcher keeps a seen-set and drops repeats. Order does not matter:
a panic with seq 10 arriving after a check-in with seq 11 is still
acted on. A mesh delivery counts at **signed** time, not arrival time.

### Interaction with the watcher clock

- The mesh transport reports **its own** reachability (Bluetooth on/off,
  peers nearby), separately from Nostr and SMS.
- **A mesh arrival proves nothing about any network.** Like SMS during
  an internet shutdown, it must not release the mass-silence hold for
  activists on other routes. Deliveries are tagged `transport.Mesh`
  (already reserved in `internal/transport`) so the watcher can tell.
- Per-route pause/resume follows [shutdown-mode.md](shutdown-mode.md):
  an activist's clock pauses only when every route they use is down.
  Mesh joins that list once it ships; plans cannot enable it yet.

## Prototype plan

| Step | What | Needs |
|---|---|---|
| 0. Done here | This research, the carry stub, the maintainer draft | — |
| 1. Maintainer answer | Send the draft, agree a payload type (option 1) | Humans; TODO below |
| 2. Two-phone carry | Android PoC: Kengele phone A broadcasts 228 bytes, Kengele phone B receives and opens them | 2 Android phones, a developer |
| 3. Crowd relay | Same, with stock bitchat phones between A and B relaying | Step 1 agreed, 3+ phones |
| 4. Drills | Measure range, hops, latency, delivery rate in a crowd; feed #18 | Volunteers, made-up data only |

**Unresolved:** the maintainer conversation and the two-phone demo need
hardware and humans and are tracked as follow-ups, not done here.

TODO: send [the maintainer draft](mesh-bitchat-maintainer-draft.md)
after review; record the answer (payload type, relay behavior, anything
they ask us not to do) in this doc before writing BLE code.

## Planned user-facing strings

Needed once the Android work starts. English and Swahili together, per
[CONTRIBUTING.md](../CONTRIBUTING.md).

| English | Swahili |
|---|---|
| `Broadcast over Bluetooth` | `Tangaza kwa Bluetooth` |
| `Mesh: %d phones nearby` | `Mesh: simu %d karibu` |
| `Bluetooth is off. Turn it on to reach nearby phones.` | `Bluetooth imezima. Iwashe ili kufikia simu zilizo karibu.` |

## Review against the threat model

| Threat ([threat-model.md](threat-model.md)) | How this layer responds | What remains |
|---|---|---|
| A6: total blackout | Crowd relay plus courier carry; alarm fires when any carrier reaches a network | Nothing moves if no mesh phone is near; then the network-return alarm is the backstop (residual risk 2) |
| A5: nearby observers log BLE traffic | Content encrypted, fixed 228-byte size, kind hidden | Nearby radios see that *something* was broadcast and when; regular check-ins form a pattern |
| A6: forged or altered alarm | Signature plus NIP-44 MAC; strict decoding rejects wrong bytes | None found at this layer |
| A5/A8: replay or duplicate | `(signer, seq)` seen-set in the watcher | None found; restarts are covered by the seq check |
| A2: phone seized | Carry queue in memory only, like the Nostr outbox | bitchat's own stored messages on the phone are its business, not ours |
| A1: guardian identities | No names, numbers or keys on the wire beyond 8-byte mesh peer IDs | Peer IDs are short-lived mesh handles, but correlation across protests is **confirm** with the bitchat source |
| A9: bitchat ships a hostile update | Our bytes stay signed and encrypted; a hostile relay can drop or delay them, never forge or read them | Dropping everything is the same as no mesh: silence fails safe |

## Open questions for reviewers

1. **Payload type:** is option 1 (relay-but-don't-display) acceptable
   to the bitchat maintainers? What value or tag do they allocate?
2. **Peer-ID lifetime:** how long is an 8-byte sender ID stable, and can
   repeated protests be linked through it?
3. **Cache windows:** how long do offline-peer caches live? Long enough
   for a day-long shutdown?
4. **Radio fingerprint:** does a fixed 228-byte broadcast plus regular
   check-in rhythm identify Kengele users to a nearby scanner? Is cover
   traffic or jitter needed?
5. **Battery:** what does advertising plus relaying cost over a 6-hour
   protest? Drills (#18) must measure it.
6. **Permissions:** which Android Bluetooth permissions does the PoC
   need, and does that change the F-Droid-only story (#17)?

## Sources

- [bitchat-android (permissionlesstech)](https://github.com/permissionlesstech/bitchat-android)
- [Media transfers implementation guide, PR #440 (packet envelope, FILE_TRANSFER 0x22, v1/v2 lengths)](https://github.com/permissionlesstech/bitchat-android/pull/440)
- [bitchat-android 0.7 release notes (150-byte cross-platform fragments)](https://github.com/permissionlesstech/bitchat-android/releases/tag/0.7)
- [Mesh gossip, PR #381 (ANNOUNCE = 0x01)](https://github.com/permissionlesstech/bitchat-android/pull/381)
- [bitchat-protocol on PyPI (v1 header layout)](https://pypi.org/project/bitchat-protocol/0.1.0/)
- [Messaging after the internet: bitchat's mesh, Nostr fallback and offline security (dev.to)](https://dev.to/lina_atar_b33f8cb45364a91/messaging-after-the-internet-inside-bitchats-bluetooth-mesh-nostr-fallback-and-offline-security-bih)
- [bitchat mesh and LoRa (Glitchwire)](https://glitchwire.com/news/bitle-adds-lora-to-bitchats-solar-powered-mesh-network-enabling-multi-kilometer/)
- [Offline payments and messaging with bitchat (Bankless)](https://www.bankless.com/read/taking-digital-payments-offline)
