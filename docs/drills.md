# Kengele drills

Status: v0.1 protocol. Drills use volunteers and made-up data only. Not for
real use until the security review in [SECURITY.md](../SECURITY.md).

Drills are how Kengele earns trust: every escalation rule, alarm screen and
guardian step is rehearsed with volunteers before anyone relies on it. They
answer [threat-model §5 (Drills)](threat-model.md#5-mitigations-by-feature),
which lists drills as the defence against untested assumptions, and
[§7 False alarms](threat-model.md#7-false-alarms), which requires every false
Level 1 and every false Level 2 to be recorded. The v0.1 target is **zero
false public alerts** across all drills; the Level 1 rate is reported
honestly whatever it is. Guardians' safety during drills follows
[§8 Safety of guardians](threat-model.md#8-safety-of-guardians).

Related docs: [threat model](threat-model.md), [shutdown mode](shutdown-mode.md),
[guardian confirmation](guardian-confirmation.md), [message format](message-format.md).

## Rules

These apply to every drill, no exceptions.

1. **Made-up data only.** Names, places, phone numbers, Nostr keys, photos,
   wallet descriptors and stories are invented for the drill. Never use a
   real person's name, key, number or location, in the drill or in its
   write-up (see [CONTRIBUTING.md](../CONTRIBUTING.md)). Drill identities
   use the surname **Mfano** (Swahili for "example") so fiction is obvious.
2. **Test relays only.** Drill traffic goes to a relay run for the drill
   (for example a local relay on the drill network such as
   `ws://drill-relay.local:8080`), never to a public production relay.
   Every drill message, alert and note starts with `DRILL:` so a leak is
   recognisable as practice.
3. **Signet only.** Any wallet or descriptor step uses Bitcoin signet with
   throwaway keys generated for the drill. No mainnet keys, no real funds.
4. **Nothing goes public as real.** A drill Level 2 publishes only to the
   drill relay, marked `DRILL:`, and drill notes are deleted from the relay
   afterwards where the relay allows it.
5. **Anyone can stop at any time**, for any reason, without explanation.
   The observer stops the drill immediately if anyone is distressed.
6. **No simulated violence.** The duress scenario is a calm walk-through:
   no restraints, no shouting, no realistic threats. The "captor" is played
   by the observer simply standing next to the activist volunteer.
7. **Every drill ends with the all-clear scenario** so no test alarm is
   left open, and with the observer reading the results back to the group.

## Roles

A drill needs at least 5 people. One person plays one role for a scenario.

| Role | Who | Does |
|---|---|---|
| **Volunteer activist** | 1 volunteer | Carries the test phone, checks in, triggers the panic or duress step when the script says so |
| **Guardians** | 3 volunteers | Watch for alarms on test devices, try to reach the activist by a separate channel, confirm or stand down together (2-of-3) |
| **Observer** | 1 volunteer | Reads the consent text, runs the stopwatch, records timings and results, stops the drill if needed. Plays the "captor" in the duress scenario by standing nearby |

Nobody plays themselves: every participant uses a drill identity from the
results sheet, and test devices hold drill keys only.

## Consent text

The observer reads this aloud, in the language each participant prefers,
before every drill. A drill starts only when every participant says yes.

**English:**

> This is a voluntary practice drill for Kengele, a safety tool that is
> still being developed. There is no real emergency. You may stop at any
> time, for any reason, without explanation. Everything we use today —
> names, places, keys, messages — is made up for practice; do not use real
> names, real keys or real locations. Nothing from this drill will be
> published as a real alert. The observer records timings and results for
> the project; tell them if you do not want to be recorded in any way.

**Kiswahili:**

> Hili ni zoezi la kujitolea la Kengele, chombo cha usalama ambacho bado
> kinatengenezwa. Hakuna dharura ya kweli. Unaweza kusimama wakati wowote,
> kwa sababu yoyote, bila maelezo. Kila kitu tunachotumia leo — majina,
> maeneo, funguo, ujumbe — kimetungwa kwa ajili ya mazoezi; usitumie
> majina halisi, funguo halisi, wala maeneo halisi. Hakuna chochote kutoka
> zoezi hili kitakachochapishwa kama tahadhari ya kweli. Mwangalizi
> anarekodi muda na matokeo kwa ajili ya mradi; mwambie kama hutaki
> kurekodiwa kwa namna yoyote.

The observer opens each scenario with "This is a drill / Hii ni zoezi"
and closes the drill with "The drill is over / Zoezi limekwisha".

## Scenarios

Run in order. Each one ends with scenario 5 (all-clear) before the next
begins. Timings start when the triggering action happens.

### 1. Missed check-in

The activist volunteer lets a short check-in deadline (5 minutes) pass
without checking in.

1. The deadline passes. Each guardian device should raise a **private
   Level 1 alarm** on its own.
2. Guardians try to reach the activist by a separate channel (a call or a
   message outside Kengele). The activist does not answer.
3. Two guardians confirm. The drill relay should show one `DRILL:` public
   note; guardian keys must not appear in it.
4. Record: time to first private alarm, time to the public note, and
   whether any guardian device failed to alarm.

### 2. Duress (forced check-in)

The activist volunteer checks in with the **duress PIN** while the
observer stands next to them as the "captor".

1. The activist enters the duress PIN. The screen must look exactly like a
   normal successful check-in: nothing on the phone may reveal the duress.
2. Guardians should see a silent top-priority alarm. They must **not**
   contact the activist in any way the "captor" could notice.
3. Guardians verify through a separate channel agreed in advance, then two
   confirm on the drill relay.
4. Record: whether the phone screen stayed innocent, time to the guardians'
   silent alarm, and whether anyone contacted the activist unsafely
   (counts as a drill failure even if the alarm worked).

### 3. Panic trigger

The activist volunteer presses the panic gesture (for example, 5
power-button presses).

1. The test phone fires everything at once: the panic event to guardians
   (and, where built, the SMS and Bluetooth broadcast).
2. Guardians treat it as a confirmed emergency: verify fast, then two
   confirm on the drill relay.
3. Record: time from gesture to first private alarm, time to the public
   note, and whether the gesture was hard to do by accident (each
   participant tries an accidental-press check: 5 minutes of normal phone
   handling must not trigger it).

### 4. Airplane-mode shutdown

The activist volunteer switches the test phone to airplane mode for 15
minutes. This rehearses [shutdown mode](shutdown-mode.md): silence must
pause the clock, not raise the alarm.

1. Beforehand, the activist sends a **going-dark** message if the build
   supports it.
2. During the 15 minutes, guardian devices should show the clock **paused**,
   with no Level 1 and no public alert. Any alarm during the outage is a
   false alarm: record it.
3. The activist leaves airplane mode. The clock restarts with a short grace
   window (for example 10 minutes); the activist checks in inside it and no
   alarm fires.
4. Then repeat once with the activist staying silent after the network
   returns: the alarm must fire within the first minute back, proving the
   outage only delayed it.
5. Record: alarms during the outage (must be zero), time from network
   return to alarm in the silent case.

### 5. All-clear (stand-down)

Run after every scenario above.

1. The activist sends an all-clear ("found safe"). Guardians must see it
   displayed as "the activist says they are safe" — and the alarm must
   stay open, because after Level 1 an activist all-clear ends nothing
   ([guardian confirmation](guardian-confirmation.md#ending-an-alarm)).
2. Each guardian verifies by a voice or video check with the activist (a
   real call between the volunteers, or a scripted line in a tabletop).
3. Two guardians send all-clears. Only then does the alarm stand down (or
   a `DRILL:` found-safe note publishes, if Level 2 was reached).
4. Record: whether the activist-only all-clear left the alarm open (it
   must), and whether any guardian all-clear was accepted without the
   voice/video check (it must not be).

## Metrics

The observer records these for every drill. Times are measured with a
stopwatch from the triggering action.

| Metric | Definition | v0.1 target |
|---|---|---|
| Time to private alert | Trigger to first guardian device showing Level 1 | Report honestly |
| Time to public alert | Trigger to the `DRILL:` note on the drill relay | Report honestly |
| False Level 1 | Private alarms with no trigger, including any alarm during the shutdown outage | Report honestly |
| False Level 2 | Public notes with no confirmed trigger | **Zero** |
| Unsafe contact | Any guardian contact that would reveal the duress in scenario 2 | **Zero** |
| Accidental panic | Panic gestures fired by normal phone handling | **Zero** |

## Results template

Copy this into the drill log below (or a linked file) for each drill.

```markdown
### Drill N: <venue type, e.g. tabletop / live devices> — <date>

Participants (drill identities, all fictional):
- Activist: <Name Mfano>
- Guardians: <Name Mfano>, <Name Mfano>, <Name Mfano>
- Observer: <Name Mfano>

Setup: <relay used, app/watcher version, check-in interval, grace window>
Consent: read aloud in <language>; all 5 agreed / <exceptions>.

| Scenario | Expected | Observed | Time to private | Time to public | False alarms |
|---|---|---|---|---|---|
| 1. Missed check-in | … | … | … | … | … |
| 2. Duress | … | … | … | n/a or … | … |
| 3. Panic | … | … | … | … | … |
| 4. Shutdown | No alarm while offline; alarm ≤1 min after return if silent | … | n/a / … | n/a / … | … |
| 5. All-clear (×4) | Activist-only leaves alarm open; 2 verified guardians close it | … | n/a | n/a | … |

Accidental-panic check: <pass/fail, details>
Unsafe contacts: <count>
Lessons: <what to change in the protocol, the app, or the next drill>
```

## Drill log

### Drill 0: tabletop dry-run — 2026-11-18 (SIMULATED)

> **This record is simulated.** It is a worked example showing how results
> are recorded, using made-up identities. No real people took part and no
> real devices were used.

Participants (drill identities, all fictional):
- Activist: Amina Mfano
- Guardians: Baraka Mfano, Chausiku Mfano, Dalila Mfano
- Observer: Enzi Mfano

Setup: tabletop walk-through, no code run; drill relay
`ws://drill-relay.local:8080` (planned); check-in interval 5 minutes,
grace window 10 minutes.
Consent: read aloud in English and Kiswahili; all 5 agreed.

| Scenario | Expected | Observed | Time to private | Time to public | False alarms |
|---|---|---|---|---|---|
| 1. Missed check-in | Private alarm on all 3 devices, then one `DRILL:` note after 2 confirm | Walked through clean; Baraka's step needed prompting on the separate-channel call | ~2 min (narrated) | ~6 min (narrated) | 0 |
| 2. Duress | Innocent screen; silent alarm; no unsafe contact | Screen step agreed; Chausiku first suggested texting the activist, corrected to silent verify before acting | ~1 min (narrated) | ~5 min (narrated) | 0 |
| 3. Panic | Immediate alarm; gesture hard to fire by accident | Gesture agreed as 5 power presses; accidental-press check deferred to the live drill | ~1 min (narrated) | ~4 min (narrated) | 0 |
| 4. Shutdown | No alarm while offline; alarm ≤1 min after return if silent | Paused-clock behaviour agreed from the shutdown-mode doc; outage length set to 15 min for the live drill | n/a | n/a | 0 |
| 5. All-clear (×4) | Activist-only leaves alarm open; 2 verified guardians close it | Rule understood; group asked for the app to show "the activist says they are safe" prominently so guardians do not mistake it for a stand-down | n/a | n/a | 0 |

Accidental-panic check: deferred to the first live drill (no devices in a tabletop).
Unsafe contacts: 0 acted (1 suggested and corrected during discussion).
Lessons: (1) guardians need a printed separate-channel list before the
drill starts; (2) the duress "do not contact" rule needs repeating right
before scenario 2; (3) run drill 1 with live devices and a stopwatch so
the timings above become measurements instead of narration.

### Drill 1: live watcher — missed check-in, shutdown pause, post-outage alarm — 2026-11-19 (LIVE, operator-only)

> **Operator-only rehearsal, not a full drill.** Every run below used the
> real built `kengele-watcher` binary with minute-scale sealed plans, but
> one operator played all roles from one machine: no volunteers, no test
> phones, no separate guardian devices. All identities are made-up
> (surname Mfano), all keys are throwaway drill keys kept in `/tmp/drill`
> (never committed), every plan label carries a `DRILL-` prefix, and no
> traffic left the machine. Timings come from the notice timestamps
> (1-second tick) and wall clock.

Participants (drill identities, all fictional; roles narrated by the operator):
- Activist: Amina Mfano (plan label `DRILL-Amina-Mfano`), Dalila Mfano (plan label `DRILL-Dalila-Mfano`)
- Guardians: Baraka Mfano, Chausiku Mfano, Dalila Mfano (no separate devices)
- Observer: Enzi Mfano (operator)

Setup: `kengele-watcher 0.0.0-dev` built from `c995b6b`; plans sealed
with `seal-plan` (drill passphrases, 12+ chars); `-tick 1s`; alert hook
`/tmp/drill/hook.sh` appending `KENGELE_KIND/LABEL/TIME/REASON/DEADLINE`
to `/tmp/drill/hook1.log`. Gates before running: `gofmt -l` clean,
`go vet ./...` pass, `go build ./...` pass.
Consent: n/a — no volunteers; operator-only session.

Runs (exact commands; fixtures in `/tmp/drill`, not committed):
- `KENGELE_PASSPHRASE=... kengele-watcher run -plan plan1.kgp -tick 1s -alert-hook hook.sh`
  (plan1: interval 2m, grace 30s, reminder 30s; deadline 04:08:53Z)
- `... run -plan plan2.kgp -tick 1s -offline -alert-hook hook.sh`
  (plan2: interval 1m, grace 20s, reminder 15s; deadline 04:12:42Z)
- `... run -plan plan2.kgp -tick 1s` (fresh process after the deadline)
- `... run -plan plan4.kgp -tick 1s -relays ws://127.0.0.1:18099 -key-file guardian.key`
  (live mode against a closed localhost port; nothing listening, so every
  dial is refused and no traffic leaves the machine)
- `... run -plan plan5a.kgp -tick 1s -down nostr` and
  `... run -plan plan5b.kgp -tick 1s -down nostr,sms`
  (both plans: `transports: ["nostr", "sms"]`, interval 1m, grace 20s)

| Scenario | Expected | Observed | Time to private | Time to public | False alarms |
|---|---|---|---|---|---|
| 1. Missed check-in | Private alarm at the deadline; hook fires once | `started` 04:06:50Z, `reminder` 04:07:53Z, `missed` 04:08:53Z exactly at the deadline; hook fired once with `KIND=missed` | 0s past deadline (≤1 tick) | n/a (no public stage wired to the CLI) | 0 |
| 4a. Shutdown, `-offline` | No alarm while offline | `started` only; deadline 04:12:42Z passed quietly; run held ~90s past it with zero notices and zero hook fires | n/a | n/a | 0 |
| 4b. Alarm after return | Alarm ≤1 min after the network returns | Fresh online process: `started` + `reminder` + `missed` all on the first step, 0s delay | 0s | n/a | 0 |
| 4c. Live mode, dead relay | Connectivity signal pauses the clock | Live banner (`watching 1 via Nostr`); `started` only; deadline passed quietly, no `missed`: the 0-reachable-relay report paused the clock for real | n/a | n/a | 0 |
| 4d. `-down nostr`, SMS plan | Clock runs on SMS; alarm at deadline | `missed` exactly at the deadline, matching the shutdown-mode per-transport table | 0s past deadline | n/a | 0 |
| 4e. `-down nostr,sms`, SMS plan | Paused; no alarm | `started` only; deadline passed quietly | n/a | n/a | 0 |
| 5. All-clear | — | **Could not run:** the CLI has no way to inject a delivery (demo mode uses `nopTransport`; live mode needs real relays and senders), so no check-in, duress, panic, going-dark or all-clear event could be fed to the binary | n/a | n/a | 0 |

Negative paths (real CLI): `-down mesh` → `transport: mesh is not
supported yet (#20)`, exit 1; wrong passphrase → `schedule: wrong
passphrase or damaged plan file`, exit 1.

Accidental-panic check: n/a (no phone, no gesture yet — see Drill 4).
Unsafe contacts: n/a (no volunteers).
Lessons: (1) **Silent pause:** when the outage predates the window
opening, the output shows `started ... deadline ...` with no `paused`
line — the engine only emits `paused` on a live transition
(`internal/schedule/schedule.go`, `SetTransport`), while a window that
opens already-offline pauses silently (`Tick`). A guardian reading CLI
output cannot tell a paused clock from a running one. (2) CLI flags are
static per run, so the mid-run "network returns with a return window"
path cannot be driven through the binary; run 4b restarted the process,
which resets connectivity and heard-state. A drill-only delivery
injector (or a documented blocked status) is needed before scenarios 2,
3 and 5 can run against the real binary. (3) The all-clear rule
(activist-only ends nothing) is covered only by unit tests today
(`TestActivistAllClearAloneEndsNothing`, pass — see Drill 4).

### Drill 2: live watcher — mass silence — 2026-11-19 (LIVE, operator-only)

> Same operator-only conditions as Drill 1: real built binary, made-up
> Mfano identities, throwaway `/tmp` keys, no traffic off the machine.

Participants (drill identities, all fictional; roles narrated by the operator):
- Activists: Amina Mfano, Baraka Mfano, Chausiku Mfano (plan labels `DRILL-*-Mfano`)
- Guardians: Baraka Mfano, Chausiku Mfano, Dalila Mfano (no separate devices)
- Observer: Enzi Mfano (operator)

Setup: `kengele-watcher 0.0.0-dev` from `c995b6b`; one plan with 3
activists, `mass_silence: 3`, `return_window: 1m`, interval 15m30s,
grace 30s, `-tick 1s`, same alert hook. The 16-minute deadline is
deliberate: the engine's mass-silence span is a fixed 15 minutes
(`DefaultMassSpan`, not plan-configurable), so the deadline must fall
more than 15 minutes after process start or plain `missed` fires
instead. Total run ≈19.5 minutes.
Consent: n/a — no volunteers; operator-only session.

Run: `KENGELE_PASSPHRASE=... kengele-watcher run -plan plan3.kgp -tick 1s -alert-hook hook.sh`

| Scenario | Expected | Observed | Time to private | Time to public | False alarms |
|---|---|---|---|---|---|
| Mass silence (3 at once) | `mass-silence` × 3 at the deadline, not `missed`; hook fires per notice | `started` × 3 at 04:15:52Z; `mass-silence` × 3 at 04:31:52Z exactly at the deadline ("possible shutdown or mass arrest, check by hand now"), new deadline 04:32:52Z; `missed` × 3 at 04:32:53Z (1s past hold expiry = first tick after); hook fired 6× (3 `mass-silence` + 3 `missed`) | 0s past deadline (mass-silence); hold then missed 1s past expiry | n/a (no public stage wired to the CLI) | 0 |

Accidental-panic check: n/a. Unsafe contacts: n/a.
Lessons: (1) `MassSpan` (15m) and `StillPausedEvery` (24h) are not
plan-configurable (`internal/schedule/schedule.go`, `Config`), so a
short-window drill cannot rehearse mass silence quickly — this run
needed 19.5 minutes of wall clock. Drill builds need test overrides.
(2) Notice order follows activist key-hash order (Baraka first), not
plan order — cosmetic, but the drill log should note it so a reader
does not misread it as priority. (3) The "check by hand now" step after
a mass-silence hold is human-only and still needs volunteers with a
printed separate-channel list (Drill 0 lesson 1, still open).

### Drill 3: crowd-carry simulator and talk CLI — 2026-11-19 (LIVE, operator-only)

> Same operator-only conditions: real built binaries, made-up data,
> nothing off the machine. `kengele-talk` send/listen runs used a closed
> localhost port only (see "Could not run").

Participants (drill identities, all fictional): Activist: Amina Mfano;
Guardians: Baraka Mfano, Chausiku Mfano, Dalila Mfano; Observer: Enzi
Mfano (operator).
Setup: `kengele-meshlink 0.0.0-dev` and `kengele-talk 0.0.0-dev` from
`c995b6b`; 4 throwaway drill keys + 1 probe key via `talk keygen`
(mode 0600, fingerprint shown); notes prefixed `DRILL:`.
Consent: n/a — no volunteers; operator-only session.

`kengele-meshlink` (all in-memory, no radio; each run ≈0.08s):
- `demo`: A→B→C, B relayed blindly (`blind: no decrypt`), C delivered
  `kind=check-in seq=1 hops=2`. Note: C also re-relays (ttl 5) — the
  flood continues past the destination, as designed.
- `demo --sw`: full Swahili strings, delivered the same way.
- `demo --drop B` and `demo --drop C`: honest silence —
  `C received 0 (no path); silence fails safe`.
- `demo --drop A`: still delivered. Consistent with the code semantic
  (`Drop` discards every *received* fragment,
  `internal/meshcarry/carry.go`; `Inject` bypasses `Receive`), but the
  CLI help ("nodes that discard everything") over-promises — wording fix.
- `demo --ttl 1`: B relayed at ttl 0, C received 0 — the TTL-exhaustion
  path verified live. `--ttl 0` means `DefaultTTL` by design
  (`carry.go`: "Zero means DefaultTTL").
- `qr`: 304-character line plus `Show this QR / Onyesha QR hii` —
  matches the documented QR-courier form (`docs/offgrid-comms.md`).
- Misuse: `--drop Z` and `--ttl 300` → clear errors, exit 2.

`kengele-talk`:
- `keygen` × 5: pass; mode 0600 enforced even over existing files.
- Oversize note (109 bytes): refused cleanly (`max 86`, bilingual), exit 1.
- `send` to closed `ws://127.0.0.1:18099`: exit 0, `accepted by 0 of 1
  relays, queued for retry`. The count is honest, but **"queued for
  retry" over-promises**: the outbox is memory-only
  (`internal/transport/nostr/outbox.go`: "queued messages are lost if
  the process stops"), and the one-shot `send` process exits at once.
- `listen` on the closed port for 25s: banner only, silent retry, exit 0
  on SIGTERM — no false output.
- `send` without `-relays`: clean error (`there is no default`), exit 1.

Could not run: a live `talk send` → `talk listen` round-trip, and any
watcher live delivery of check-ins, because this sandbox refuses TCP
binds (`listen tcp 127.0.0.1:0: bind: operation not permitted`), so no
local drill relay could be started; public relays are forbidden by the
drill rules. For a human rerun on an open machine: start any minimal
NIP-01 relay on `ws://127.0.0.1:18099`, then in one terminal
`kengele-talk listen -key guardian.key -relays ws://127.0.0.1:18099 -senders <activist-pub>`
and in another
`kengele-talk send -key activist.key -to <guardian-pub> -relays ws://127.0.0.1:18099 -note "DRILL: Mfano check-in"`.
Same cause blocks 9 repo tests here (`go test -race ./...`:
`internal/meshexit` × 2, `internal/transport/nostr` × 7 fail at
`net.Listen` with the same error); every other package passes,
including `cmd/kengele-watcher`, `cmd/kengele-talk`,
`cmd/kengele-meshlink`, `internal/schedule`, `internal/confirm`,
`internal/message`, `internal/transport/bitchat` and
`internal/meshcarry`. The tree was otherwise untouched, so these are
environmental, not regressions.

| Scenario | Expected | Observed | Time to private | Time to public | False alarms |
|---|---|---|---|---|---|
| Crowd carry (simulator) | Blind relay; silence fails safe | Delivered hops=2; `--drop B/C` and `--ttl 1` all fail silent honestly | n/a (transport drill) | n/a | 0 |
| QR courier | 304 chars | 304-char line + show-QR prompt | n/a | n/a | 0 |
| Talk send/listen | Round-trip over drill relay | **Could not run** (no local relay; see above). Negative paths all behave honestly | n/a | n/a | 0 |

Accidental-panic check: n/a. Unsafe contacts: n/a.
Lessons: (1) Fix `talk send`'s "queued for retry" wording for the
one-shot CLI, or persist the queue — today the queue dies with the
process. (2) Fix `meshlink demo --drop` help wording ("discard
everything" → "discard everything received"). (3) The project needs a
checked-in minimal local drill relay (the test relay in
`internal/transport/nostr/relay_test.go` is test-only) so talk/watcher
live drills are reproducible without public relays.

### Drill 4: tabletop against the current code — 2026-11-19 (SIMULATED)

> **This record is simulated.** One operator walked each scenario
> against the code at `c995b6b` and the passing unit tests below. No
> volunteers, no phones, no radios. It shows what the code already
> implements per step — and what still needs human volunteers.

Participants (drill identities, all fictional): Activist: Amina Mfano;
Guardians: Baraka Mfano, Chausiku Mfano, Dalila Mfano; Observer: Enzi
Mfano (operator).
Setup: code walk + in-process unit tests (`go test -race`, all pass):
`TestGoingDark` (16 subtests), `TestStillPaused`, `TestStandDown`,
`TestWatchLoopGoingDark`, `TestWatchLoopNostrDown`,
`TestActivistAllClearAloneEndsNothing`, `TestAllClearByTwoGuardians`,
`TestTwoOfThreeReleasesOnce`, `TestGuardianAllClearNeedsVerification`,
`TestOneOfThreeDoesNothingPublic`, `TestDuressPath`.
Consent: n/a — no volunteers; operator-only session.

| Scenario | Expected | Observed (code walk) | Time to private | Time to public | False alarms |
|---|---|---|---|---|---|
| 1. Missed check-in | Private alarm, then one `DRILL:` note after 2 confirm | Engine half verified live in Drills 1–2. Confirm half is code-only: 1-of-3 publishes nothing, 2-of-3 releases once (`internal/confirm/tally.go`, `TestTwoOfThreeReleasesOnce`), shares bound to activist/threshold/guardian list (`internal/confirm/kit.go`) | live: 0s (Drills 1–2) | SIMULATED (no CLI path publishes the note) | 0 |
| 2. Duress | Innocent screen; silent alarm; no unsafe contact | Screen: `DURESS` returns the identical success UI as `REAL` (`android/.../pin/PinStore.kt`, `CheckInResultActivity.kt`; `CheckInManager.kt` builds `DURESS_CHECK_IN` with identical UI). Alarm: engine raises top-priority `alarm` (`internal/schedule/schedule.go`, `Observe`), duress path tested (`TestDuressPath`). No-unsafe-contact is human-only — no code can enforce it | SIMULATED (no delivery path in the CLI; engine covered by unit tests) | n/a | 0 |
| 3. Panic | Immediate alarm; gesture hard to fire by accident | Engine side exists (`NoticeAlarm` on `KindPanic`, `schedule.go`). Phone side is event-builder only: `PanicTrigger.buildEvent` (`android/.../panic/PanicTrigger.kt`) with the gesture, confirmation screen and transports explicitly still missing (issues #8, #9). Accidental-press check impossible until the gesture exists | SIMULATED | n/a | 0 |
| 4. Shutdown | No alarm while offline; alarm ≤1 min after return if silent | Pause/resume verified live in Drill 1 (4a–4e). Going-dark pre-arm is code-only: `GoingDarkEvent` (`internal/message/goingdark.go`) → `goingDark` pauses at once (`schedule.go`), `Tick` ends the hold with the return window, check-in ends it early, panic/duress still alarm inside the hold — all covered by `TestGoingDark` (16 subtests, pass) and `TestWatchLoopGoingDark` (pass). Still-paused re-alert every 24h (`schedule.go`, `TestStillPaused`) never fires in a short drill | live for pause (Drill 1); SIMULATED for going-dark expiry | n/a | 0 |
| 5. All-clear (×4) | Activist-only leaves alarm open; 2 verified guardians close it | Activist all-clear at Level 1+ → `activist-says-safe`, ends nothing (`schedule.go`, `Observe`); `StandDown` is guardians-only (`schedule.go`); guardian all-clears refuse without a voice/video `Verification` kept on-device (`internal/confirm/body.go`, `GuardianAllClearEvent`); races tested (`TestAllClearByTwoGuardians`, `TestGuardianAllClearNeedsVerification` — pass). The voice/video check itself is human | SIMULATED | n/a | 0 |
| Crowd relay (phones/radios) | Blind carry over Bluetooth; QR courier in total blackout | Simulator verified live in Drill 3 (`internal/meshcarry`, `cmd/kengele-meshlink`). Real BLE is a plan/test stub only: `internal/transport/bitchat` "builds no mesh and speaks no BLE" (`carry.go`); Android mesh UI/service files exist (`android/.../mesh/`) but were not exercised here. QR courier verified live (304 chars, Drill 3). Dense-crowd TTL cap exists (`meshcarry/carry.go`) | SIMULATED for real radios | n/a | 0 |

Accidental-panic check: **blocked** — no gesture exists yet
(`PanicTrigger.kt` stub, issues #8, #9), so there is nothing to
accidentally fire or to rehearse.
Unsafe contacts: n/a (no volunteers; the rule is human-only).
Lessons: (1) **A live Level 2 drill is not safe to run yet:**
`confirm.Note` (`internal/confirm/note.go`) builds the public note with
no drill-marking option, so a rehearsed release would publish an
*unmarked* public note — drill rule 4 ("marked `DRILL:`") has no code
support. Add a drill flag to note building (or a drill-only publisher)
before any volunteer touches the confirm path live. (2) Screen
innocence (duress PIN) needs a human glance test on real devices; code
review of `PinStore.kt` is not enough. (3) The 15-minute airplane-mode
run, the post-return grace check-in, the accidental-alarm count, the
voice/video verification, and the crowd relay on real phones/radios all
still need 5 volunteers, test phones and a local drill relay — Drills
1–3 cleared the CLI/transport half so the volunteer session can focus
on the human half. (4) Agree a spoken duress word before scenario 2
(`docs/guardian-confirmation.md` threat list) and print the
separate-channel list before scenario 1 (Drill 0 lesson 1, still open).

## Addendum (2026-11-19): landed since Drills 1–4 ran

Drills 1–4 ran against `c995b6b`. Since then, two "blocked" items cleared:

- **Panic gesture exists now.** The five-press trigger, confirmation dialog
  path and settings screen landed after the drill run; the accidental-press
  check in lesson 3 is ready for volunteers (see the panic field-test steps
  in the originating change).
- **Phone BLE radio proven live.** The activist app's GATT layer was
  exercised against a real phone: advertisements observed, a sealed panic
  event carried phone→laptop over BLE and relayed byte-identical with
  TTL 7→6, and the phone's own counter showed the relay. Mesh→guardian
  intake is wired; the remaining radio work is crowd drills (3+ phones),
  not code.

Since the addendum: lesson 1 is closed — `DrillNote`/`DrillPublish`
(`internal/confirm/note.go`) mark rehearsed releases with a `DRILL:`
prefix and a drill tag, and drills must publish through them.

Still open: the duress-screen glance test and the full 5-volunteer
session.
