# Off-grid communications

How a Kengele alarm moves when the internet is down: which bearers we
use, which we reject, and how the 228-byte sealed message rides them.
This is the parent doc for the offline layers; the details live in
[message-format](message-format.md),
[mesh-bitchat](mesh-bitchat.md),
[sms-transport](sms-transport.md),
[meshtastic-exit](meshtastic-exit.md) and
[shutdown-mode](shutdown-mode.md).

> **No bearer here promises delivery.** A destroyed or isolated phone
> with no peers nearby sends nothing: no mesh, no SMS, no QR, no radio
> can carry what has no path. Every layer below fails silent, and the
> guardians' clocks, not the network, raise the alarm. Never rely on
> Kengele to protect a real person until the [threat
> model](threat-model.md) review and an independent security review are
> done.

Related code: `internal/meshcarry` (blind-carry core),
`cmd/kengele-meshlink` (runnable 3-node demo),
`internal/transport/bitchat` (planning stub the core supersedes for
K-to-K carriage; kept until the Android transport lands, then rewired).

## 1. Purpose and scope

**What ships now (no hardware, no phones, no humans needed):**

- `internal/meshcarry`: the BLE-transport-agnostic opaque carry core.
  Split, blind relay, reassembly, an in-memory multi-hop simulator.
- `cmd/kengele-meshlink demo`: a runnable A→B→C chain the user can run
  today; the middle node logs hop control only, proving blindness.
- This doc: the bearer decisions, the Android plan, the honest limits.

**What is field work (needs maintainers, hardware, volunteers):**

- The bitchat maintainer conversation (relay-but-don't-display payload
  type) and everything blocked on it.
- The Android BLE transport (section 4).
- All drills in section 8, on real phones, with made-up data only.
- LoRa gateway hardware, SMS cost surveys, satellite uplink contracts.

**Rules every bearer obeys:** recipients are guardians only, never
authorities (A1 state agents may be abductors: no authority routing
exists anywhere). Carriers are consenting app users and
cryptographically blind: sealed opaque 228-byte payloads end to end,
the relay path never decrypts and never branches on kind, body or
sender, and there is no non-consensual relay beyond the consenting
app's own documented crowd relay.

## 2. Bearer matrix

Verdicts: **ADOPT** (use it), **REJECT** (do not use it, with reason),
**TRACK** (revisit if evidence changes). Ranges are typical
crowd/protest conditions, not lab maxima.

### Short range: Bluetooth, Wi-Fi, crowd apps

| Bearer | Range | 228 B payload fit | Background constraints | Verdict |
|---|---|---|---|---|
| BLE legacy advertising | ~10–30 m | No: 31 B per packet, needs 8+ lossy packets plus reassembly | Background advertising throttled; fingerprintable | **REJECT** as carrier: too small, lossy, linkable |
| BLE extended advertising | ~30–100 m | Yes: chained packets fit 228 B | Fragmented handset support; background limits; fingerprintable | **REJECT** as carrier: handset share too low, GATT preferred |
| BLE GATT, Kengele-to-Kengele | ~10–30 m/hop | Yes: 2 × 150 B fragments | Foreground service + live connections; Doze duty-cycle | **ADOPT** as fallback if the crowd answer is no |
| BLE L2CAP CoC | ~10–30 m | Yes, larger MTU | Android-to-Android interop uneven | **TRACK**: candidate for the 2-phone K-to-K PoC |
| Bluetooth Classic | ~10 m | Yes | Pairing UX, power cost, no crowd effect | **REJECT** crowd flood: pairing per peer does not scale |
| Wi-Fi Direct | ~50–200 m | Yes | Group-owner negotiation is slow, kills other Wi-Fi, background-hostile | **REJECT** as primary: too slow and disruptive in a crowd |
| Wi-Fi Aware | tens of metres | Yes | Unknown handset share in Kenya; needs `NEARBY_WIFI` | **TRACK**: free ride only if bitchat relays the same packets |
| Nearby Connections | ~10–100 m | Yes | Requires Google Play Services | **REJECT**: GMS dependency breaks the F-Droid story (#17) |
| bitchat crowd (stock app relays) | Hop ~10–30 m, crowd-scale via relay | Yes: 228 B = 2 fragments | Foreground service on our side; crowd density decides | **ADOPT** as primary crowd bearer (needs maintainer payload type; K-to-K GATT fallback) |
| Bridgefy SDKs | Hop ~10–100 m | Yes | Proprietary SDK, past security findings, licensing | **REJECT**: unaudited proprietary relay dependency |
| Briar (as carrier) | Via Tor / local | Yes, but needs Briar contacts added in person | Needs the Briar app and its own trust setup | **REJECT** as carrier: adds nothing over our own GATT; respected as prior art |

### Long range and sneakernet

| Bearer | Range | 228 B / 304-char fit | Background constraints | Verdict |
|---|---|---|---|---|
| LoRa / Meshtastic exit | km-scale | Gift wrap ~2 KB ≈ 10 × 200 B fragments | Needs pre-equipped node + paired app + exit in range | **ADOPT** for pre-equipped routes only |
| SMS (per-guardian opt-in) | Anywhere with signal | 304 chars = one 2-segment concatenated SMS | `SmsManager` from background; telco sees both numbers | **ADOPT** best no-internet long-range bearer when SMS survives |
| USSD victim path | Session-only | No store-and-forward to guardians; telco-visible | Sessions die with the call | **REJECT** victim path: cannot reach guardians |
| Voice data over a call | Call range | No handset API; fragile modems; telco hears all | None usable | **REJECT**: fragile, no API, fully overheard |
| FM/RDS victim path | Broadcast-wide | Broadcast-only; no uplink from the victim | Needs a transmitter | **REJECT** victim path: the victim cannot transmit |
| QR / human courier | Anywhere a human walks | 304 chars fit one QR | No background concept: a person carries it | **ADOPT** cheapest total-blackout bearer; v0.1 drill bearer |
| Satellite (org exit uplink) | Global | Exit-side only: exit publishes to Nostr over it | Cost, availability, retention vary | **ADOPT** as organisation exit uplink only, never on the victim phone |
| Missed-call 1-bit ping | Anywhere with signal | 1 pre-agreed bit; telco sees both numbers | None | **TRACK** as last-resort pre-agreed ping |

Also rejected without a row: **FindMy-relay abuse** (smuggling bytes
through Apple's Find My network): non-consensual, terms-violating, and
unreliable. Kengele relays only through consenting app users.

## 3. How the 228 bytes ride

One rule covers every bearer: the wire message travels **unchanged and
opaque**. Seal once per guardian (`message.Seal`), carry the bytes,
open on receipt (`message.Open`). No re-encryption, no Kengele header
on the relay path, no branching on kind, body or sender. The carrier
sees 228 fixed-size bytes; a panic and a check-in look identical.

- **Blind relay.** Fragment headers carry hop control only (TTL, part
  numbers, random message id). Relays decrement TTL, deduplicate by
  packet id and content hash, and forward. `internal/meshcarry` encodes
  this; its relay path cannot import the decryptor.
- **Recognition by shape plus open.** A 228-byte (or 304-character)
  payload with a known format version is a *candidate*; only
  `message.Open` against a watched sender key confirms it. Mesh sender
  ids are never trusted; the signature says who sent it.
- **Duplicates, replays, order.** Every copy carries the same signed
  `(signer, seq)` and original `created_at`. The watcher keeps a
  seen-set and drops repeats; order does not matter, and a mesh
  delivery counts at signed time, not arrival time.
- **Route tagging.** Mesh deliveries are tagged `transport.Mesh`
  (already reserved in `internal/transport`), so the watcher can tell
  mesh carriage apart from Nostr or SMS.
- **Mesh arrival proves nothing about any network.** Like SMS during
  an internet shutdown, a mesh delivery must not release the
  mass-silence hold for activists on other routes. Per-route
  pause/resume follows [shutdown-mode](shutdown-mode.md): an activist's
  clock pauses only when every route they use is down.

## 4. Android BLE integration plan

No Android BLE code exists yet; this is the build order once the
maintainer conversation (step 1) concludes. All user strings ship in
English and Swahili together.

**Permissions (API 31+):** `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`,
`BLUETOOTH_CONNECT` (all runtime); add `NEARBY_WIFI` only if the
Wi-Fi Aware free ride (section 2) ever ships. No location permission
beyond what the OS forces for scanning; no GPS use, by design.

**Background survival:** a foreground service of
`connectedDevice` type while a watch window is active; a 180-second
backstop that re-arms scanning after the OS kills the service; and a
Doze-aware duty cycle (scan bursts, then sleep) so the phone lasts a
protest. Exact duty cycles come from the battery drill (section 8).

**GATT dual role, bitchat model:** the app advertises *and* scans,
connects both ways, and gossips packets it has not seen before with
TTL-1, up to 7 hops; broadcasts drop to TTL 5 once the phone holds 6
or more live links. Fragments are 150 bytes on the air, so the 228-byte
datagram always travels as 2 fragments (150 + 78 on bitchat framing;
9-byte header + 141/87-byte chunks in `internal/meshcarry` terms).
Dedup is by packet id on the wire plus a sha256 seen-set of completed
datagrams. The carry queue is memory-only.

**Steps:**

1. **Maintainer answer (humans).** Send
   [the draft](mesh-bitchat-maintainer-draft.md); agree the
   relay-but-don't-display payload type or tag (option 1 in
   [mesh-bitchat.md](mesh-bitchat.md)). Record the answer there before
   writing BLE code. This step blocks crowd interop.
2. **Two-phone carry (developer + 2 Android phones).** Phone A
   broadcasts 228 bytes; phone B receives and opens them. L2CAP CoC
   may replace GATT here if it proves more reliable phone-to-phone;
   keep the comparison honest and pick the one that delivers.
3. **Crowd relay (step 1 agreed + 3 or more phones).** Stock bitchat
   phones between A and B relay; Kengele packets stay undisplayed on
   their screens.
4. **Drills.** Measure range, hops, latency and delivery rate in a
   crowd; feed issue #18. Made-up data only.

**K-to-K fallback:** if the maintainers say no, our app speaks the
packet format to nearby Kengele phones without involving the bitchat
app. Same bytes, same blindness, no crowd effect. `meshcarry` already
is this layer's core; only the radio glue is missing.

**L2CAP / Aware notes:** L2CAP stays a 2-phone experiment until an
Android-to-Android interop survey says otherwise. Wi-Fi Aware ships
only as a free ride if bitchat relays the identical packets; Kengele
writes no Aware transport of its own.

**Strings this layer adds** (besides the three in
[mesh-bitchat.md](mesh-bitchat.md)):

| English | Swahili |
|---|---|
| `Relaying blindly` | `Inasambaza bila kusoma` |
| `No path — silence fails safe` | `Hakuna njia — kimya ni salama` |

## 5. QR sneakernet, SMS, LoRa exit and satellite uplink

Each bearer below covers a gap the others cannot.

- **QR / human courier (total blackout, cheapest).** The 304-character
  text form fits one QR code. The activist's phone shows it
  (`kengele-meshlink qr` prints the demo form plus `Show this QR /
  Onyesha QR hii`); any consenting person carries it to a guardian
  or exit phone, which scans and opens it. Cost: a screen and a willing
  walker. v0.1 drill bearer.
- **SMS (internet down, phone network up).** One 2-segment
  concatenated SMS per guardian, per the
  [SMS spec](sms-transport.md), only to guardians the activist opted
  in, knowing the telco sees both numbers. Best long-range no-internet
  bearer when SMS survives; useless the moment texts stop.
- **LoRa / Meshtastic exit (pre-equipped routes).** An organisation's
  exit radio hears fragments and publishes to Nostr over whatever link
  it still has (see [meshtastic-exit](meshtastic-exit.md)). Only for
  routes equipped *before* the blackout: a node, a paired app and an
  exit in range. Never improvised mid-crisis.
- **Satellite uplink (organisation exits only).** A D2C/NTN link or a
  dish at the exit site carries exit-to-Nostr traffic when nothing
  else does. It never sits on the victim phone: cost, availability and
  retention vary, and the phone cannot rely on sky access in custody.

## 6. Honest limits

- **No path, no message.** A destroyed phone, a phone switched off, or
  a phone with no mesh peers, no signal and no courier nearby sends
  nothing. The backstop is the network-return alarm: the first minute
  anything works, the alarm fires with proof of timing.
- **Crowd density decides.** Sparse crowds relay nothing; TTL 7 bounds
  even dense ones. Drills must measure the density where delivery
  collapses, not the density where it shines.
- **Doze, battery, seizure.** Background scanning costs battery; a
  dead phone is an isolated phone. Carry queues stay memory-only so a
  seized phone (A2) yields no queue from disk, which also means a
  reboot drops everything in flight.
- **Metadata survives encryption.** Nearby radios see that *something*
  was broadcast, when, how big (always 228 bytes: a fingerprint), and
  short-lived peer ids. Regular check-in rhythm is linkable across
  protests until cover traffic or jitter says otherwise (open
  question 4). SMS additionally tells the telco exactly which numbers
  texted which.
- **Relays can drop.** A hostile or broken relay drops or delays
  packets; it can never forge or read them. Dropping everything equals
  no mesh: silence fails safe.

## 7. Threat-model mapping

Recipients are guardians only, never authorities: A1 state agents may
be abductors, so no bearer routes to, through, or at the request of
any authority.

| Threat ([threat-model](threat-model.md)) | How this layer responds | What remains |
|---|---|---|
| A6: total blackout | Crowd relay plus courier carry; alarm fires when any carrier reaches a network | Nothing moves with no peers nearby; network-return alarm is the backstop (residual risk 2) |
| A5: nearby observers log traffic | Content encrypted, fixed size, kind hidden; blind relay | Broadcast timing, size fingerprint and peer ids are visible; rhythm linkability is open |
| A6: forged or altered alarm | Signature plus NIP-44 MAC; strict decoding rejects wrong bytes | None found at this layer |
| A5/A8: replay or duplicate | `(signer, seq)` seen-set in the watcher | None found; restarts are covered by the seq check |
| A2: phone seized | Carry queue in memory only | Stored app data is the app's business; a reboot drops in-flight carries |
| A1: guardian identities | No names, numbers or keys on the wire beyond short mesh peer ids | Peer-id correlation across protests is **confirm** with the bitchat source |
| A4: compromised guardian | Quorum untouched: 2-of-3 confirmation still decides Level 2 | A bearer cannot fix a majority-compromised quorum (residual risk 6) |
| A9: hostile mesh-app update | Our bytes stay signed and encrypted; a hostile relay can drop or delay, never forge or read | Dropping everything equals no mesh: silence fails safe |

## 8. Drills (#18)

Every drill follows [drills.md](drills.md): made-up data only (drill
identities use the surname **Mfano**), `DRILL:`-marked traffic on test
channels, anyone can stop at any time, and every drill ends with the
all-clear scenario.

1. **2-phone carry.** Two test phones, made-up check-ins: A broadcasts,
   B receives and opens. Pass bar: 10 of 10 opens, range and latency
   recorded.
2. **3-phone crowd.** A stock bitchat phone between A and B relays
   without displaying. Pass bar: B still opens; the middle screen
   shows no Kengele content (blindness check).
3. **QR scan rate.** One phone shows the 304-char QR; others scan at
   arm's length, in sunlight and low light. Pass bar: scan success
   rate and time recorded; failures analysed, not hidden.
4. **SMS cross-border.** Test SMS to a guardian SIM on another
   network (still test data, still `DRILL:`-marked where the channel
   allows). Pass bar: delivery time per route recorded; costs logged.
5. **Battery.** 6-hour protest-day run with advertising plus relaying.
   Pass bar: battery cost recorded; duty cycle adjusted to survive it.

## 9. Open questions

Carried over from the research; each needs its named evidence before
the layer it blocks ships.

1. **Maintainer answer:** relay-but-don't-display payload type/tag
   (humans send the draft; blocks crowd-relay interop code).
2. **bitchat-android source confirm:** MESSAGE/FRAGMENT values,
   signature coverage, decode caps, peer-ID rotation and lifetime,
   ANNOUNCE/LEAVE non-caching, TTL-5-at-6-links rule.
3. **Cache windows:** sender outbox plus store-and-forward TTL versus
   a day-long shutdown.
4. **Radio fingerprint:** fixed 228 B plus 90-minute rhythm
   linkability, and the cover-traffic/jitter decision.
5. **Battery and permissions:** 6-hour protest cost plus the F-Droid
   prompt burden (#17, #18).
6. **L2CAP vs GATT interop** and **Wi-Fi Aware handset share** plus
   identical-packet relay behaviour.
7. **Kenya radio rules:** 868 MHz power, duty cycle and type approval;
   gateway-shim protocol; phone-side LoRa TX path (BLE vs USB).
8. **SMS/QR/satellite numbers:** SMS costs, caps and throttling
   (KE/UG/TZ); QR ECC/version choices; satellite D2C/NTN
   availability, pricing and retention; USSD shortcode only if an SOS
   centre is ever pursued.

## Sources

- Protocol research and bitchat sources:
  [mesh-bitchat.md](mesh-bitchat.md) (Sources section).
- [message-format.md](message-format.md): the 228-byte wire message
  and 304-character text form.
- [sms-transport.md](sms-transport.md): the 2-segment SMS fallback and
  its metadata cost.
- [meshtastic-exit.md](meshtastic-exit.md): the LoRa exit point, radio
  settings and Kenya legal notes.
- [threat-model.md](threat-model.md): adversaries A1–A9 and residual
  risks; [shutdown-mode.md](shutdown-mode.md): pauses, holds and the
  written plan for no signal at all.
- [drills.md](drills.md): drill rules, consent and the results template.
