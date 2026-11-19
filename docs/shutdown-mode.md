# Shutdown mode

How Kengele behaves when the internet, the phone network, or both are switched off, often exactly when abductions happen.

The honest starting point: **during a shutdown, silence means nothing.** If everyone goes quiet at once, no app can tell an abduction from a dead network. Kengele treats shutdowns as their own mode, not as a weaker version of normal operation.

## Principle 1: the clock only runs while the network is up

When a guardian's device sees that its own connection is gone (all relays unreachable, or many activists going silent together), it switches to shutdown mode:

- Missed check-ins **pause** instead of raising the alarm, so a blackout does not trigger hundreds of public false alarms.
- When the network returns, the clock restarts. The activist has a short window (for example 2 hours) to check in by any route. Otherwise the alarm goes off **the moment the network returns**, not days later when someone finally notices.

### One network at a time

A shutdown rarely takes everything down at once. In Tanzania in 2025 the internet went dark while SMS mostly kept working. So the watcher tracks each route separately (Nostr and SMS today, the mesh later) instead of one "online" switch:

- **Each activist's plan lists the routes they use.** The default is Nostr only; SMS is off unless the activist turns it on ([sms-transport.md](sms-transport.md)). Plans written before this change read as Nostr only.
- **An activist's clock pauses only when every route they use is down.** If Nostr is down but SMS works, an activist with SMS enabled keeps a running clock and checks in by SMS. A Nostr-only activist pauses.
- **When a route returns, only the activists who use it resume**, each measured from the moment their own last route went down.
- **A message proves only the route it came by.** An SMS check-in during an internet shutdown shows that SMS works. It does not end the "many silent at once" hold for activists who only use Nostr; their hold runs its course. A message from an unknown route proves nothing about any network, though it still counts as a check-in.
- **"Nobody heard from" is also per route.** The mass-silence hold looks only at silence on the routes each silent activist uses.

| Situation | SMS-enabled activist | Nostr-only activist |
|---|---|---|
| Nostr down, SMS up | Clock runs; check in by SMS | Paused |
| SMS down, Nostr up | Clock runs; check in by Nostr | Unaffected |
| Both down | Paused | Paused |
| SMS back first | Resumes | Still paused |
| Nostr back first | Resumes | Resumes |
| SMS check-in from someone else during a mass-silence hold | Hold ends | Hold continues |

### A stuck pause re-alerts

An offline pause never ends on its own: if the probe's "down" signal sticks, the clock would stay frozen and silence would mean nothing forever. So while an activist stays offline-paused, the watcher re-alerts every 24 hours (`bado imesimama / still paused: clock paused since …, deadline …`). Mass-silence and going-dark holds already end on their own, so they never re-alert.

### An activist's all-clear does not end an alarm

A captor can force the activist to unlock with the real PIN. So once an alarm reaches Level 1 (a missed check-in, a panic or a duress check-in), the activist's own all-clear is shown to guardians as "the activist says they are safe" but ends nothing. Only the guardians stand the alarm down together, after a voice or video check ([guardian-confirmation.md](guardian-confirmation.md), #30). Before Level 1, while a check-in is only due or in its grace period, the activist's own all-clear still ends the watch.

### Going dark: pre-arm before a shutdown

Shutdowns often come with a few hours' warning. Uganda's was ordered on 13 January 2026 to take effect at 6pm, two days before the vote. So the activist gets a **"Going dark"** button: it sends a signed, encrypted `going-dark` event (kind 7) before the network goes down, and every guardian's watcher switches that activist — and only that activist — to shutdown mode at once.

What the event carries (code: `internal/message`, `GoingDark`; body layout in `goingdark.go`):

- **The current watch plan**: window start and end, check-in interval, grace, reminder, and the routes the activist uses. The guardian compares it against the encrypted plan it already holds; the watcher never adopts a new plan from this message alone.
- **The dark window**: how long the activist expects to be dark, 1 minute to 7 days.
- **The return window**: the check-in window the activist asks for once the dark period ends.
- **The pre-written alert, as a commitment**: SHA-256 of the canonical encrypted alert blob. The full plan and the encrypted alert do not fit in the 86-byte body, so they travel alongside over Nostr (#8) or are already held in the guardian's confirmation kit (#11); the hash tells the guardian whether what it holds is still the latest. The guardian list itself comes from enrolment (#11), not from this event.
- **A shutdown-plan reference**: a short code (at most 23 bytes) for the written plan below — who checks on whom, where, when.

The event is the same size on the wire as every other kind, so a carrier cannot tell it from a check-in ([message-format.md](message-format.md)).

What the watcher does on receipt (`internal/schedule`, `cmd/kengele-watcher`):

- **Pauses that activist's clock at once**, even with the transports up, until receipt plus the dark window. Silence inside the hold raises nothing.
- **Resumes when the hold ends.** A deadline that passed inside gets the activist's return window, once per check-in cycle; any other deadline moves on by the length of the hold. Continued silence past that misses exactly like a normal missed check-in.
- **Renews on a newer going-dark**, which replaces the hold: each renewal lasts at most 7 days from its own receipt, so a longer shutdown needs the activist's signing key again.
- **Ends early on an accepted check-in**, which proves the activist is reachable (a final check-in still closes the window). A panic or duress check-in still raises the top-level alarm at once.
- **Never downgrades**: a going-dark does not clear a missed deadline, an alarm or a closed window, and one with an unreadable body — or a replayed sequence number — is dropped.
- **Combines with the other pauses**: it converts a mass-silence hold into this hold (the activist has explained the silence), its arrival still proves its own transport for other activists' holds, and a transport outage overlapping the hold resumes only after both end.

Guardians see two bilingual notices, for example `anaenda gizani / going dark: the activist expects a shutdown, clock paused until …` and `giza kimekwisha / dark period ended: clock running again, check in by …`.

Why at most 7 days: the bound is the point. A going-dark suppresses alarms, so one message must never silence an activist for longer than a week; anything longer needs a fresh message, which needs the activist alive, free and holding their key.

Threats this addresses ([threat-model.md](threat-model.md) §4.1 A6 "Shut down the internet", §4.2 "Nationwide shutdown makes everyone silent", §5 "Going dark"): announced shutdowns must not become mass false alarms, and the alarm must fire the minute the network — or the hold — ends. Residual: forcing a going-dark under coercion (A3) still buys a bounded pause of a running clock, never of an alarm; a device already compromised (§6.4) or a majority of compromised guardians (§6.6) defeat it like everything else; and the fact that a message was sent, and when, stays visible to the network (§2, check-in timing metadata).

## Principle 2: the alert needs a path out eventually, not a live network

Every Kengele message is signed and encrypted. Any phone can carry it and deliver it later, cannot read it, and cannot fake it.

| Layer | Works when | How |
|---|---|---|
| 1. Nostr over the internet | Internet is up | Normal operation |
| 2. Encrypted SMS | Internet down, SMS working (Tanzania 2025: SMS and USSD mostly kept working) | A compact signed message of about 2 SMS, sent straight to guardians' phones. No gateway, nothing run by Altradits |
| 3. Bluetooth mesh, carry and forward | Everything down | The panic alert hops between nearby phones. Any phone that later reaches a network delivers it |

SMS exposes metadata (who texted whom, when) to the network operator. Each activist chooses whether to enable it.

### The moment of capture

The phone is usually taken or switched off within seconds. The panic trigger (for example, pressing the power button 5 times) fires **everything at once**: an SMS to guardians and a Bluetooth broadcast to every Kengele or bitchat phone nearby. At a protest that is a crowd of phones, and one of them will get the alert out.

### Optional exits for organisations

Not required, and never run by Altradits: Meshtastic LoRa radios (cheap, several kilometres, no network) and a Starlink terminal (licensed in Kenya) can act as exit points that carry alerts out during a blackout. Phones with satellite messaging can serve as another exit where that service exists.

## Principle 3: prove when it happened

A delayed alert still carries the activist's **original signed timestamp**. Kengele also anchors each alert to Bitcoin with **OpenTimestamps**, so the habeas corpus packet can show a court that "the panic was triggered at 14:07 on 15 January, during the shutdown."

## Principle 4: a written plan for when there is no signal at all

When no technology gets through, Kengele produces a **shutdown plan**: who checks on whom, at which agreed places and times, and which guardian outside the country publishes the alert if the plan fails. It is printed or memorised in advance. People protect each other; Kengele organises it.

## What it still cannot do

If the phone is taken instantly, SMS is down and no Kengele phone is nearby, nothing gets out until the network returns. Kengele then makes sure the alarm fires in the first minute the network is back, with proof of timing, instead of days later. The README must say this plainly.

## Distribution

Google Play restricts apps that read SMS. Kengele will be distributed through **F-Droid and a signed APK download**. Many activists prefer that anyway, since no Google account is needed.

## Roadmap

- **v0.1:** shutdown mode with the paused clock, the "Going dark" button, encrypted SMS fallback, carry-and-deliver-later messages, OpenTimestamps proof, the shutdown plan.
- **v0.2:** Bluetooth mesh built to **work with bitchat** (open source, already used in protests) rather than a new mesh, and a Meshtastic exit point for organisations.

## Sources

- [Tanzania: SMS/USSD kept working during the shutdown (TechCabal)](https://techcabal.com/2025/11/11/tanzania-internet-shutdown-bitcoin-for-remittance/)
- [Uganda 2026 shutdown order and timing (Unwanted Witness)](https://www.unwantedwitness.org/no-signal-no-voice-what-really-happened-during-ugandas-2026-election-shutdown/)
- [Uganda shutdown impact (CIPESA)](https://cipesa.org/2026/04/assessing-the-impact-of-the-2026-internet-shutdown-on-ugandas-digital-economy/)
- [bitchat mesh and LoRa (Glitchwire)](https://glitchwire.com/news/bitle-adds-lora-to-bitchats-solar-powered-mesh-network-enabling-multi-kilometer/)
- [Offline payments and messaging with bitchat (Bankless)](https://www.bankless.com/read/taking-digital-payments-offline)
