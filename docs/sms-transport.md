# SMS transport (spec v0.1)

How a Kengele message travels by plain SMS when the internet is down but the
phone network still carries texts. There is no gateway and no server: the
activist's phone texts each guardian's phone directly.

Status: **specification for review.** No Android code yet; implementation
follows once this spec is agreed (see "Out of scope" in #9). Not for real use
until the threat model (#5, [threat-model.md](threat-model.md)) and an
independent security review are done.

Related: [message-format.md](message-format.md) (the 304-character text form
this spec carries), [shutdown-mode.md](shutdown-mode.md) (why SMS matters),
[nostr-transport.md](nostr-transport.md) (the primary transport).

## Why SMS

Internet shutdowns often leave SMS working. During Tanzania's 2025 election
shutdown, SMS and USSD largely kept working while the internet was off
([TechCabal](https://techcabal.com/2025/11/11/tanzania-internet-shutdown-bitcoin-for-remittance/)).
A signed, encrypted message sent straight to a guardian's phone can carry a
panic alert through that gap.

SMS is a **fallback, never the only path.** Nostr stays the default. SMS adds
reach during internet-only shutdowns and costs privacy (see "What the phone
network sees").

## 1. When SMS is used

SMS fallback is **off by default** and turned on **per guardian** by the
activist, as the threat model requires ([threat-model.md](threat-model.md),
residual risk 7).

For each guardian with SMS on, the activist picks one of two profiles:

| Profile | Kinds sent by SMS | When |
|---|---|---|
| **Alarm only** (recommended) | panic, duress check-in, going dark, all clear | Always, alongside Nostr |
| **Shutdown check-ins** | the above, plus ordinary check-ins | Only while the activist's app is in shutdown mode (no relay reachable) |

Guardians use SMS among themselves for `missed` and `guardian-confirm`
during a shutdown, under the same per-guardian opt-in.

Guidance shown to the activist when turning SMS on:

- **Turn it on** for guardians whose link to you is already known (close
  family, your public lawyer), and before a known risk of shutdown (an
  election, a planned protest).
- **Leave it off** for guardians whose link to you should stay hidden. Every
  SMS tells the phone company that your number texted theirs.
- **Never rely on SMS alone.** If SMS is cut too, only Bluetooth carry-and-forward
  (#20) or the network returning gets the alert out.

## 2. Sending

1. The event is sealed once per guardian (`message.Seal`), giving a 228-byte
   wire message, then turned into its **304-character text form**
   (`message.EncodeText`): unpadded base64url, every character in the GSM-7
   basic set.
2. The 304 characters are sent as **one standard concatenated SMS of two
   segments** (2 × 153 characters, with the usual concatenation header). On
   Android: `SmsManager.divideMessage` then `sendMultipartTextMessage`.
   Nothing is added: no prefix, no "Kengele", no visible part numbers.
3. **Panic order:** on a panic trigger the phone may be taken or switched off
   within seconds. The app sends to SMS-enabled guardians **in the order the
   activist ranked them**, starting immediately, in parallel with the
   Bluetooth broadcast and Nostr.
4. **Resends** reuse the exact same text (same sealed bytes), so a retry
   looks like a repeat, not a new event, and the receiver drops it as a
   duplicate.

### Send results and retries

Android reports each part's result to the app's `sentIntent`:

| Result | Meaning | Action |
|---|---|---|
| `RESULT_OK` | Handed to the network (not proof of delivery) | Mark sent |
| `RESULT_ERROR_NO_SERVICE` | No service right now | Queue; retry with backoff (30 s, 1 m, 2 m, 5 m, then every 5 m) |
| `RESULT_ERROR_RADIO_OFF` | Airplane mode or radio off | Queue; retry when service returns |
| Other failures | Generic failure, null PDU, etc. | Queue; retry with backoff; show the activist |

Delivery reports (`deliveryIntent`) are **not** requested by default: they
add little and create one more record at the operator.

The SMS queue is held in memory only, for the same reason as the Nostr
outbox ([nostr-transport.md](nostr-transport.md), open question 1): nothing to
find on a seized phone. "Going dark" (#10) decides what, if anything, is kept.

## 3. Receiving and reassembly

### Path A: the phone reassembles (normal case)

When both segments arrive with their concatenation header, Android joins them
and hands the app the full 304-character body. The app strips whitespace
(some phones insert line breaks) and passes the text to
`message.DecodeText`, then `message.Open`.

### Path B: the parts arrive as two separate texts

Some networks, especially across operators or borders, deliver the parts
as two separate messages, or drop the header. The receiver handles this
without any marker:

1. A text from any number that is **exactly 153 or 151 characters** of the
   base64url alphabet (after stripping whitespace) is held as a candidate
   part, **for at most 10 minutes**, in memory.
2. When a second candidate arrives from the **same number**, the receiver
   tries `first + second` and `second + first`. Only a combination that
   decodes **and** opens with a watched sender's key is accepted.
3. At most 4 candidate parts are held per number, and at most 64 in total;
   the oldest are dropped first. This bounds memory and trial decryptions.
4. A part that never finds its partner is dropped after 10 minutes. The
   sender's retry (same text) gives another chance.

Wrong combinations fail safely. Joining the halves in the wrong order,
using one half alone, or a single changed character are all rejected by
strict decoding and the NIP-44 MAC (see the worked example below). They are
never misread as a different event.

### Recognising a Kengele SMS without a marker

There is no fixed marker for a filter to block. The receiver recognises a
Kengele message only by:

- its **shape**: 304 base64url characters (or a 153/151 part, path B), and
- the fact that it **opens** with the key of someone this guardian watches.

The sender's phone number is **not trusted** and not required to match: the
signature says who sent it. An activist can text from a borrowed phone and
still be recognised. Texts that are not Kengele messages are ignored and left
untouched.

**Honest limit:** the shape itself is distinctive. An operator that wants to
can write a filter for "exactly 304 random-looking base64url characters".
There is no room to disguise it: the message needs 304 of the 306 characters
two segments allow. This is a residual risk; Nostr remains the primary path,
and Bluetooth (#20) does not have this problem.

## 4. What the phone network sees

| The operator sees | Notes |
|---|---|
| **Sender and recipient numbers** | SIM registration is tied to national ID in Kenya, Uganda and Tanzania, so this links the activist to each guardian. **The main cost of SMS.** |
| **Time sent and received** | Regular check-ins (for example every 90 minutes) form a visible pattern. |
| **Sender's cell location** at the time of sending | The same as any text or call the phone makes. |
| **Size** | Always two segments. The size reveals nothing about the kind. |
| **Content** | Encrypted. The operator cannot read it or tell a panic from a check-in. It can **store** it. |

**No forward secrecy.** NIP-44 has none. If a guardian's private key is later
seized, stored SMS ciphertexts sent to that guardian can be decrypted. Relays
may delete old events; operators often keep SMS records. This is another
reason SMS is opt-in, and a reason to rotate keys after a guardian's phone is
lost or seized.

Compared with Nostr: relays see a gift-wrapped event from a one-time key and
cannot tell who wrote to whom ([nostr-transport.md](nostr-transport.md)). SMS
gives that up in exchange for working when the internet does not.

## 5. Messages left on the phone

On Android, the system's default SMS app stores incoming texts in its inbox.
A non-default app sending with `SmsManager` also has its sent texts written to
the system SMS store
([Android Developers Blog, "Getting Your SMS Apps Ready for KitKat"](https://android-developers.googleblog.com/2013/10/getting-your-sms-apps-ready-for-kitkat.html)).
The method that sends without saving (`sendTextMessageWithoutPersisting`) is
reserved for privileged apps.

So, unless Kengele is the default SMS app:

- the **activist's** Messages app keeps a sent thread of random-looking text
  to each guardian's number. **A seized phone shows who the guardians are.**
- each **guardian's** Messages app keeps the received texts.

This conflicts with "nothing to find on a seized phone"
([design.md](design.md)). Options for reviewers (open question 1):

1. Kengele offers to become the default SMS app during a watch window, so it
   can avoid storing or delete its own texts, then hands the role back.
2. After each send, Kengele reminds the activist to delete the thread, with a
   one-tap shortcut to it.
3. Advise a separate SIM for SMS fallback.

Until this is decided, the activist app must **show this warning** when SMS
fallback is turned on.

## 6. Duplicates, replays and order

The same signed event can arrive by SMS, by Nostr, and later by a
carry-and-forward phone, in any order.

- Every copy carries the same signed `(signer, seq)` and original
  `created_at`. The watcher keeps a **set** of seen `(signer, seq)` pairs and
  drops repeats (`schedule.Engine.Observe`). Order does not matter: a panic
  with seq 10 that arrives after a check-in with seq 11 is still acted on.
- Old timestamps are accepted, because store-and-forward means a real panic
  can arrive late (message-format.md, "Replays").
- An event counts at the time it was **signed**, not received, so an SMS
  delayed by a shutdown still proves when the activist sent it.

## 7. Interaction with the watcher clock

The watcher pauses deadlines when it believes the network is down
([shutdown-mode.md](shutdown-mode.md), principle 1). SMS changes what
"network" means:

- The SMS transport reports **its own** reachability, separately from the
  Nostr transport's `Connectivity` signal (`RelayStatus`): service present or
  absent, from Android's service state and the send result codes above.
- **An SMS arriving is not proof the internet works.** Today
  `schedule.Engine.Observe` treats any message from anyone as proof the
  network is up and releases the mass-silence hold. A message that came by
  SMS during an internet shutdown must **not** release the hold for
  activists who use Nostr only. This needs the delivering transport to be
  passed to the watcher. It's a follow-up to #7, outside this spec.
- Missed deadlines still pause while the guardian's own Nostr path is down,
  even if SMS works, unless the activist chose "Shutdown check-ins" for that
  guardian. In that case the guardian's watcher keeps that activist's clock
  running on SMS.

## 8. Android constraints and distribution

- Permissions: `SEND_SMS` (activist and guardians) and `RECEIVE_SMS`
  (guardians, and activists who receive all clears).
- Google Play allows SMS permissions only for the default SMS handler and a
  few listed exceptions. One exception, "physical safety/emergency alerts",
  covers **`SEND_SMS` only** and needs Google's approval. Receiving is not
  covered ([Play Console Help, SMS and Call Log permissions](https://support.google.com/googleplay/android-developer/answer/10208820)).
- So the build with full SMS fallback ships through **F-Droid and a signed
  APK** (#17). A Play build could at most send SMS, and only if Google grants
  the exception.
- **A no-permission fallback:** the app can show the 304-character text and
  open the phone's own SMS app with it pre-filled (the SMS intent, which
  needs no permission). A guardian can paste a received text into Kengele.
  It's slower, but it works on any build.

## 9. Cost and rate limits

| Item | Kenya | Uganda | Tanzania |
|---|---|---|---|
| Price per SMS segment, prepaid | unknown, to measure | unknown, to measure | unknown, to measure |
| Daily or bundle caps on SMS | unknown | unknown | unknown |
| Operator anti-spam throttling of repeated long random texts | unknown | unknown | unknown |

Volume formula: **events × 2 segments × SMS-enabled guardians.** For example,
a 6-hour protest with check-ins every 90 minutes ("Shutdown check-ins"
profile) and 3 SMS guardians is 4 × 2 × 3 = **24 SMS**. "Alarm only" usually
sends **0** SMS, and **6** on a panic to 3 guardians.

The unknowns above are part of the drill protocol (#18): measure them on real
networks before v0.1.

## 10. Worked example (made-up keys)

These keys are the test keys from [message-format.md](message-format.md#test-vectors).
**Never use them for anything real.**

| | Value |
|---|---|
| Activist private key | 32 bytes of `0xa1` |
| Activist public key (x-only) | `ab5d2e79cfd621b1b027ffb24e2453ed7fb571ba9a841ff0e2473466cabd168d` |
| Guardian private key | 32 bytes of `0xb2` |
| Guardian public key (x-only) | `6aa3da9b5c1d61956076cb3014ffdaa0996bacdae29ba4b89e39b4088f86ec78` |
| Event | `check-in`, seq 1, body `Kisumu`, created `2026-01-15T14:07:00Z` |
| NIP-44 nonce (test only) | 32 bytes of `0x01` |

The 304-character text form (the golden vector, checked by
`TestGoldenVector`) is sent as one two-segment SMS. If the network splits it
into two separate texts, they arrive as:

Part 1 (153 characters):

```
AQIBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAUyZvPhQzh4ngEDhik-Aoido1CqOVI9_xNhkd6hmipmQnA2x0QkKn0zUw8Jg5DvBZU1e8gdqmMXi65zaDewNNnqYJ5Dd1i1gqcxEKeClRfmUg
```

Part 2 (151 characters):

```
0R9Z_yvuO9ry8VPUB8faT5H2FQlDjEZmQ0CZnh9qSR3rFFD8EDFPQvS0aS1xXXTX5YKaCFjdWmWfDJe1cJ3Ui0bn4W0ObVUFwP3AODrSYCA95H6CVCASuZbGR9E3bLRszlh_vE7gvgVg6XCpukuGZEx
```

What the guardian's app does with each case (checked against
`internal/message`):

| Received | Result |
|---|---|
| Part 1 + Part 2 | Opens: `check-in`, seq 1, `Kisumu`, signed 14:07:00 UTC |
| Part 2 + Part 1 (wrong order) | Rejected (`unknown version`), then tries the other order |
| Part 1 alone | Rejected (`wrong size`), held up to 10 minutes for its partner |
| Any single character changed | Rejected (`not addressed to this key by any known sender`), silently dropped |
| The same text again, or the same event later via Nostr | Opened, then dropped by the watcher as a repeat of `(activist, seq 1)` |

## 11. Review against the threat model

| Threat ([threat-model.md](threat-model.md)) | How this spec responds | What remains |
|---|---|---|
| A1: state agents with telco access read SMS | Content encrypted and fixed-size; the kind is hidden | Numbers, times, cell location and pattern are visible. Opt-in per guardian. No forward secrecy |
| A1: identify the guardians | SMS off by default; guidance on which guardians to enable | Each enabled guardian is linked to the activist at the operator. The phone's SMS store shows guardians (section 5) |
| A6: internet shutdown | SMS carries alarms with no gateway | Doesn't help if SMS is also cut |
| A6: SMS blocked or filtered | No fixed marker | The shape (304 base64url characters) is filterable. Bluetooth (#20) is the next layer |
| Forged or altered alarm | Signature and MAC; strict decoding; wrong reassembly fails safely | None found at this layer |
| Replay or duplicate | `(signer, seq)` seen-set in the watcher | None found; restarts are covered by the seq check |
| Phone seized | In-memory queue; no Kengele log of texts | **The system SMS store keeps the texts unless handled** (section 5) |
| False alarm from a shutdown | SMS doesn't release the mass-silence hold for Nostr-only activists | Needs the follow-up to #7 (section 7) |

## Open questions for reviewers

1. **Texts kept in the system SMS store** (section 5): become the default SMS
   app during watch windows, remind the user to delete, or require a
   separate SIM?
2. **Filterable shape:** is the 304-character pattern acceptable, given there
   is no room to disguise it? Should "Alarm only" be the only profile, to
   keep volume (and visibility) as low as possible?
3. **Cover traffic:** regular dummy SMS would hide the check-in rhythm but
   cost money and add more operator records. Worth it for anyone?
4. **No forward secrecy:** should guardians rotate keys on a schedule, or
   should a later message format version add per-session keys?
5. **Path B windows:** are 10 minutes and 4 parts per number the right
   bounds on real East African networks, including cross-border delivery?
6. **Costs and caps** (section 9): to be measured in Kenya, Uganda and
   Tanzania during drills (#18).
7. **Play Store:** apply for the safety exception for a send-only Play build,
   or stay F-Droid/APK only?
8. **Watcher change** (section 7): track it as a new issue, building on #7.
