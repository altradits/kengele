# Habeas corpus packet — Kenya template / Jalada la habeas corpus — Kiolezo cha Kenya

> [!WARNING]
> **A Kenyan advocate must review this packet before any real use. / Wakili wa Kenya lazima apitie jalada hili kabla ya matumizi yoyote halisi.**
> This template is a draft. It is not legal advice and it is not for real use until an independent security review is complete and a Kenyan advocate has approved the wording. See [SECURITY.md](../SECURITY.md).
> Kiolezo hiki ni rasimu. Sio ushauri wa kisheria na haifai kwa matumizi halisi hadi ukaguzi huru wa kiusalama ukamilike na wakili wa Kenya aidhinishe maneno.

Status: draft for review. Unresolved: advocate review (human gate). No packet generated from this template may be filed or relied on until that review happens.

Related docs: [threat model](threat-model.md) (sections 2, 4.2, 6, 9), [design](design.md) (Level 3, legal), [message format](message-format.md), [guardian confirmation](guardian-confirmation.md).

## What this is / Hii ni nini

When guardians confirm an alarm (Level 2), the lawyer needs a ready court packet: who is missing, when and where they were last seen, what the guardians say, and a signed event log that proves timing. This document is the Kenya-first template for that packet. Uganda and Tanzania templates come later (v0.2).

Walinzi wanapothibitisha tahadhari (Level 2), wakili anahitaji jalada tayari la mahakama: nani amepotea, lini na wapi alionekana mwisho, walinzi wanasema nini, na kumbukumbu ya matukio yaliyotiwa saini inayothibitisha muda. Hati hii ni kiolezo cha Kenya kwa jalada hilo.

The Go generator (`internal/packet`, command `cmd/kengele-packet`) fills this template from the alarm record. The template below shows the blank form; the generator output follows the same headings so a lawyer can compare them side by side.

## Threats this answers / Vitisho vinavyojibiwa

From [threat-model.md](threat-model.md):

- Section 2, **Timestamps and evidence**: the packet carries only signed event times plus their OpenTimestamps proof status, so timing is believable in court.
- Section 4.2, **Trigger a false public alert**: the packet is built only after k guardians confirm; it states facts (last check-in time and place) rather than accusations (section 9).
- Section 6.10, **Timestamps before anchoring are claims**: every event row shows its original signed time and whether an OpenTimestamps proof is verified, pending, or missing. A wrong phone clock gives a wrong time; OpenTimestamps proves only that an event existed by a certain block.
- Section 9, **Evidence in court**: whether signed events and OpenTimestamps proofs are accepted is for lawyers and courts. That is why advocate review is a hard gate.

## How to generate / Jinsi ya kutengeneza

```sh
# record.json holds the alarm record (see "Fields" below). Made-up data only in this repo.
# record.json ina kumbukumbu ya tahadhari. Tumia data za kubuni pekee.
go run ./cmd/kengele-packet -in record.json -out packet.md
```

The output file is written with mode `0600`: it names a missing person and must be treated as sensitive. Delete the plain JSON afterwards if it holds real details.

Faili la matokeo linaandikwa kwa ruhusa `0600`: linamtaja mtu aliyepotea na lazima lichukuliwe kama nyeti.

## Fields / Sehemu

| Field / Sehemu | Required / Lazima | Notes / Maelezo |
|---|---|---|
| Subject name / Jina la mtu | yes / ndiyo | Full name as it should appear in court. / Jina kamili kama linavyopaswa kuonekana mahakamani. |
| National ID / Kitambulisho cha Taifa | no / hapana | Kenyan ID number if known, else "not provided / haikutolewa". / Namba ya kitambulisho kama inajulikana. |
| Time last seen / Muda alipoonekana mwisho | yes / ndiyo | The activist's last signed check-in time (RFC 3339, UTC). / Muda wa mwisho wa kuthibitisha uwepo uliotiwa saini. |
| Place last seen / Mahali alipoonekana mwisho | yes / ndiyo | Only what the activist typed. Never GPS. / Kilichoandikwa na mwanaharakati pekee. Kamwe GPS. |
| Guardian statements / Maelezo ya walinzi | yes, at least 1 / ndiyo, angalau 1 | Each: who speaks, what they say, when they signed it. / Kila moja: nani anasema, anasema nini, lini alitia saini. |
| Event log / Kumbukumbu za matukio | yes, at least 1 / ndiyo, angalau 1 | Each: kind, original signed timestamp, sequence, signer key, OpenTimestamps status (`verified / imethibitishwa`, `pending / inasubiri`, `missing / haipo`), optional note. / Kila moja: aina, muda asili uliotiwa saini, namba ya mfuatano, ufunguo wa mtia saini, hali ya OpenTimestamps, maelezo ya ziada. |

No real names, ID numbers, keys, locations, or stories in this repo. Use made-up test data only.
Hakuna majina halisi, namba za vitambulisho, funguo, maeneo, au visa halisi kwenye repo hii. Tumia data za kubuni pekee.

## Blank template / Fomu tupu

Copy from here when filling by hand. The generator produces the same sections.

```markdown
# Habeas corpus packet (Kenya) / Jalada la habeas corpus (Kenya)

> A Kenyan advocate must review this packet before any real use.
> Wakili wa Kenya lazima apitie jalada hili kabla ya matumizi yoyote halisi.

## Subject / Mtu

- Name / Jina: [FULL NAME]
- National ID / Kitambulisho cha Taifa: [ID OR "not provided / haikutolewa"]
- Time last seen / Muda alipoonekana mwisho: [RFC 3339 UTC, FROM THE LAST SIGNED CHECK-IN]
- Place last seen / Mahali alipoonekana mwisho: [WHAT THE ACTIVIST TYPED ONLY]

## Guardian statements / Maelezo ya walinzi

### 1. [GUARDIAN NAME OR LABEL]

[STATEMENT IN THE GUARDIAN'S OWN WORDS]

Signed / Imesainiwa: [RFC 3339 UTC]

## Event log / Kumbukumbu za matukio

Each row shows the sender's original signed time and its OpenTimestamps proof status.
Kila mstari unaonyesha muda asili wa mtumaji uliotiwa saini na hali ya uthibitisho wa OpenTimestamps.

| # | Kind / Aina | Signed time / Muda uliotiwa saini (UTC) | Seq | Signer / Mtia saini | OTS proof / Uthibitisho | Note / Maelezo |
|---|-------------|------------------------------------------|-----|---------------------|-------------------------|----------------|
| 1 | [KIND] | [RFC 3339] | [SEQ] | [64 HEX CHARS] | [verified / imethibitishwa, pending / inasubiri, OR missing / haipo] | [OPTIONAL] |

## Timing note / Ufafanuzi wa muda

Signed times are the sender's claims until anchored: a wrong phone clock gives a wrong time.
OpenTimestamps proves only that an event existed by a certain block.
See the threat model, section 6.10.
Muda uliotiwa saini ni madai ya mtumaji hadi uthibitishwe: saa ya simu isiyo sahihi hutoa muda usio sahihi.
OpenTimestamps inathibitisha tu kwamba tukio lilikuwepo kufikia block fulani.

## Advocate review / Uhakiki wa wakili

- [ ] A Kenyan advocate has reviewed this packet. / Wakili wa Kenya amepitia jalada hili.
- Reviewer / Aliyehakiki: [NAME, DATE]
```

## Filled example (made-up data, never real) / Mfano uliojazwa (data za kubuni, kamwe halisi)

All names, IDs, keys, places, and times below are invented for tests and docs.

```markdown
# Habeas corpus packet (Kenya) / Jalada la habeas corpus (Kenya)

> A Kenyan advocate must review this packet before any real use.
> Wakili wa Kenya lazima apitie jalada hili kabla ya matumizi yoyote halisi.

## Subject / Mtu

- Name / Jina: Amina Mfano
- National ID / Kitambulisho cha Taifa: MFANO-000001
- Time last seen / Muda alipoonekana mwisho: 2026-03-10 14:30 UTC
- Place last seen / Mahali alipoonekana mwisho: Mfano Market, Kisumu (made-up)

## Guardian statements / Maelezo ya walinzi

### 1. Guardian One (made-up)

I tried calling Amina twice after the missed check-in and got no answer.
Nilijaribu kumpigia Amina mara mbili bila mafanikio.

Signed / Imesainiwa: 2026-03-10 16:05 UTC

### 2. Guardian Two (made-up)

I went to the meeting point and she was not there. I confirm this alarm.
Nilikwenda mahali pa kukutana wala hakuwepo. Nathibitisha tahadhari hii.

Signed / Imesainiwa: 2026-03-10 16:20 UTC

## Event log / Kumbukumbu za matukio

Each row shows the sender's original signed time and its OpenTimestamps proof status.
Kila mstari unaonyesha muda asili wa mtumaji uliotiwa saini na hali ya uthibitisho wa OpenTimestamps.

| # | Kind / Aina | Signed time / Muda uliotiwa saini (UTC) | Seq | Signer / Mtia saini | OTS proof / Uthibitisho | Note / Maelezo |
|---|-------------|------------------------------------------|-----|---------------------|-------------------------|----------------|
| 1 | check-in | 2026-03-10 13:00 UTC | 12 | a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1 | verified / imethibitishwa | last check-in before silence |
| 2 | missed | 2026-03-10 14:30 UTC | 3 | b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2 | pending / inasubiri | deadline passed |
| 3 | guardian-confirm | 2026-03-10 16:20 UTC | 4 | c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3 | missing / haipo | second confirmation |

## Timing note / Ufafanuzi wa muda

Signed times are the sender's claims until anchored: a wrong phone clock gives a wrong time.
OpenTimestamps proves only that an event existed by a certain block.
See the threat model, section 6.10.
Muda uliotiwa saini ni madai ya mtumaji hadi uthibitishwe: saa ya simu isiyo sahihi hutoa muda usio sahihi.
OpenTimestamps inathibitisha tu kwamba tukio lilikuwepo kufikia block fulani.

## Advocate review / Uhakiki wa wakili

- [ ] A Kenyan advocate has reviewed this packet. / Wakili wa Kenya lazima apitie jalada hili.
- Reviewer / Aliyehakiki: [NAME, DATE]
```

## Open questions

1. Is this packet useful to lawyers in Kenya? What is missing? (For Defenders Coalition Kenya; see the threat model, section 10.)
2. Does the wording avoid legal risk for guardians? The template states facts, not accusations.
3. How should guardians attach OpenTimestamps proof files (`.ots`) alongside this markdown?
