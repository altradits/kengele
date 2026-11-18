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
