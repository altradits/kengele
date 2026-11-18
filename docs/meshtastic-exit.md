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
   and is retried every 30 seconds and after every later success.
5. **Deduplicate.** Mesh repeats and phone resends are forwarded once: the
   exit remembers recent datagram hashes.

There are no radio-level acknowledgements in v0.1. A fragment lost on the
air means the phone must send the datagram again under a new message id.
(Gift wraps are idempotent, so resends are safe.)

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

The exit logs one bilingual line per event (`forwarded to Nostr / imetumwa
kwa Nostr`, `queued / imewekwa foleni`, `dropped invalid message / ujumbe
batili umetupwa`). Watch the `queued` lines: a queue that only grows means
the uplink is gone and someone should fix it, not restart the exit (a
restart with `-queue` keeps the queue; without `-queue` it is lost).

### Gateway shim

`kengele-exit` does not speak the Meshtastic protocol itself. A tiny gateway
process on the host owns the USB or Wi-Fi link to the node and passes raw
packet bytes to the exit over the framing in `internal/meshexit/mesh.go`
(2-byte big-endian length + payload, same framing over TCP and serial). The
shim is deliberately small so an organisation can audit it: subscribe to the
Kengele channel, hand every packet to the exit, transmit what the exit sends
back. The shim ships separately; until it does, `-mesh sim` and the
simulator tests are how this is exercised.

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
