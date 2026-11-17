# Contributing to Kengele

Kengele is a safety tool. People may one day trust it with their lives, so we move carefully: design and threat model first, code second, real use only after an independent security review.

Start with an issue labelled `good first issue` or `help wanted`. Comment to claim it. Claims lapse after 14 days without a draft PR.

## Your own work

Code, tests, docs and translations must be your own work, and you must be able to explain every line. Reviewers give their time to people who understand what they submit.

## Never put real people in this repo

No real names, phone numbers, Nostr keys, locations or stories of real activists, in code, tests, issues, screenshots or logs. Use made-up test data only.

## Commits

Every commit message is one line: a short subject, no body, no trailers. Use `area: short summary`, for example `watcher: pause clock during shutdown`. CI fails any longer message.

## Dev setup

You need Go 1.22 or newer.

```
go build ./...
go vet ./...
go test ./...
```

The Android app is not started yet. Its setup will be documented when it lands.

## Style

* `gofmt` and `go vet` must be clean.
* One logical change per PR.
* Tests for behaviour changes. Anything that touches alarms, keys or encryption needs tests for the failure cases too.
* New user-facing strings need English and Swahili.

## Security-sensitive changes

Changes to message formats, encryption, key handling, escalation rules or the duress PIN must reference the threat model (`docs/threat-model.md` once it exists) and say which threat they address. Report vulnerabilities privately; see [SECURITY.md](SECURITY.md).

## Pull requests

Fill in the template and tick every box. A maintainer reviews every PR before merge.

## Conduct

Be kind. The [Code of Conduct](CODE_OF_CONDUCT.md) applies everywhere in this project.
