# Guardian confirmation and the public alert

Status: v0.1 design and code (`internal/confirm`). Not for real use until the security review in [SECURITY.md](../SECURITY.md).

Nothing about an activist goes public until **k guardians** (default **2 of 3**) confirm the same alarm. This document explains how that rule is enforced, how an alarm ends, and what is still possible for an attacker. It answers threat-model sections [4.2 Trigger a false public alert](threat-model.md#42-trigger-a-false-public-alert) and [4.5 Forge an all-clear](threat-model.md#45-forge-an-all-clear), and adversary A4 (a compromised or pressured guardian).

## The short version

1. At setup, the activist writes the public alert and creates a separate **alert key** that will sign it. Both are locked with a random key **K**.
2. K is split into one **share** per guardian with Shamir's secret sharing. Any k shares rebuild K; fewer reveal nothing.
3. Each guardian keeps a **kit**: the locked alert, their own share and the list of guardians. A kit on its own cannot open the alert.
4. A guardian confirms an alarm by sending a `guardian-confirm` message that carries their share, to the **other guardians only**, never to the activist.
5. When a guardian's app holds k fresh shares for the same alarm, it rebuilds K, opens the alert and publishes it once as a public Nostr note signed by the alert key.

So "2 of 3" is not just a check in the code that one guardian could patch out: with one share the alert cannot even be read.

## Setup (activist's device)

`confirm.NewKits(alert, activistKey, alertKey, guardians, k)` returns one `GuardianKit` per guardian.

- `Alert` has a name, a photo reference (a link or hash, never the photo itself), a lawyer contact and optional text. **It has no location field.** Any field that looks like GPS coordinates or a map link is refused (`ErrCoordinates`).
- The alert key must differ from the activist's own key. Its public key goes on the activist's public profile **before** any alarm, so journalists and the public can check that an alert is genuine.
- The alert and the alert key are encrypted with XChaCha20-Poly1305 under K. The ciphertext is bound (as associated data) to the activist's key, the alert key, the threshold and the full guardian list, so kits from different setups cannot be mixed.
- K is split with Shamir's scheme from OpenBao (`github.com/openbao/openbao/sdk/v2/helper/shamir`, MPL-2.0), the community fork of the code HashiCorp Vault has used for its unseal keys for years. We use OpenBao rather than `hashicorp/vault/shamir` because Vault is no longer open source (BUSL since 1.15). v2.5.1 is pinned because later releases require a newer Go than Kengele supports; the Shamir file is unchanged apart from loop syntax.
- k must be at least 2 (one guardian alone must never be able to publish) and at most the number of guardians; at most 10 guardians.

Each kit must be kept only inside the guardian's encrypted plan. Handing kits to guardians, and storing them in the plan file, is part of enrolment (Android app #15 and the plan format).

## Message bodies

Both travel inside the signed, encrypted, fixed-size [Kengele message](message-format.md), so carriers cannot tell them from a check-in.

| Kind | Body | Size |
|---|---|---|
| `guardian-confirm` | `0x01` · activist key (32) · epoch (4) · share (33) | 70 bytes |
| `all-clear` | `0x02` · activist key (32) · epoch (4) | 37 bytes |

The **epoch** numbers alarms for one activist. It starts at 1 and goes up by one each time an alarm ends. Every confirmation and all-clear names its epoch, so one from an earlier alarm can never count towards a later one (replay). Confirmations up to 3 epochs ahead are kept, to let a guardian who missed an all-clear catch up; anything further ahead is refused.

## Counting (each guardian's device)

`confirm.Tally` runs per watched activist. Pass every opened event to `Handle`, including the guardian's own.

A confirmation counts only if:

- it is signed by an **enrolled guardian** (the activist cannot confirm their own alarm: their phone may be in captors' hands);
- it names this activist and the current (or a near-future) epoch;
- it is **fresh**: signed within the last 24 hours by default (`ConfirmTTL`), and not from the future. Confirmations that expire while waiting stop counting;
- it is the **first** from that guardian for that epoch (duplicates are ignored).

With k fresh shares, the tally tries every group of k until the alert opens. A guardian who sends a wrong share can delay a release until another honest guardian confirms, but cannot forge one: a wrong K fails the authenticated decryption.

The result of `Handle` is an `Outcome`:

| Action | When | What the app does |
|---|---|---|
| `None` | Below the threshold | Show the count to guardians, privately |
| `Release` | k fresh, valid confirmations | Publish the alert note **once** |
| `StandDown` | Alarm ended before release | Tell guardians privately; publish nothing |
| `FoundSafe` | Alarm ended after release | Publish a "found safe" note |

## Ending an alarm

- **The activist's own all-clear always wins**, at any time. The duress PIN never produces an all-clear (it sends a duress check-in), so a captor holding the phone cannot use this path. Guardians are still told to check by voice or video before standing down ([threat model 4.5](threat-model.md#45-forge-an-all-clear)).
- **Guardians' all-clear needs k** distinct, fresh guardian all-clears for the same epoch.
- Check-ins, duress check-ins, panics and going-dark events never end an alarm here.
- Races: an all-clear moves the epoch on, so a confirmation that arrives after it is stale and ignored. If the release happened first, the all-clear produces `FoundSafe` instead.

## The public notes

`confirm.Note` and `confirm.Publish` build and send a kind 1 Nostr note signed by the alert key:

- **Alert** (Swahili and English): the name, the time of the last signed check-in, the place the activist **typed** at that check-in (refused if it looks like coordinates), the lawyer, the photo link, the activist's own words, and how many guardians confirmed. Tags: `t=kengele` and `kengele=alert,<epoch>`.
- **Found safe**: a short update with `kengele=found-safe,<epoch>` and an `e` tag pointing at the alert note.

Publishing goes through a small `Publisher` interface so the Nostr transport (#8) or any relay client can send it. `Publish` refuses an outcome that is not `Release` or `FoundSafe`.

**No GPS, ever.** The alert carries only what the activist typed.

**Guardians are not named or co-signed in public.** Adding guardian signatures to the note would publish the guardians' keys and help identify them ([threat model 4.3](threat-model.md#43-identify-guardians)). The proof that k guardians agreed is that the note is signed by the alert key, which can only be unlocked with k shares.

## A kit is used once

After a release, the k guardians who confirmed hold the alert key. A kit is therefore **spent**: the tally refuses to release again with it (`ErrSpent`), and the activist must make new kits, with a new alert key, before the next watch window. The found-safe note should be treated as retiring that alert key.

## What is still possible (residual risk)

- **k colluding or coerced guardians** can publish a false alert or a false all-clear. This is the threshold by design; choose guardians who do not all live, work or get arrested together.
- **After a release, any one guardian who rebuilt the key** could sign a false "found safe" note or a fake second alert with the spent alert key. Apps must ignore alert-key notes after a found-safe note. A threshold signature (for example FROST) would remove this; it is an open question.
- **Captors with the activist's phone and real PIN** can send a genuine all-clear. Guardians verifying by voice or video is the only defence.
- **Unsigned fakes** shared on social media cannot be stopped; the published alert-key fingerprint lets journalists check.
- **Timing of confirmations** is visible to relays as gift-wrapped traffic between guardians (see [nostr-transport.md](nostr-transport.md)).

## Open questions

1. Should the alert key be replaced by a FROST threshold key, so no single guardian ever holds it, even after a release?
2. Where the kit lives: inside the guardian's encrypted plan (preferred), and how the activist delivers kits safely at enrolment.
3. Should an activist all-clear after a duress or panic event need a second factor (for example a guardian's voice check recorded in the app) before it stands the alarm down?
4. Is 24 hours the right confirmation lifetime, or should it follow the watch window?
5. How the activist's profile announces the alert key (a field in the kind 0 profile, or a dedicated event).

## Tests

`go test -race ./internal/confirm` covers: 1 of 3 publishes nothing; 2 of 3 publishes once; other thresholds (2-of-2, 3-of-3, 3-of-5, 10-of-10) and invalid ones; duplicate confirmations; expired confirmations; forged confirmations (stranger, the activist, altered in transit); a guardian sending a bad share; replay from an earlier alarm; a single coerced guardian; all-clear by the activist and by 2 guardians; found safe after release and the spent kit; all-clear races in both orders; concurrent confirmations releasing exactly once; the duress path; location trails; kit tampering.
