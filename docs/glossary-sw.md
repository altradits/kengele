# Kamusi ya Kengele — Swahili glossary

Status: draft. Not for real use until an independent security review is complete.
This wording is the author's own careful draft. It still needs review by a
Tanzanian Swahili speaker before it is used in the app or in real alerts
(human gate, not done here).

Purpose: one shared word list so the app, the docs and the alerts say the
same thing in Swahili and English. Public-alert wording here matches the
strings the watcher already sends in `internal/confirm/note.go`.

## Ukiwa na wasiwasi — if you are reading this under stress

- Read only the **first line** of a template. It says what happened.
- Then read the **numbered steps**, one by one. Do them in order.
- Words in **[brackets]** are placeholders. Replace them, never send them.
- Write times as date + hour + **UTC**, for example `2026-01-15 14:07 UTC`.
- Never add GPS coordinates, a map link, guardian names, or phone numbers
  to a public alert. Not naming guardians in public follows
  [threat-model.md](threat-model.md) 4.3 (identify guardians).

## Maneno ya Kenya na Tanzania — Kenyan vs Tanzanian notes

Kenyan Swahili borrows English freely in speech (`alert`, `check-in`, `PIN`,
`SMS`, Sheng among young people). Tanzanian standard Swahili (BAKITA)
prefers Swahili coinages (`tahadhari`, `kuripoti`, `namba ya siri`).
This glossary puts the standard Swahili word first so it works in both
countries, and names the Kenyan everyday word in the last column where
people actually say it. Where the column says `Sawa` the word is ordinary
in both countries. These notes are the author's judgment and are part of
what the Tanzanian review must check.

## Core terms — maneno muhimu

| English | Kiswahili | Maana — meaning | Kenya / Tanzania |
|---|---|---|---|
| activist | mwanaharakati | Mtu Kengele inamchunga. / The person Kengele watches over. | Sawa |
| guardian | mlinzi, w. walinzi | Mtu uliyemchagua kupokea tahadhari yako. / Someone you chose to receive your alerts. | Sawa. Si `mdhamini` (that is a financial guarantor). |
| lawyer | wakili | Wakili wako; anapokea jalada la kisheria. / Your lawyer; receives the legal packet. | Sawa |
| trusted organisation | shirika unaloliamini | Shirika, kwa mfano la haki za binadamu, linalosaidia kama mlinzi. / An organisation, e.g. human-rights, acting as a guardian. | Sawa |
| the public | umma | Watu wote; tahadhari ya hadhara inaonekana na umma. / Everyone; the public alert is visible to all. | Sawa |
| watch window | kipindi cha ulinzi | Muda ulioweka Kengele ianze na iishe. / The period you set Kengele to watch. | KE speech: `window`. TZ: `kipindi`. |
| check-in | kuripoti uko salama | Kujulisha walinzi uko salama kwa kubonyeza kitufe. / Telling guardians you are safe at the press of a button. | KE everyday: `check-in`, `kucheck-in`. TZ: `kuripoti`. |
| check-in interval | kila baada ya muda gani | Muda kati ya ripoti moja na nyingine, kwa mfano kila dakika 90. / Time between reports, e.g. every 90 minutes. | Sawa |
| reminder | ukumbusho | Ujumbe mfupi kabla muda haujaisha: ripoti sasa. / A short nudge before the deadline. | Sawa |
| grace period | muda wa ziada | Dakika chache baada ya muda kuisha kabla kengele haijalia. / A few minutes after the deadline before the alarm sounds. | Sawa |
| missed check-in | kukosa kuripoti | Muda umeisha na hukuripoti; walinzi wanaarifiwa kwa siri. / The deadline passed with no report; guardians are told privately. | Sawa |
| deadline | mwisho wa muda | Saa ya mwisho ya kuripoti. / The latest time to report. | KE: `deadline` common. TZ: `mwisho wa muda`, `ukomo`. |
| schedule | ratiba | Orodha ya nyakati za kuripoti. / The list of reporting times. | Sawa |
| alarm | kengele | Ishara kuwa mtu hajajulikana alipo. / The signal that someone is unaccounted for. | Sawa; `kengele` is also the app's name. |
| private alert (Level 1) | tahadhari ya siri | Ujumbe kwa walinzi tu: jaribu kumtafuta. / Guardians only: try to reach them. | KE speech: `alert ya siri`. TZ: `tahadhari ya siri`. |
| public alert (Level 2) | tahadhari kwa umma | Ujumbe wa hadhara baada ya walinzi wawili kuthibitisha. / Published only after 2 guardians confirm. | KE speech: `public alert`. TZ: `tahadhari kwa umma`. |
| legal packet (Level 3) | jalada la kisheria | Makaratasi tayari kwa wakili kwenda mahakamani. / Papers ready for the lawyer to go to court. | Sawa |
| confirmation | uthibitisho | Mlinzi kukubali tahadhari ni ya kweli. / A guardian agreeing the alarm is real. | Sawa |
| threshold (2 of 3) | idadi ya kuthibitisha (wawili kati ya watatu) | Tahadhari haitoki hadharani hadi walinzi wawili kati ya watatu wathibitishe. / Nothing goes public until 2 of 3 guardians confirm. | Sawa |
| false alarm | tahadhari ya uongo | Kengele iliyolia kumbe mtu yuko salama. / The alarm sounded but the person is safe. | Sawa |
| found safe / all clear | yuko salama | Habari kuwa mtu amepatikana salama; tahadhari inasitishwa. / The person is found safe; the alarm ends. | Sawa; matches `YUKO SALAMA` in the app. |
| stand down | kusitisha tahadhari | Walinzi kukubaliana hakuna hatari tena. / Guardians agreeing the danger is over. | Sawa |
| duress PIN | namba ya siri ya kulazimishwa | Namba unayoweka ukilazimishwa; inaonekana kawaida lakini inalia kengele kimya. / Entered under force; looks normal but silently alarms. | KE short: `duress PIN`. Same meaning. |
| real PIN | namba ya siri ya kawaida | Namba yako ya kila siku ya kuripoti. / Your everyday reporting PIN. | KE: `PIN ya kawaida`. |
| panic trigger | kitufe cha dharura | Kubonyeza kutuma tahadhari mara moja. / Pressing sends an alert at once. | KE: `panic button` common. |
| going dark | kujiandaa kabla mtandao haujakatwa | Kutuma mpango na tahadhari kabla ya kuzimwa kwa mtandao. / Sending plan and alert before a shutdown. | Explain in full in both; no short form. |
| quick-hide | kuficha programu haraka | Kuficha Kengele kwenye simu kwa kubonyeza mara moja. / Hiding Kengele on the phone with one press. | Sawa |
| spoken duress word | neno la siri la kusema | Neno mliyokubaliana na walinzi kulisema kwa simu ukilazimishwa. / A word agreed with guardians to say on a call under force. | Sawa |
| key | ufunguo | Namba ya kielektroniki inayothibitisha ujumbe ni wako. / Electronic secret proving a message is yours. | KE speech: `key`. TZ: `ufunguo`. |
| alert key | ufunguo wa tahadhari | Ufunguo unaotia sahihi tahadhari ya hadhara; umma huutumia kuhakiki. / Signs the public alert; the public verifies with it. | KE speech: `alert key`. |
| signature | sahihi ya kidijitali | Alama inayothibitisha ujumbe umetoka kwako na haukubadilishwa. / Proof a message is from you and unaltered. | Sawa |
| encryption | usimbaji fiche | Kufunga ujumbe ili wasomwe na walinzi tu. / Locking a message so only guardians read it. | Sawa |
| signed timestamp | muda uliothibitishwa | Saa halisi ya ujumbe iliyotiwa sahihi; inabaki hata ujumbe ukichelewa. / The message's signed original time; survives delay. | Sawa |
| share (of the secret) | sehemu ya siri | Kipande cha ufunguo kila mlinzi anachoshika; viwili vinafungua tahadhari. / Each guardian's piece of the key; two open the alert. | Sawa |
| passphrase | kishazi cha siri | Maneno ya kufungua mpango uliohifadhiwa. / Words unlocking the stored plan. | KE: `password` common. |
| relay | kituo cha kupitishia ujumbe (`relay`) | Kompyuta ya Nostr inayopitisha ujumbe bila kuusoma. / A Nostr computer passing messages without reading them. | `Relay` stays in both; gloss explains it. |
| Nostr | Nostr (mtandao bila seva kuu) | Mtandao unaotuma ujumbe bila kampuni moja kuudhibiti. / Messaging with no single company in control. | Same in both; never translated. |
| SMS fallback | SMS mbadala | SMS iliyosimbwa inapotumika mtandao ukikatika. / Encrypted SMS used when the internet is cut. | `SMS` ordinary in both. |
| shutdown mode | hali ya mtandao kukatika | Kengele inasimama kusubiri mtandao urudi badala ya kulia uongo. / Kengele waits for the network instead of false-alarming. | Sawa |
| Bluetooth mesh | simu-kwa-simu (Bluetooth) | Ujumbe unaruka kutoka simu moja hadi nyingine bila mtandao. / Messages hop phone to phone with no network. | Sawa |
| carry and deliver later | kubeba na kutuma baadaye | Simu inashika ujumbe na kuutuma mtandao ukipatikana. / A phone holds a message and sends it when a network appears. | Sawa |
| proof of time | uthibitisho wa muda | Uhakika kuwa tahadhari ilitumwa saa fulani, unaoweza kuonyeshwa mahakamani. / Proof of when an alert was sent, for court. | Sawa |
| habeas corpus | habeas corpus (amri ya kumleta mtu mahakamani) | Ombi la mahakama kuwa mamlaka wamlete aliyeshikwa. / A court order to produce a detained person. | Latin kept in both. |
| typed place | mahali ulipoandika | Mahali unapoandika mwenyewe unaporipoti; si GPS. / The place you type yourself when reporting; never GPS. | Sawa |
| legal fund | mfuko wa kisheria | Bitcoin ya kumlipa wakili ukiwa umeshikwa. / Bitcoin to pay the lawyer while detained. | Sawa |
| donation | mchango | Pesa watu wanatoa kusaidia, kwa anwani za walinzi. / Money people give to help, via guardians' addresses. | Sawa |

## Violezo vya tahadhari — alert templates

All templates are Swahili first, English second, matching the app.
Replace everything in **[brackets]**. Times are UTC. Names and places
below are placeholders only; never use real people's details.

### 1. Kukosa kuripoti — missed check-in (private, guardians only)

```text
KENGELE: SIRI — USISAMBAZE / PRIVATE — DO NOT SHARE

[Jina] hakuripoti saa [muda UTC].
[Name] missed the [time UTC] check-in.

1. Mpigie simu sasa. / Call them now.
2. Ukiwasiliana naye na yuko salama, mwambie ariripoti. / If you reach them and they are safe, ask them to check in.
3. Usipowasiliana, subiri walinzi wenzako. Tahadhari ya hadhara inahitaji wawili kati ya watatu. / If you cannot reach them, wait for the other guardians. A public alert needs 2 of 3.
4. Usitume ujumbe huu nje ya walinzi. / Do not forward this outside the guardians.
```

### 2. Tahadhari kwa umma — public alert (after 2 of 3 confirm)

The app builds this note itself; guardians sharing it by hand copy this
shape. Never add GPS, a map link, guardian names, or phone numbers.

```text
KENGELE: TAHADHARI / ALERT

[Jina] hajasikika tangu [tarehe, saa UTC].
[Name] has not been heard from since [date, time UTC].

Mahali pa mwisho (aliandika) / Last place (typed by them): [mahali / place]
Wakili / Lawyer: [jina na mawasiliano / name and contact]
Picha / Photo: [kiungo / link]

[Maneno ya mwenyewe / Their own words]

Imethibitishwa na walinzi 2 / Confirmed by 2 of the guardians they chose.
Hakiki kuwa ujumbe umetiwa sahihi na ufunguo wa tahadhari kwenye wasifu wao. / Check this note is signed by the alert key on their profile. #kengele
```

### 3. Amepatikana salama — found safe (ends the alarm)

```text
KENGELE: YUKO SALAMA / FOUND SAFE

[Jina] amepatikana salama / [Name] has been found safe.
Asanteni. / Thank you to everyone who shared the alert. #kengele

Usishiriki tena tahadhari ya awali. / Do not share the earlier alert any more.
```

## Open questions

1. Tanzanian speaker review of every Swahili line (human gate).
2. Whether the app needs a shorter Sheng-tolerant variant for Kenyan
   push notifications, or standard Swahili everywhere.
3. Lawyer contact format in the public alert (name only vs name + number)
   needs a threat-model decision before real use.
