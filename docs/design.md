# Kengele design

**When an activist goes silent, the right people know within minutes, and nobody can switch the alarm off.**

*Ukinyamaza, kengele inalia.* ("If you go quiet, the bell rings.")

Status: design stage. Not for real use until an independent security review is complete.

## Why

Speed of public pressure gets people released. In Kenya, the national human rights commission counted more than 80 abductions and enforced disappearances after the 2024 protests, and in January 2025 abducted young men were released within hours of public outcry and planned protests. Boniface Mwangi and Agather Atuhaire, detained in Tanzania in May 2025, were released after their disappearance became public.

Today the alarm depends on someone noticing and phoning around. Activists also say the phone network has been used to track them (Amnesty International, November 2025; Safaricom denies sharing data without court orders), so the network cannot be the only route for the alarm.

Kengele shortens the time between a disappearance and public pressure. It does not prevent abductions.

## Who uses it

- **The activist** switches it on before risky moments: a protest, a court visit, a trip across a border.
- **Guardians** (3 to 5 people: family, a lawyer, a trusted organisation) receive the alarm and decide what happens next.
- **The public** sees an alert only after guardians confirm it.

## How it works

1. **Watch windows, not constant tracking.** The activist sets a window, for example "Protest, 2pm to 8pm, check in every 90 minutes." Kengele is on only when needed. This saves battery and keeps Kengele from becoming another way to be tracked.
2. **The alarm runs on the guardians' devices.** Each check-in is an encrypted, signed message sent to the guardians over Nostr. Every guardian's app knows the schedule. If a check-in does not arrive, each guardian's app raises the alarm on its own. Seizing or smashing the activist's phone stops nothing.
3. **Fewer false alarms.** Before a deadline the activist gets a reminder. If they still miss it, guardians are told privately first: "Mary missed her 4:30 check-in. Try to reach her."
4. **Duress PIN.** If someone forces the activist to check in, the duress PIN shows a normal "checked in" screen and quietly raises the top-level alarm.
5. **Escalation, step by step.**
   - **Level 1, private:** guardians try to reach the activist.
   - **Level 2, public:** once 2 of 3 guardians confirm, the alert the activist wrote in advance is published on Nostr and shared by the guardians: name, photo, last check-in time, the place the activist typed in, and the lawyer's contact.
   - **Level 3, legal:** the lawyer receives a ready habeas corpus packet (a court application to make the authorities produce the person): name, ID, time and place last seen, guardian statements and signed timestamps. Kenya first; Uganda and Tanzania templates later.
6. **All clear.** The activist, or 2 guardians, can mark them "found safe", and a public update follows.
7. **Legal fund.** Kengele creates a Bitcoin wallet descriptor that either the activist alone, or 2 of 3 guardians, can spend. Guardians spend with existing, audited wallets such as Liana or Sparrow, so Kengele never touches money. The public alert can list the guardians' own Lightning addresses for donations.

## What it deliberately does not do

- **No live GPS.** If a phone or a guardian is compromised, a location trail helps the abductors. The activist types the place when they check in.
- **No servers, no accounts, no phone numbers.** Messages travel over public Nostr relays. Content and timing are hidden with NIP-44 encryption and NIP-59 gift wrap. Altradits runs nothing and holds nothing.
- **Nothing useful on a seized phone.** Guardians are stored only as keys, the plan stays encrypted, and the app has a quick-hide.
- **No promise of safety.** It shortens the time to public pressure. The README must say so.

## Building blocks

| Part | Choice |
|---|---|
| Transport | Nostr: NIP-17 private messages, NIP-44 encryption, NIP-59 gift wrap, several public relays |
| Message format | Fixed-size signed, encrypted events that fit two SMS: see [message-format.md](message-format.md) |
| Activist app | Android first (reliable background timers), distributed by F-Droid and signed APK |
| Guardian side | Guardian app, plus a small Go watcher (`cmd/kengele-watcher`) that an organisation can run as a backup |
| Timestamps | OpenTimestamps, anchored to Bitcoin |
| Legal fund | Miniscript descriptor (activist key, or 2-of-3 guardians), spent with Liana or Sparrow |
| Offline routes | See [shutdown-mode.md](shutdown-mode.md) |

## v0.1 scope

| In | Later |
|---|---|
| Android app for the activist: check-ins, duress PIN, alert written in advance | iOS |
| Guardian app, plus a Go watcher an organisation can run as a backup | Evidence recording |
| Encrypted check-ins and alarms over Nostr | LoRa and other radio exits |
| 2-of-3 confirmation, then public alert | More languages (Swahili and English first) |
| Legal-fund descriptor generator on signet | Mainnet, only after an audit |
| Habeas corpus packet, Kenya first | Uganda and Tanzania templates |
| Shutdown mode, encrypted SMS fallback, OpenTimestamps | Bluetooth mesh via bitchat (v0.2) |

## Safety rules

Lives may depend on this, so:

1. Publish the [threat model](threat-model.md) **first** and ask for review by digital-security groups: Access Now's Digital Security Helpline, Front Line Defenders, Defenders Coalition Kenya and CIPESA.
2. Label everything "Not for real use until reviewed" until they sign off.
3. Test with **simulated disappearance drills** run by volunteer pairs over the internet. Measure time to alert and false-alarm rate.
4. Keep a responsible-disclosure policy ([SECURITY.md](../SECURITY.md)) and a public log of every security fix.
5. Never put real people's data in the repo.

## Evidence plan

What we will be able to show funders such as the HRF Bitcoin Development Fund:

- A reviewed threat model, with reviewers named.
- v0.1 working, with a demo video of a full simulated disappearance: missed check-in, private alert, guardian confirmation, public alert.
- Drill numbers, for example "guardians alerted in under 3 minutes, 0 false public alerts in 40 drills".
- Bitcoin (timestamps, legal fund) and Nostr at the core, for activists anywhere.

See also [prior-art.md](prior-art.md).

## Sources

- [Abducted youth released after public outcry (AllAfrica)](https://allafrica.com/stories/202501080027.html)
- [Public pressure led to release of 4 abductees (The Standard)](https://www.standardmedia.co.ke/houghton-irungu/article/2001509386/public-pressure-led-to-release-of-4-abductees-now-let-police-account)
- [Tanzania releases Atuhaire a day after Mwangi (AllAfrica)](https://allafrica.com/stories/202505230195.html)
- [Amnesty: Kenya used digital tools against Gen Z protests](https://www.amnesty.org/en/latest/news/2025/11/kenyan-weaponized-social-media-and-digital-tools-to-suppress-gen-z-protests/)
- [Safaricom abductions backlash (Semafor)](https://www.semafor.com/article/01/07/2025/kenyas-safaricom-faces-abductions-backlash)
- [Panic Button: why it was retired (The Engine Room)](https://www.theengineroom.org/panic-button-retiring-the-app/)
