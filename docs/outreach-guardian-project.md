# Outreach: Guardian Project (Círculo)

> **Status: DRAFT. Do NOT send.** This file is a draft email plus a plan for
> recording any reply. Sending, and any follow-up, needs maintainer approval
> (see "Human gates" below). No email has been sent.

Related: [prior art](prior-art.md) (Círculo section),
[threat model](threat-model.md) (sections 6–7: residual risks, false alarms).

## Why we are writing

Círculo is the closest prior art to Kengele (6-person circles, check-ins,
alerts, encrypted location sharing, no phone number or email, Matrix-based,
audited by 7ASecurity in 2025). Its site says the app "is no longer
available" and the service "will shut down on November 15, 2026", which we
have so far found only on Círculo's own site. Before citing the shutdown
elsewhere or reusing any of Círculo's approach, we want to hear from the
people who built and ran it what worked, what did not, and what they would
do differently.

## Draft email (do NOT send without maintainer approval)

```text
To: support@guardianproject.info
Subject: Kengele (open-source safety check-ins) — asking what you learned from Círculo
```

```text
Hello Guardian Project team,

I am writing from Kengele, a small open-source project building a
safety check-in tool for activists in East Africa (English and Swahili
first). When an activist goes silent during a watch window, their
guardians' devices raise the alarm — no central server, no accounts, no
phone numbers. Everything is public at [REPO URL] and the design is at
the draft stage; nothing is for real use until an independent security
review is complete.

Círculo is the closest prior art we have found, and we cite it in our
prior-art survey. We saw on encirculo.org that the app is no longer
available and the service shuts down on November 15, 2026. We are sorry
to see it go, and we would be grateful for anything you can share so we
learn from your experience rather than repeat its mistakes. Any subset
of these you have time for would help:

1. Shutdown reasons: what led to the decision to shut Círculo down?
   Was it funding, maintenance burden, low uptake, technical limits of
   the Matrix homeserver model, or something else?
2. Real usage: roughly how many people used Círculo in practice, and in
   which regions or contexts? Did it reach users in Africa?
3. False alarms: did missed check-ins or accidental triggers cause
   false alarms, and how did you handle them (reminders, private-first
   alerts, confirmation thresholds)? Anything you would do differently?
4. 7ASecurity audit lessons: the 2025 audit report is public, but is
   there anything behind it worth knowing — which findings mattered
   most, what was hard to fix, what you would design differently?
5. Reusable code: is any Círculo code (on gitlab.com/circuloapp or
   elsewhere) reusable under its open-source licence, and is there a
   part you would recommend for or against building on?
6. Threat-model review: would anyone on your team be willing to glance
   at our draft threat model ([REPO URL]/docs/threat-model.md) and tell
   us what we have missed? Even a few lines would mean a lot.

If you reply, may we summarise your answers in our public prior-art
survey ([REPO URL]/docs/prior-art.md) with credit to the Guardian
Project team? We will send you the draft text for approval before
publishing anything, and we will keep anything you mark private out of
the repo entirely. If you prefer, we can also keep the whole exchange
private.

Thank you for Círculo and for everything you publish openly. It has
already shaped our thinking.

Best wishes,
[NAME], Kengele maintainer
[CONTACT]
```

Notes for the sender (not part of the email):

- Replace `[REPO URL]`, `[NAME]`, and `[CONTACT]` before sending.
- Keep the email in English; Guardian Project works in English.
- Send from a maintainer address, not a personal one, so replies survive
  any one person leaving.

## Plan for recording answers in docs/prior-art.md

Only record anything if the reply gives permission, and only what the
permission covers:

1. Confirm scope: public summary allowed, private only, or no recording.
   If private only, keep the reply out of the repo and record just
   "Guardian Project replied on [DATE]; kept private at their request."
2. Draft the update to the Círculo section of
   [prior-art.md](prior-art.md): shutdown reasons, real usage, false
   alarms, audit lessons, reusable-code pointers. Keep each claim to one
   or two sentences and attribute it ("Guardian Project told us …").
3. Send the draft text back to Guardian Project for approval before
   merging, as promised in the email.
4. On approval, update `docs/prior-art.md` and the comparison table if a
   cell changes (for example false-alarm control or shutdown behaviour).
   Link any reusable code they point to.
5. If they agree to review the threat model, file their comments as
   `threat-model`-labelled issues and note the review in
   [threat-model.md](threat-model.md) (reviewer checklist, section 10).
6. Never put real people's names, contact details, or stories in the
   repo. Credit "the Guardian Project team" unless they ask to be named
   individually.

## Human gates (unresolved)

- [ ] Maintainer reviews this draft.
- [ ] Maintainer sends the email (or delegates sending).
- [ ] Reply received, permission confirmed, and `docs/prior-art.md`
      updated as above.
