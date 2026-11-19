# Meshtastic exit point

How an organisation carries Kengele alarms out of a total blackout over LoRa
radio and onto Nostr. Code: `internal/meshexit`, `cmd/kengele-exit`.

> **Status: design stage. Not for real use.** (*Si kwa matumizi halisi.*) Do
> not rely on this to protect anyone until the [threat model](threat-model.md)
> review and an independent security review are done.

## What it is

When the internet and SMS are both down, an activist's phone cannot reach any
guardian directly. A **Meshtastic exit point** is a radio plus a small
computer, run by an organisation (a newsroom, a clinic, a church compound),
that hears Kengele alarms on the LoRa mesh and publishes them to Nostr relays
over whatever link the organisation still has: Starlink, a neighbouring
country's network, or the internet once it returns.

```
activist phone --LoRa--> exit radio --USB/TCP--> kengele-exit --internet--> Nostr relays --> guardians
```

The phone does all the cryptography **before** transmitting: each Kengele
event is sealed to each guardian ([message format](message-format.md)),
wrapped in a NIP-59 gift wrap ([Nostr transport](nostr-transport.md)), split
into radio-sized fragments and sent. The exit only reassembles and replays
bytes the phone already signed. It holds no private keys and cannot read,
forge or alter any alarm.

**Out of scope:** running exit nodes for others. Each organisation runs its
own. Altradits runs none, and this document does not ask anyone to carry
third-party traffic. An organisation that runs an exit carries alarms from
activists it has already agreed to protect, nothing else.

## How it works

1. **Fragment.** The phone splits one gift wrap (JSON, about 2 KB) into
   fragments of 200 bytes plus an 8-byte header (`KG`, version, part
   count, index, 24-bit message id). Each fragment fits one Meshtastic
   packet (237-byte payload limit).
2. **Reassemble.** The exit collects fragments for up to 10 minutes,
   ignoring other mesh traffic and duplicate repeats, then joins them.
3. **Validate.** The reassembled datagram must be a signed kind-1059 gift
   wrap addressed to someone. Anything else is dropped silently.
4. **Forward.** The exit publishes the gift wrap to every configured relay.
   Only an explicit `OK true` counts. If too few relays accept, the gift
   wrap goes to the **queue** (memory, or a file that survives restarts)
   and is retried every 30 seconds and after every later success. The file
   queue is rewritten atomically with mode 0600, so a crash never leaves a
   half-written file.
5. **Deduplicate.** Mesh repeats and phone resends are forwarded once: the
   exit remembers recent datagram hashes.

There are no radio-level acknowledgements in v0.1. A fragment lost on the
air means the phone must send the datagram again under a new message id.
(Gift wraps are idempotent, so resends are safe.)

The TCP and serial links to the gateway redial with exponential backoff if
they drop (500 ms to 30 s); the simulator does not. The exit keeps its
reassembler, dedup and queue across redials.

## Hardware

One exit point needs, per site:

| Part | Example | Notes |
|---|---|---|
| Meshtastic LoRa node | Heltec LoRa 32 V3, RAK WisBlock, LilyGO T-Beam or T-Echo | Any node the Meshtastic firmware supports, with a proper antenna for the band below. A second node as a spare. |
| Antenna | 868 MHz whip or small outdoor antenna | Never transmit without an antenna attached. Keep the gain modest; height matters more than power. |
| Host computer | Raspberry Pi 4/5 or a small laptop | Runs `kengele-exit` and the gateway shim. Full-disk encryption on. |
| USB cable | Data-capable USB cable | Powers the node and carries the serial link. |
| Power backup | UPS or a charged power bank | Blackouts often take the mains too. |
| Uplink | Starlink terminal (licensed in Kenya), a second ISP, or patience | Needed only to reach Nostr. Without any uplink the exit queues and waits. |

Indicative cost is a few hundred US dollars per site, most of it the
computer and power backup. Prices move; budget from current shops, not from
this document.

### Radio settings

- **Band: 868 MHz** (`EU_868` region preset in the Meshtastic firmware).
  Kenya is in ITU Region 1, where the 863–870 MHz short-range band is the
  usual LoRa home. Confirm against the current Communications Authority
  rules before transmitting (see below).
- **A dedicated channel** with a random pre-shared key, shared only with
  the organisation's own phones and exits — not the public default
  LongFast channel. The Kengele payload is already encrypted end to end;
  the channel key only keeps casual observers from seeing that Kengele
  fragments are on the air.
- **Modem preset:** MediumFast or faster. Slow long-range presets stretch
  a 2 KB gift wrap over many minutes of airtime; prefer short links with
  fast settings and more exit sites over one heroic link.
- **No MQTT uplink, no default telemetry** from the exit's node. The node
  is a radio front end, not a sensor.

## Setup

You need Go 1.22 or newer on the host.

```sh
# Build.
go build ./...

# Choose relays together with the activists and guardians you protect:
# at least three, run by different operators in different countries.
# (Made-up addresses below. Never commit real relay URLs for real people.)
./kengele-exit run \
  -mesh serial:/dev/ttyUSB0 \
  -relays wss://relay1.example.org,wss://relay2.example.net,wss://relay3.example.com \
  -queue /var/lib/kengele/exit-queue.json
```

Options:

- `-mesh sim` runs without a radio, for drills. `-mesh tcp://host:port`
  talks to the [gateway shim](#gateway-shim) over the network instead of USB.
- `-min-accepted 1` (default) is how many relays must acknowledge a gift
  wrap before it counts as sent. Raise it if your relays are flaky, not to
  lower it.
- `-tick 30s` is how often the queue is retried.
- `-reassemble-timeout 10m` is how long fragments wait for their siblings.
- TCP and serial links redial automatically if the gateway restarts or USB
  re-enumerates. The first dial still fails fast, so a typo'd address is a
  clear error instead of silent retries.

The exit logs one bilingual line per event (`forwarded to Nostr / imetumwa
kwa Nostr`, `flushed 2 to Nostr / 2 zimetumwa kwa Nostr kutoka foleni`,
`queued / imewekwa foleni`, `dropped invalid message / ujumbe batili
umetupwa`, `mesh link dropped, redialling / muunganisho wa mesh umekatika,
inaunganisha tena`, `mesh link restored / muunganisho wa mesh umerudi`).
Watch the `queued` lines: a queue that only grows means the uplink is gone
and someone should fix it, not restart the exit (a restart with `-queue`
keeps the queue; without `-queue` it is lost). The queue file is created
with mode 0600 and its parent directories with 0700; a corrupt file stops
the exit with an error instead of silently losing messages — back it up,
delete it, and restart (the phones still hold every alarm and resend).

### Gateway shim

`kengele-exit` does not speak the Meshtastic protocol itself. A tiny gateway
process on the host owns the USB or Wi-Fi link to the node and passes raw
packet bytes to the exit over the framing in `internal/meshexit/mesh.go`
(2-byte big-endian length + payload, same framing over TCP and serial). The
shim is deliberately small so an organisation can audit it: subscribe to the
Kengele channel, hand every packet to the exit, transmit what the exit sends
back. If the shim restarts, the exit redials it and keeps everything queued.
The shim ships separately; until it does, `-mesh sim` and the
simulator-vs-gateway cross-tests (`internal/meshexit/cross_test.go`, which
pin the exact bytes on the wire) are how this is exercised.

### Drills

Run the exit in drills before anything else depends on it (#18): one phone
sending gift wraps through the simulator or a real node, the exit's uplink
unplugged halfway, then plugged back. The drill passes when every alarm
sent during the outage reaches the relays within a minute of the uplink
returning, exactly once each, with zero false public alerts.

## Kenya radio legal notes

**This is not legal advice.** An organisation should have a Kenyan lawyer
(or its own country's lawyer outside Kenya) confirm all of this before it
transmits. The key questions:

- **Licence-exempt bands.** Kenya's Communications Authority (CA) publishes
  which frequency bands, power levels and duty cycles may be used without an
  individual licence. Short-range LoRa devices in the 868 MHz band are the
  intended fit, but the exact limits and any type-approval requirements
  change; check the current CA rules and buy nodes that already carry
  type approval where it is required.
- **Type approval.** Radio equipment used in Kenya generally needs CA type
  approval. Importing or operating unapproved transmitters can mean seizure
  or fines.
- **Emergencies.** During declared emergencies or security operations the
  state can restrict radio use as well as shut down networks. An exit that
  cannot legally transmit is just a queue: keep the queue file safe and
  deliver it when you lawfully can.
- **Organisation liability.** Publishing an alarm names a person and may
  accuse the authorities; the [threat model](threat-model.md#9-legal-risks-not-legal-advice)
  asks guardians to get legal input on alert wording before anything goes
  public. The same applies to running an exit: agree in writing who is
  responsible for it, who may access the host, and when it is switched off.
- **Outside Kenya.** Uganda and Tanzania templates and radio notes are out
  of scope for v0.1.

## Security notes

From the [threat model](threat-model.md):

- **The exit sees what a relay sees** (§3, adversary A5): recipient keys,
  gift-wrap sizes and arrival times — never content, sender identity or
  event kinds. Running it does not expose anything new about the activists
  beyond "someone near this radio uses Kengele", which the radio signal
  itself already reveals.
- **Total blackouts** (§5, "Organisation exit points", §6.2) are what the
  exit is for: it shortens the wait from "when the network returns" to "when
  any exit's uplink returns". It does not help if no exit hears the phone.
- **An insider at the organisation** (§3, A7) can read the queue file and
  see which guardians receive alarms. Limit host access, encrypt the disk,
  and store no names next to the keys.
- **Seizure.** The host holds gift wraps and relay URLs, not private keys.
  There is nothing on it that decrypts past or future alarms — but the
  queue shows who is being protected. Have a plan for switching the exit
  off and wiping the queue if the site is raided.
- **Publishing IP.** Relays see the exit's IP address. If the organisation
  must hide that it runs an exit, publish over Tor or a VPN.
- **SMS-style metadata does not apply**: LoRa reception reveals proximity,
  not phone numbers. Direction-finding a transmitter is still possible for
  a capable adversary; exits should sit where a radio mast is unremarkable.

## Fragment format reference

See `internal/meshexit/fragment.go` for the normative code. Summary: magic
`KG`, 1-byte version (`0x01`), 1-byte total, 1-byte index, 3-byte message
id, then 1–200 bytes of datagram. Max datagram 4096 bytes, max 64 partial
datagrams, 10-minute reassembly window. Non-Kengele mesh traffic is ignored,
never an error.

## Appendix: hardware acceptance

**Not yet done. No radio has touched this code.** (*Hakuna redio
iliyojaribiwa.*) Everything below is a checklist for a field volunteer with
hardware. Work it in order, tick each box, and stop at the first failure —
do not skip ahead. Anything marked **[must confirm]** depends on the
firmware version or the law on the day you transmit; check it then, not from
memory of this document.

You need a sender before you start: either the Kengele phone app with LoRa
transmit support, or a laptop that replays the test fragments from
`internal/meshexit/cross_test.go` through a second node. Without one of
those there is nothing to accept.

### A. Shopping checklist (per site)

- [ ] 2× Meshtastic LoRa nodes, 868 MHz variant: Heltec LoRa 32 V3, or a RAK
  WisBlock Meshtastic bundle (base board + matching core + LoRa radio — buy
  the bundle the vendor sells for Meshtastic, not loose parts). One is the
  exit's radio, one is the spare. **[must confirm]** board revision and
  antenna connector (usually SMA female on Heltec, IPEX/u.FL pigtail on some
  RAK cores — match the antenna to the connector).
- [ ] 2× 868 MHz antennas (one fitted, one spare). A whip is enough for the
  bench; a small outdoor antenna with coax suits a roof. **Never transmit
  without an antenna attached. / Kamwe usitume bila antena.**
- [ ] 2× data-capable USB cables matching the nodes (usually USB-C; carry a
  spare). Charge-only cables power the node but carry no data — if the host
  sees no serial device, suspect the cable first.
- [ ] 1× host computer: Raspberry Pi 4/5 with SD card and PSU, or a small
  laptop. Full-disk encryption on before it leaves the office.
- [ ] 1× power backup: UPS or a power bank rated for the host (a Pi 4 wants
  5 V / 3 A over USB-C **[must confirm]** against the Pi docs for your
  model).
- [ ] 1× uplink, or patience: licensed Starlink terminal, a second ISP, or
  nothing (the exit queues until the internet returns).

### B. Flash and configure the nodes

- [ ] Flash both nodes with the same Meshtastic firmware release for their
  exact board, using the Meshtastic web flasher or CLI. Note the firmware
  version in the site log. **[must confirm]** menu and button names against
  the flasher on the day.
- [ ] Set the LoRa region preset to `EU_868`. **[must confirm]** against the
  current Communications Authority rules before transmitting (see section E
  and [Kenya radio legal notes](#kenya-radio-legal-notes)).
- [ ] Create one dedicated channel with a fresh random pre-shared key, shared
  only with this organisation's phones and exits — never the public default
  LongFast channel. Generate it in the app, or with `openssl rand -hex 32`.
  Store the key with the site secrets; never commit it.
- [ ] Set the modem preset to MediumFast or faster. Slow long-range presets
  stretch a 2 KB gift wrap over many minutes of airtime.
- [ ] Turn off MQTT uplink and default telemetry on the exit's node. The node
  is a radio front end, not a sensor.
- [ ] Name the nodes `SITE-exit-1` and `SITE-spare-1` (replace SITE with the
  site code; the example names are placeholders). Confirm each node shows
  its antenna fitted, its channel key set, and MQTT off, then unplug.

### C. Set up the host

- [ ] Install Go 1.22 or newer, clone the repo, run `go build ./...`.
- [ ] Run the simulator suite once: `go test ./internal/meshexit/
  ./cmd/kengele-exit/`. Expect `ok` for both (relay subtests skip where TCP
  binds are forbidden; that is environmental).
- [ ] Choose at least three relays with the activists and guardians, run by
  different operators in different countries. Use placeholders such as
  `wss://relay1.example.org` in every written record; never commit real
  relay URLs for real people.
- [ ] Create the queue directory, e.g. `sudo mkdir -p /var/lib/kengele &&
  sudo chmod 700 /var/lib/kengele`, and give it to the exit user. The exit
  also creates missing parents with 0700 on first write.

### D. Acceptance procedure

Run on the bench first (both nodes on the desk, antennas on, a few metres
apart), then from the planned site at real distance.

- [ ] **1. Idle start.** Connect the exit node by USB, find its device
  (`ls /dev/ttyUSB* /dev/ttyACM*` on Linux, `/dev/tty.usb*` on macOS
  **[must confirm]** for your host), and start:
  `./kengele-exit run -mesh serial:/dev/ttyUSB0 -relays
  wss://relay1.example.org,wss://relay2.example.net,wss://relay3.example.com
  -queue /tmp/accept-queue.json`
  Expect the banner (`kengele-exit 0.0.0-dev: mesh serial:..., 3 relays ...`)
  and then silence: no packets, no log lines. `Ctrl-C` stops it.
- [ ] **2. One alarm through.** Start the exit as above. Send one check-in
  from the sender. Expect within seconds one line like
  `2026-…Z forwarded to Nostr / imetumwa kwa Nostr (a1b2c3d4)`, and the gift
  wrap visible exactly once on each relay (subscribe to the guardian's key
  with any Nostr client). Note the 8-hex id in the site log.
- [ ] **3. Mesh repeats.** With the sender's mesh-repeat enabled, send one
  alarm. Expect one `forwarded` line and one `duplicate ignored / marudio
  yamepuuzwa` line per repeat — still exactly one relay event per relay.
- [ ] **4. Outage queue.** Unplug the uplink (pull the cable, do not stop the
  exit). Send three alarms. Expect three `queued, Nostr unreachable /
  imewekwa foleni, Nostr haipatikani` lines, and `jq length
  /tmp/accept-queue.json` reporting `3`. `ls -l` shows the file mode `600`.
- [ ] **5. Recovery flush.** Plug the uplink back. Within one `-tick`
  (default 30 s) expect `flushed 3 to Nostr / 3 zimetumwa kwa Nostr kutoka
  foleni`, and each relay holding the three gift wraps exactly once each.
  `jq length /tmp/accept-queue.json` reports `0`.
- [ ] **6. Gateway drop.** While idle, unplug the node's USB for 10 seconds,
  then plug it back (same port). Expect `mesh link dropped, redialling /
  muunganisho wa mesh umekatika, inaunganisha tena`, then `mesh link
  restored / muunganisho wa mesh umerudi`, with no restart and no lost queue
  (`jq length` unchanged). Send one alarm: it forwards.
- [ ] **7. Restart survival.** With two alarms queued (uplink unplugged),
  stop the exit (`Ctrl-C`), start it again with the same `-queue` file.
  Expect `jq length` still `2`; plug the uplink back and expect `flushed 2`.
- [ ] **8. Invalid traffic.** From a second Meshtastic client on the same
  channel, send a plain text message (not a Kengele fragment). Expect no new
  exit line at all — non-Kengele traffic is ignored, never an error — and
  nothing on the relays.
- [ ] **9. Range check.** Move the sender to the farthest point it must
  cover. Send five alarms. Expect at least four to forward first try; any
  loss must be cured by the phone resending under a new id (resends are
  safe: gift wraps are idempotent). If losses persist, add an exit site
  rather than slowing the modem preset.

The drill passes when steps 2–9 all behave as above with zero false public
alerts. Record firmware versions, modem preset, relay count, and the note
"accepted on <date> by <initials>" in the site log.

### E. EU_868 Kenya notes (must confirm, not legal advice)

**This is not legal advice.** Have a Kenyan lawyer (or your own country's
lawyer outside Kenya) confirm every line before you transmit. The
[Kenya radio legal notes](#kenya-radio-legal-notes) are the binding section;
these are the practical notes for the 868 MHz bench:

- Kenya is in ITU Region 1, where the 863–870 MHz short-range band is the
  usual LoRa home — hence the `EU_868` preset. **[must confirm]** the exact
  sub-band, power limit, and duty cycle in the current Communications
  Authority rules; presets are not permission.
- Buy nodes that already carry CA type approval where it is required, and
  keep the approval paperwork with the site log. **[must confirm]** whether
  your import and your site need individual approval.
- Keep the gain modest and the antenna high; never raise power to fix a
  range failure in step 9 — add a site instead.
- During declared emergencies or security operations the state can restrict
  radio use as well as shut down networks. If the exit cannot lawfully
  transmit it is just a queue: keep the queue file safe and deliver it when
  you lawfully can. (*Kama huwezi kutuma kisheria, linda foleni.*)
