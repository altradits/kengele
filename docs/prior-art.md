# Prior art

Tools that already try to protect people at risk, what we learn from each, and why Kengele is still needed.

## Círculo (Guardian Project and ARTICLE 19)

The closest tool to Kengele, built for journalists, activists and human rights defenders.

- A circle of 6 trusted peers, check-ins, alerts and encrypted location sharing.
- No phone number or email needed; lock and disguise features.
- Free and open source, built on Matrix; independent security audit by 7ASecurity in 2025, funded by the Open Technology Fund.

**Status:** Círculo's own website says "the app is no longer available" and that the service "will shut down on November 15, 2026." As of 17 November 2026 we found this only on Círculo's site, not in any outside report. Confirm with Guardian Project before citing it elsewhere.

**Lessons:**
- It needed the internet: its site says a connection is required to send and receive messages.
- It ran on Guardian Project's own servers. When those servers shut down, every circle stopped. Kengele has no servers.
- We found no Swahili and no use in Africa.
- Its site does not mention automatic alarms for missed check-ins.

Its code is on GitLab and may be reusable. We should ask Guardian Project what they learned.

## Amnesty International Panic Button (2014)

A panic alert sent by SMS to trusted contacts. **Retired.** Amnesty and its partners said it sometimes alerted contacts without the user meaning to, a side effect of letting people trigger it without opening the app, and it depended on SMS and a central team. Amnesty chose to advocate for panic features across devices instead.

**Lessons:** false alarms destroy trust, hence Kengele's reminder, private-first alerts and 2-of-3 guardian confirmation before anything goes public.

## Consumer check-in apps (Beacon Safe, SafePulse and similar)

They alert contacts by email, SMS or voice if you miss a check-in, even when your phone is off, because the deadline runs in the company's cloud.

**Lessons:** the idea of running the deadline away from the user's phone is right. Running it in a company cloud is wrong for activists: a government can subpoena or block it. Kengele runs the deadline on the guardians' own devices.

## bitchat

Bluetooth mesh chat with Cashu ecash, used by protesters (for example in Nepal). No check-ins, no alarm, no escalation. Kengele's v0.2 mesh should work with bitchat rather than build a new mesh.

## Briar

Messaging over Tor, Bluetooth and Wi-Fi, built for activists. No alarm or escalation logic.

## Meshtastic

Long-range LoRa radio mesh using cheap devices. Carries messages without any network, but has no alarm logic. A possible exit point for organisations during blackouts.

## What Kengele does differently

| | Círculo | Panic Button | Consumer apps | Kengele |
|---|---|---|---|---|
| Needs a central server | Yes | Central team | Yes (company cloud) | No (public Nostr relays) |
| Alarm survives a seized phone | Not stated | No | Yes | Yes (guardians' devices) |
| Works during shutdowns | No | SMS only | No | Paused clock, SMS, mesh, carry-forward |
| False-alarm control | Not stated | Weak | Reminder | Reminder, private first, 2-of-3 confirm |
| Duress PIN | Not stated | No | No | Yes |
| Legal escalation | No | No | No | Habeas corpus packet |
| Court-ready timestamps | No | No | No | OpenTimestamps on Bitcoin |
| Legal fund | No | No | No | Activist or 2-of-3 guardians, non-custodial |
| Swahili | No | Not known | Not known | First |

## Sources

- [Círculo official site (shutdown notice)](https://encirculo.org/en)
- [7ASecurity completes security audit of Círculo (Guardian Project)](https://guardianproject.info/2025/03/17/7asecurity-completes-security-audit-of-c%C3%ADrculo/)
- [Círculo code on GitLab](https://gitlab.com/circuloapp)
- [Círculo: Stay Safe Together (Guardian Project podcast)](https://guardianproject.info/podcast/2020/episode6-circulo.html)
- [Círculo final report 2023–2024](https://guardianproject.info/releases/CirculoFinalReport20232024.pdf)
- [Panic Button: why it was retired (The Engine Room)](https://www.theengineroom.org/panic-button-retiring-the-app/)
- [Beacon Safe](https://beaconsafe.app/)
- [SafePulse: Check In On Me](https://checkinonme.app/)
- [bitchat mesh and LoRa (Glitchwire)](https://glitchwire.com/news/bitle-adds-lora-to-bitchats-solar-powered-mesh-network-enabling-multi-kilometer/)
- [Offline payments and messaging with bitchat (Bankless)](https://www.bankless.com/read/taking-digital-payments-offline)
