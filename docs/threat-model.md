# Kengele threat model v0.1

> **Status: draft for review. Do not use Kengele to protect a real person yet.** This document is written to be reviewed by digital-security organisations before anyone relies on alarm code. See [SECURITY.md](../SECURITY.md).

Kengele is meant to shorten the time between an activist disappearing and the right people acting. A flaw can cost a life, so every feature, message format and escalation rule must answer to this document. Pull requests that touch security-sensitive code must cite the section they rely on (see [CONTRIBUTING.md](../CONTRIBUTING.md)).

Related docs: [design](design.md), [shutdown mode](shutdown-mode.md), [prior art](prior-art.md).

## 1. Scope and assumptions

**In scope:** the activist app, the guardian app, the Go watcher (`cmd/kengele-watcher`), the message format, the transports (Nostr, encrypted SMS, Bluetooth mesh carry-and-forward), the escalation rules, the habeas corpus packet, OpenTimestamps anchoring and the legal-fund descriptor generator.

**Out of scope:** the security of Android itself, Nostr relay software, bitchat, Meshtastic, Liana, Sparrow and other upstream projects. We depend on them and say so where it matters.

**Assumptions** (if one is false, the analysis below changes):

1. The activist's phone is not already compromised by spyware when Kengele is set up. Kengele cannot defend against an adversary who already controls the device.
2. At least 2 of the 3 to 5 guardians are honest and reachable when it matters.
3. The activist and guardians exchange keys in person or over a channel they already trust (for example, Signal with verified safety numbers).
4. Standard cryptography holds: secp256k1 Schnorr signatures, NIP-44 encryption (ChaCha20 + HMAC-SHA256), SHA-256.
5. The adversary can control or watch the phone network inside the country, but not every Nostr relay worldwide at once.
6. Phone clocks are roughly right (within minutes). Timestamps are signed by the sender, so they are claims, not proof, until anchored (see #13).

## 2. Assets

| Asset | Why it matters | Where it lives |
|---|---|---|
| **The activist's safety and liberty** | The thing Kengele exists to protect | — |
| **Activist identity** (name, photo, ID number in the alert) | Reveals who is being watched over; the alert is written to be public only after confirmation | Encrypted on the activist's phone and on guardians' devices |
| **Guardian identities** | Guardians can be threatened, detained or used to reach the activist | Activist's phone stores only guardian public keys and a local nickname |
| **The watch plan** (times, places, check-in interval) | Tells an adversary where the activist will be and when | Encrypted on the activist's phone; sent encrypted to guardians |
| **Alert text written in advance** | Contains personal details meant for the public only after confirmation | Encrypted until Level 2 |
| **Check-in timing metadata** | Even without content, the pattern of messages can reveal routine, presence at a protest, or that someone is a Kengele user | Visible to relays, network observers and, for SMS, the telco |
| **Signing and encryption keys** | Whoever holds them can forge check-ins or all-clears, or read messages | Activist's phone (Android Keystore where possible), guardians' devices |
| **Duress PIN and real PIN** | Revealing which is which defeats the duress feature | Activist's memory; only hashes on the phone |
| **Legal-fund keys** | Control of the bail and lawyer money | Activist's wallet; each guardian's own wallet (Kengele stores no private keys) |
| **Timestamps and evidence** | Used in the habeas corpus packet; must be believable in court | Signed events, OpenTimestamps proofs |

## 3. Adversaries

| # | Adversary | Capabilities we assume | Goal |
|---|---|---|---|
| A1 | **State security agents with telco access** | Call records, cell location, SMS content and metadata, the ability to order SMS or internet shutdowns, requests to app stores | Find, track and seize the activist; identify their network |
| A2 | **Captors who seize the phone** | Physical possession, can switch it off, search it, copy storage, use forensic tools on an unlocked or weakly locked phone | Silence the alarm; learn the guardians and the plan |
| A3 | **Coercion of the activist** | Threats or torture to force a check-in, an unlock, a PIN or the names of guardians | Make the alarm stay quiet; find guardians |
| A4 | **A compromised or pressured guardian** | One guardian's keys and device, or the guardian acting under threat | Suppress an alarm, trigger a false one, forge an all-clear, learn the plan, take the legal fund |
| A5 | **Malicious or logging Nostr relays and network observers** | Store every event, record IP addresses and timing, drop or delay events | Build metadata maps; silence alarms by dropping them |
| A6 | **Network shutdowns and SMS interception** | Internet and/or SMS off, regionally or nationally; SMS read or blocked by the operator | Make all activists silent at once; read fallback messages |
| A7 | **Insider at a partner organisation** | Access to an organisation's watcher or guardian device | Leak who the organisation protects; suppress alarms |
| A8 | **Impersonators and defamation campaigns** | Can create Nostr keys and post public messages | Post fake alarms or fake "found safe" updates to discredit activists or Kengele |
| A9 | **App store and supply-chain attackers** | Push a malicious build, a malicious dependency, or get Kengele removed from a store | Run code on every activist's phone; deny access to the tool |

## 4. Attack trees

Each tree lists the ways to reach the goal, then the mitigation and the roadmap issue that delivers it. "Residual" means the attack still partly works; see section 6.

### 4.1 Silence the alarm

| Path | Adversary | Mitigation | Issue |
|---|---|---|---|
| Seize or smash the activist's phone | A2 | The alarm runs on the guardians' devices, so silence itself triggers it | #7 |
| Force the activist to check in | A3 | Duress PIN shows a normal screen and raises the top-level alarm | #6 (duress event type), #15 |
| Force the activist to reveal which PIN is real | A3 | Both PINs give a normal-looking result; nothing on the phone tells them apart. **Residual:** a captor who already knows Kengele has a duress PIN may demand both and test them | #15 |
| Force the activist to cancel the watch window early | A3 | Cancelling sends an "all clear" that guardians see; the app should require the same PIN choice (duress PIN cancels into a silent alarm). Guardians are told how to verify by a separate channel | #11, #15 |
| Drop check-ins at the relays | A5 | Publish to several independent relays; guardians read from several. Dropping everything makes the guardians' clocks run out, which raises the alarm (silence fails safe) | #8 |
| Shut down the internet | A6 | Paused clock, SMS fallback, carry-and-forward, alarm fires the minute the network returns | #7, #9, #10, #20 |
| Pressure one guardian to ignore it | A4 | Every guardian's device alarms independently; one guardian cannot stop the others | #7, #11 |
| Pressure enough guardians to block confirmation | A4 | 2-of-3 is the default; the activist chooses guardians in different places and organisations, including one outside the country. **Residual:** if a majority is compromised, Level 2 can be blocked | #11 |
| Ship a malicious app update that disables alarms | A9 | Reproducible builds, signed APK, F-Droid; guardians and the Go watcher run independently of the activist's app | #17 |

### 4.2 Trigger a false public alert

| Path | Adversary | Mitigation | Issue |
|---|---|---|---|
| Activist genuinely misses a check-in (fatigue, battery, no signal) | — | Reminder before deadline; Level 1 is private only; Level 2 needs 2 guardians to confirm | #7, #11 |
| Nationwide shutdown makes everyone silent | A6 | Shutdown detection pauses the clock instead of alarming | #7 |
| Forge a panic or missed event | A8 | Every event is signed by the activist's key; guardians reject unsigned or wrongly signed events | #6 |
| Replay an old panic event | A5, A8 | Events carry a creation time and a watch-window ID; guardians reject events outside the active window or already seen | #6 |
| One compromised guardian publishes the alert alone | A4 | Level 2 requires 2 confirmations signed by distinct guardian keys | #11 |
| Impersonator posts a fake alert from a new key | A8 | Public alerts are signed by the activist's key and co-signed by guardians; the activist's public key is published in advance on their own profile so journalists can verify. **Residual:** people may share an unsigned fake anyway | #11 |

### 4.3 Identify guardians

| Path | Adversary | Mitigation | Issue |
|---|---|---|---|
| Search the seized phone | A2 | Guardians stored only as public keys with a local nickname; no phone numbers, names or photos of guardians. Quick-hide and encrypted storage | #15 |
| Force the activist to name them | A3 | No technical fix. **Residual.** Guardians should be people already publicly linked to the activist (family, lawyer, organisation) where possible, so naming them adds little | — |
| Watch who receives the activist's Nostr events | A5 | NIP-59 gift wrap hides sender, recipient and real timestamp from relays; random ephemeral keys per message | #8 |
| Read SMS metadata at the telco | A1, A6 | **Residual and serious.** SMS shows which numbers texted which. SMS fallback is off by default; the activist opts in per guardian, knowing the trade-off | #9 |
| Compromise one guardian and read the guardian list | A4 | Guardians do not need to know each other's identities; each sees only the keys needed to verify confirmations | #11 |
| Insider at a partner organisation | A7 | The organisation's watcher stores keys and schedules, not names. Organisations are told to limit who can access it | #7 |

### 4.4 Locate the activist through Kengele

| Path | Adversary | Mitigation | Issue |
|---|---|---|---|
| Read GPS from messages | A1, A5 | **No GPS, by design.** The activist types a place name only when they choose | #6, #15 |
| Read the watch plan from relays | A5 | Plan and check-ins are NIP-44 encrypted to each guardian | #6, #8 |
| Read the plan from a compromised guardian | A4 | **Residual.** Guardians see the plan. The activist can keep place names vague | — |
| Correlate check-in timing with cell-tower data | A1 | **Residual.** Any phone that sends anything is visible to the telco. Kengele adds no new location data, but it does add transmissions at regular intervals. Gift wrap randomises the timestamps relays see, but not the moment the phone transmits | #8 |
| Identify Kengele users from network traffic | A1 | Traffic to Nostr relays looks like other Nostr apps. **Residual:** an F-Droid install or a sideloaded APK can be visible on a searched phone | #17 |

### 4.5 Forge an all-clear

| Path | Adversary | Mitigation | Issue |
|---|---|---|---|
| Captors use the activist's unlocked phone to send "found safe" | A2, A3 | All-clear requires the real PIN; the duress PIN produces a fake success and a silent alarm. Guardians are told to verify by voice or video before standing down | #11, #15 |
| One guardian sends "found safe" | A4 | All-clear from guardians needs 2 distinct guardian signatures | #11 |
| Impersonator posts "found safe" publicly | A8 | Public updates must be signed by the activist or 2 guardians; unsigned updates are ignored by the apps | #11 |

### 4.6 Steal or freeze the legal fund

| Path | Adversary | Mitigation | Issue |
|---|---|---|---|
| Take the activist's key from the seized phone | A2 | Activist key lives in the activist's own wallet, not in Kengele. Keep only small amounts in the fund | #14 |
| One guardian spends alone | A4 | Guardian path is 2-of-3 | #14 |
| Two guardians collude | A4 | **Residual.** The design trusts a guardian majority. Keep the fund small and choose guardians in different places | #14 |
| Bug in the descriptor generator makes funds unspendable or spendable by others | A9 | Generated descriptors are checked by importing into Liana or Sparrow and doing a test spend on signet before any mainnet use; mainnet only after an audit | #14 |
| Kengele itself takes the money | — | Kengele never holds keys or funds and runs no server | #14 |

## 5. Mitigations by feature

| Feature | What it defends against | Relies on | Issue |
|---|---|---|---|
| Watch windows | Constant tracking; battery drain causing false alarms | Activist chooses start and end | #7, #15 |
| Alarm on guardians' devices | Phone seizure (A2) | Guardian app and Go watcher | #7 |
| Reminder before deadline | False alarms | Activist app | #15 |
| Duress PIN | Coercion (A3) | Indistinguishable success screens | #6, #15 |
| 2-of-3 confirmation | False public alerts; one compromised guardian (A4) | Distinct guardian keys | #11 |
| Signed events | Forged alarms and all-clears (A8) | Schnorr signatures | #6 |
| NIP-44 encryption, NIP-59 gift wrap | Relay and network observers (A5) | Nostr | #6, #8 |
| Several independent relays | Relays dropping events (A5) | Nostr | #8 |
| No GPS, no phone numbers, no accounts | Location tracking and guardian identification (A1, A5) | Design rule | [design](design.md#what-it-deliberately-does-not-do) |
| Paused clock and shutdown detection | Mass false alarms in shutdowns (A6) | Guardian app, Go watcher | #7 |
| Going dark | Shutdowns announced hours ahead (A6) | Activist app | #10 |
| Encrypted SMS fallback | Internet-only shutdowns (A6); **adds** telco metadata (A1) | Opt-in per guardian | #9 |
| Carry-and-forward over Bluetooth | Total blackouts (A6) | bitchat interop | #20 |
| Organisation exit points | Total blackouts (A6) | Meshtastic, Starlink, run by organisations, never by Altradits | #21 |
| OpenTimestamps | Disputed timing in court | Bitcoin | #13 |
| Habeas corpus packet | Delay in legal action | Signed events, timestamps | #12 |
| Miniscript legal fund | Frozen bank or mobile money; one rogue guardian (A4) | Liana or Sparrow | #14 |
| Reproducible builds, signed APK, F-Droid | Malicious builds and store removal (A9) | Build pipeline | #17 |
| Shutdown plan (people, places, times) | Total blackout with no peers nearby | Printed or memorised | [shutdown mode](shutdown-mode.md#principle-4-a-written-plan-for-when-there-is-no-signal-at-all) |
| Drills | Untested assumptions; false-alarm rate | Volunteers, made-up data | #18 |

## 6. Residual risks: what Kengele cannot do

We state these plainly so no one relies on Kengele for more than it gives.

1. **It does not prevent an abduction.** It shortens the time to private and public action.
2. **If the phone is taken instantly, SMS is down and no Kengele or bitchat phone is nearby, nothing gets out until the network returns.** Kengele then makes sure the alarm fires in the first minute the network is back, with proof of timing. This matches [shutdown-mode.md](shutdown-mode.md#what-it-still-cannot-do).
3. **During a shutdown, silence means nothing.** Guardians cannot tell an abduction from a dead network, so the alarm waits for the network to return. That can mean hours or days of delay.
4. **A device already infected with spyware defeats Kengele.** It can read the plan, the PINs and the keys.
5. **Coercion can reveal guardians** and, if the captor knows about the duress PIN, may defeat it.
6. **A majority of compromised guardians** can block confirmation, forge an all-clear or spend the legal fund.
7. **SMS fallback exposes who texted whom** to the phone network. It is off by default for this reason.
8. **Regular transmissions are visible** to the phone network, even when encrypted.
9. **People may share fake alerts** that are not signed, whatever the apps do.
10. **Timestamps before anchoring are claims.** A wrong phone clock gives a wrong time; OpenTimestamps proves only that an event existed by a certain block.

## 7. False alarms

Amnesty International's Panic Button was retired partly because it alerted contacts when users had not meant to trigger it ([The Engine Room](https://www.theengineroom.org/panic-button-retiring-the-app/)). Frequent false alarms teach guardians to ignore real ones, and a false public alert can damage an activist's credibility.

Kengele's defences, in order:

1. Watch windows, so Kengele is off most of the time.
2. A reminder before each deadline.
3. Level 1 is private: guardians try to reach the activist before anything else.
4. Level 2 needs 2 guardians to confirm.
5. Shutdown detection pauses the clock instead of alarming.
6. The panic trigger needs a deliberate gesture (for example, 5 power-button presses) that drills must show is hard to do by accident.

**Measure it:** drills (#18) record every false Level 1 and every false Level 2. The v0.1 target is **zero false public alerts** across all drills; the Level 1 rate is reported honestly whatever it is.

## 8. Safety of guardians

Guardians take on risk by agreeing to watch over someone.

- Being named as a guardian can make a person a target. Kengele never stores guardians' names or numbers on the activist's phone, and guardians do not see each other's identities unless they choose to.
- A guardian's device holds the plan. Guardians should use a screen lock, full-disk encryption and, ideally, a device they do not take to protests.
- Guardians who publish a public alert may face pressure for doing so. Organisations can act as guardians so that individuals are not alone in publishing.
- At least one guardian outside the country reduces the chance that all guardians are pressured or cut off at once.
- Guardians must be told plainly, during setup, what they are agreeing to.

## 9. Legal risks (not legal advice)

This section flags questions for lawyers. It is not legal advice, and Uganda and Tanzania specifics are out of scope for v0.1.

- **Publishing an alert** names a person and may accuse authorities. Guardians should get legal input on wording before Level 2, and the template should state facts (last check-in time and place) rather than accusations.
- **Holding or spending the legal fund** may raise questions under virtual-asset rules in each country. Kengele does not hold funds; guardians spend from their own wallets.
- **Encryption, SMS and radio exits** may be restricted in some places, especially during declared emergencies. Organisations running exit points (#21) should check local law.
- **Evidence in court:** whether signed events and OpenTimestamps proofs are accepted is for lawyers and courts. The habeas corpus packet (#12) should be reviewed by a Kenyan lawyer before use.

## 10. Reviewer checklist

We are asking each organisation to answer these questions in a GitHub issue, a private advisory, or by email to the maintainers. Partial answers are welcome.

### Access Now Digital Security Helpline
- [ ] Are the assumptions in section 1 realistic for the activists you support in East Africa?
- [ ] Is storing guardians as public keys with a local nickname enough on a seized Android phone? What should quick-hide do?
- [ ] Is NIP-59 gift wrap enough to protect guardian identities from relay operators?
- [ ] What spyware threats should be listed as known limits?

### Front Line Defenders
- [ ] Does the escalation (private, then 2-of-3 confirmation, then public) match how human rights defenders' networks respond today?
- [ ] Is the duress PIN safe, or could it endanger a defender whose captor knows about it?
- [ ] What do guardians need to be told before they agree?
- [ ] Are there groups for whom a public alert does more harm than good?

### Defenders Coalition Kenya
- [ ] Is the habeas corpus packet (#12) useful to lawyers in Kenya? What is missing?
- [ ] Does the public alert template avoid legal risk for guardians?
- [ ] Which partner organisations would act as guardians or run a watcher?

### CIPESA
- [ ] Are the shutdown assumptions right (SMS often surviving internet shutdowns, warning hours before)?
- [ ] Is opt-in SMS fallback acceptable given telco metadata access in Kenya, Uganda and Tanzania?
- [ ] What do recent shutdowns tell us about how long the paused clock might wait?

### Everyone
- [ ] Which residual risk in section 6 would stop you recommending Kengele?
- [ ] What is missing from the adversary list?

## 11. Reporting issues

Do not report vulnerabilities in public issues. Use GitHub private vulnerability reporting as described in [SECURITY.md](../SECURITY.md). Comments on this threat model itself are welcome as normal issues labelled `threat-model`.

## Changelog

- **v0.1** (2026-11): first draft for review.
