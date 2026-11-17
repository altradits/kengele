# Message format (v1)

Every Kengele event (check-in, duress check-in, panic, missed, guardian
confirmation, all clear, going dark) travels in the same small, signed,
encrypted message. The same bytes can go over Nostr, SMS, Bluetooth or a
phone that carries them until it finds a network. Code: `internal/message`.

Status: design stage. Not for real use until the threat model (#5) and an
independent security review are done.

## Goals

1. **Nobody can fake it.** Each event is signed by its sender with a BIP-340
   schnorr key (the same kind of key Nostr uses).
2. **Carriers can't read it.** Each event is encrypted separately to each
   guardian with [NIP-44 v2](https://github.com/nostr-protocol/nips/blob/master/44.md).
3. **Carriers can't tell kinds apart.** Every message is exactly the same
   size. A duress check-in or a panic looks like an ordinary check-in to
   everyone except the guardian who decrypts it.
4. **It fits in two SMS.** The text form is 304 characters, all from the
   GSM-7 basic set, so it fits in two concatenated SMS (2 × 153 = 306).
5. **The original time survives.** The sender's timestamp is inside the
   signature, so a message delivered days later still proves when it was made.

## Event kinds

| Value | Kind | Sent by | Meaning |
|---|---|---|---|
| 1 | `check-in` | activist | I am safe |
| 2 | `duress-check-in` | activist | I am being forced to check in |
| 3 | `panic` | activist | Panic trigger |
| 4 | `missed` | guardian | A check-in deadline passed |
| 5 | `guardian-confirm` | guardian | I confirm the alarm (towards 2 of 3) |
| 6 | `all-clear` | activist or guardian | Found safe, stand down |
| 7 | `going-dark` | activist | Pre-arming before a shutdown |

Values are part of the format and never change. Unknown values are rejected.

## Layout

### Wire message (228 bytes)

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | Format version, `0x01` |
| 1 | 227 | NIP-44 v2 payload (below) |

### NIP-44 v2 payload (227 bytes)

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | NIP-44 version, `0x02` |
| 1 | 32 | Random nonce |
| 33 | 162 | ChaCha20 ciphertext of: 2-byte length (always 160) + the 160-byte signed plaintext. NIP-44 padding adds nothing because 160 is already a padding boundary. |
| 195 | 32 | HMAC-SHA256 |

The conversation key is NIP-44's ECDH + HKDF between the sender and that
guardian. The wire message carries **no sender key**: a guardian tries the
keys of the few people it watches over. This keeps the sender's identity off
the wire.

### Signed plaintext (160 bytes, inside the encryption)

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | Kind |
| 1 | 4 | Created at: Unix seconds, big-endian (the sender's original time) |
| 5 | 4 | Sequence number, big-endian, increases with every event from this sender |
| 9 | 1 | Body length, 0 to 86 |
| 10 | 86 | Body, then zero padding to 86 bytes |
| 96 | 64 | BIP-340 signature |

The signature is over the BIP-340 tagged hash
`SHA256(SHA256(tag) || SHA256(tag) || plaintext[0:96])` with
`tag = "kengele/event/v1"`. The tag keeps a Kengele signature from ever being
valid as a Nostr event signature, or the other way round.

The signature is **not** bound to a recipient on purpose: the same signed
event goes to every guardian and can later be shown to a lawyer, a court or
OpenTimestamps (#13) as proof of what the activist sent and when.

### SMS text form (304 characters)

Unpadded base64url of the 228-byte wire message. Its alphabet
(`A–Z a–z 0–9 - _`) is entirely in the GSM-7 basic character set, so no
character costs two septets. There is deliberately no "Kengele" prefix, so a
filter can't single the messages out by a fixed marker.

## Sizes

| Event | Wire bytes | SMS characters | SMS segments |
|---|---|---|---|
| Every kind, any body from 0 to 86 bytes | 228 | 304 | 2 |

Every message is the same size. A single SMS (153 or 160 characters) is
impossible: the 64-byte signature plus NIP-44's 32-byte nonce and 32-byte
MAC already take 171 characters.

## Strict decoding

`DecodeText` and `Open` reject, and the caller should silently drop:

- the wrong length (truncated or padded text, any `=` or whitespace)
- an unknown format version or NIP-44 version
- a message that no known sender's key decrypts, which covers a wrong
  recipient, an unknown sender and any altered character
- a bad signature, including an event signed by a guardian but presented as
  the activist's
- an unknown kind, a body length over 86, or non-zero padding after the body
- a timestamp of zero, or more than 10 minutes in the future

### Replays

Old timestamps are accepted, because store-and-forward means a real panic
can arrive days late. Replays are stopped above this package: the receiver
keeps the highest `Seq` seen per sender and drops anything at or below it
(or keeps a set of seen `(signer, seq)` pairs where order can't be
trusted). That logic belongs to the watcher (#7).

## What a carrier can still see

Encryption hides the content and kind, but not everything:

- **That a message was sent**, its time of sending and its size (always 228
  bytes / 304 characters, so the size itself reveals nothing).
- **Over SMS, the phone numbers** of sender and receiver, and the timing
  pattern. Regular check-ins every 90 minutes form a visible pattern. The SMS
  transport spec (#9) has to decide whether to add cover traffic.
- **Over Nostr**, relay operators see the outer event unless it is
  gift-wrapped (#8, NIP-59).

## Test vectors

Made-up keys only. Never use them for anything real.

| | Value |
|---|---|
| Activist private key | `a1a1…a1` (32 bytes of `0xa1`) |
| Guardian private key | `b2b2…b2` (32 bytes of `0xb2`) |
| NIP-44 nonce | 32 bytes of `0x01` |
| Event | `check-in`, created `2026-01-15T14:07:00Z` (Unix 1768486020), seq 1, body `Kisumu` |

SMS text form:

```
AQIBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAUyZvPhQzh4ngEDhik-Aoido1CqOVI9_xNhkd6hmipmQnA2x0QkKn0zUw8Jg5DvBZU1e8gdqmMXi65zaDewNNnqYJ5Dd1i1gqcxEKeClRfmUg0R9Z_yvuO9ry8VPUB8faT5H2FQlDjEZmQ0CZnh9qSR3rFFD8EDFPQvS0aS1xXXTX5YKaCFjdWmWfDJe1cJ3Ui0bn4W0ObVUFwP3AODrSYCA95H6CVCASuZbGR9E3bLRszlh_vE7gvgVg6XCpukuGZEx
```

`TestGoldenVector` checks this exact output, and `TestNIP44OfficialVector`
checks the NIP-44 library against the specification's own first vector
(`v2.valid.encrypt_decrypt[0]` in
[paulmillr/nip44 `nip44.vectors.json`](https://github.com/paulmillr/nip44)).

## Threats this addresses

From the threat model draft (#5): a carrier or relay forging an alarm or an
all clear; a compromised guardian forging the activist's events; a carrier
or telecom reading, or telling apart, check-ins and panics; delayed delivery
losing proof of time; truncated or garbled SMS being misread.

## Open questions

1. **Constant-time MAC check.** The NIP-44 library (`go-nostr/nip44`
   v0.52.3) compares the MAC with `bytes.Equal`, not a constant-time
   compare. Over SMS this is hard to exploit, but it should be fixed
   upstream rather than worked around here.
2. **Trying every sender.** A guardian watching many activists tries each
   key in turn. That's fine for a handful; an organisation running a watcher
   for hundreds may need a short, rotating recipient hint, which would leak
   a little metadata.
3. **Body semantics** (for example, which event a `guardian-confirm`
   refers to) are left to #7 and #11. 86 bytes fits a 32-byte key plus a
   sequence number with room to spare.
4. **Cover traffic over SMS** (see above) is for #9.
