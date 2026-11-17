# Shutdown mode

How Kengele behaves when the internet, the phone network, or both are switched off, often exactly when abductions happen.

The honest starting point: **during a shutdown, silence means nothing.** If everyone goes quiet at once, no app can tell an abduction from a dead network. Kengele treats shutdowns as their own mode, not as a weaker version of normal operation.

## Principle 1: the clock only runs while the network is up

When a guardian's device sees that its own connection is gone (all relays unreachable, or many activists going silent together), it switches to shutdown mode:

- Missed check-ins **pause** instead of raising the alarm, so a blackout does not trigger hundreds of public false alarms.
- When the network returns, the clock restarts. The activist has a short window (for example 2 hours) to check in by any route. Otherwise the alarm goes off **the moment the network returns**, not days later when someone finally notices.

Shutdowns often come with a few hours' warning. Uganda's was ordered on 13 January 2026 to take effect at 6pm, two days before the vote. So the activist gets a **"Going dark"** button: it sends the latest plan, the alert written in advance and the guardian list before the network goes down.

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
