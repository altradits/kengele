# Kengele

**When an activist goes silent, the right people know within minutes, and nobody can switch the alarm off.**

*Ukinyamaza, kengele inalia.* ("If you go quiet, the bell rings.")

> [!WARNING]
> **Status: design stage. Not for real use.** There is no working app yet. Do not rely on Kengele to protect anyone until an independent security review is complete and published here. People's lives may depend on this tool, so we will not cut corners to ship it sooner.

## Why

When an activist disappears, how fast the public finds out often decides how quickly they are freed.

* In Kenya, more than 80 people were abducted or forcibly disappeared after the 2024 protests. In January 2025, abducted young men were [released within hours of public outcry](https://allafrica.com/stories/202501080027.html).
* Boniface Mwangi and Agather Atuhaire, detained in Tanzania in May 2025, were [released after their disappearance became public](https://allafrica.com/stories/202505230195.html).
* Governments switch networks off when it matters most. Uganda [shut down the internet and mobile money](https://www.unwantedwitness.org/no-signal-no-voice-what-really-happened-during-ugandas-2026-election-shutdown/) around its January 2026 election, and Tanzania [did the same](https://techcabal.com/2025/11/11/tanzania-internet-shutdown-bitcoin-for-remittance/) in October 2025.
* Activists say the phone network has been used to track them ([Amnesty International, 2025](https://www.amnesty.org/en/latest/news/2025/11/kenyan-weaponized-social-media-and-digital-tools-to-suppress-gen-z-protests/)). The alarm cannot depend on it alone.
* The closest tool for defenders, Círculo, says on [its own site](https://encirculo.org/en) that its service shut down on 15 November 2026. It needed the internet and one organisation's servers.

Today the alarm depends on someone noticing and phoning around. Kengele makes it automatic.

## How it works

1. **Watch windows.** Before a protest, a court visit or a border crossing, the activist sets a window: "2pm to 8pm, check in every 90 minutes." Kengele is off the rest of the time.
2. **The alarm runs on the guardians' devices.** Check-ins are encrypted, signed messages sent over [Nostr](https://nostr.com) to 3 to 5 guardians (family, a lawyer, a trusted organisation). If a check-in does not arrive, each guardian's app raises the alarm on its own. Taking the activist's phone stops nothing.
3. **Duress PIN.** If someone forces the activist to check in, the duress PIN shows a normal screen and quietly raises the alarm.
4. **Escalation, step by step.**
   * Private: guardians are told first and try to reach the activist.
   * Public: once 2 of 3 guardians confirm, the alert the activist wrote in advance is published.
   * Legal: the lawyer gets a ready habeas corpus packet with signed timestamps.
5. **All clear.** The activist, or 2 guardians, mark them safe, and a public update follows.
6. **Legal fund.** A Bitcoin wallet that the activist alone, or 2 of 3 guardians, can spend, so a lawyer can be paid while the activist is held. Guardians spend it with existing wallets such as Liana or Sparrow. Kengele never touches the money.

The full design is in [docs/design.md](docs/design.md).

## Works through shutdowns

* **The clock pauses while the network is down**, so a blackout does not set off false alarms. It fires the moment the network returns if the activist has not checked in.
* **Encrypted SMS fallback** when the internet is off but SMS still works.
* **Carry and deliver later.** Signed alerts can hop between phones (Bluetooth mesh, v0.2) and be delivered by whichever phone reaches a network first.
* **Proof of time.** Every alert is anchored to Bitcoin with OpenTimestamps, so a court can see when the panic was triggered.

Details and honest limits are in [docs/shutdown-mode.md](docs/shutdown-mode.md).

## What it does not do

* **No servers.** Messages travel over public Nostr relays. Altradits runs nothing and nobody can switch Kengele off.
* **No GPS tracking.** The activist types the place when they check in. A location trail would help abductors.
* **No custody.** Kengele never holds keys or money for anyone.
* **No accounts, no phone numbers.** Guardians are stored only as keys.
* **It does not prevent abductions.** It shortens the time between a disappearance and public pressure. That is all it promises.

## Roadmap

**v0.1**

* Threat model, published first and reviewed by digital-security groups
* Android app for the activist: check-ins, duress PIN, alert written in advance
* Guardian app, and a Go watcher an organisation can run as a backup
* Encrypted check-ins and alarms over Nostr; 2-of-3 confirmation, then the public alert
* Shutdown mode, encrypted SMS fallback, OpenTimestamps proof
* Legal-fund descriptor on signet; habeas corpus packet, Kenya first

**v0.2**

* Bluetooth mesh that works with bitchat
* Meshtastic radio exits for organisations
* Uganda and Tanzania legal templates; mainnet legal fund after an audit

Work is tracked in the [issues](https://github.com/altradits/kengele/issues).

## Get involved

* **Developers:** Go (watcher, message format), Android and Kotlin (activist and guardian apps), Nostr (NIP-17, NIP-44, NIP-59), Bitcoin (miniscript, OpenTimestamps).
* **Swahili translators:** Swahili and English come first.
* **Digital-security reviewers:** the threat model needs your critique before any code reaches a real person.

Read [CONTRIBUTING.md](CONTRIBUTING.md) before you start. Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md). Never open a public issue about a security flaw, and never put real people's details in the repo.

## Prior art

Kengele learns from Círculo, Amnesty's Panic Button, bitchat, Briar and Meshtastic. See [docs/prior-art.md](docs/prior-art.md).

## License

[MIT](LICENSE)
