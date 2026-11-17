# Security policy

Kengele is meant to help people who may be abducted. A security bug here can cost a life. Please treat every report that way, and so will we.

## Do not use Kengele for real protection yet

Kengele is at the design stage. Do not rely on it to protect a real person until an independent security review is complete and this file says so. Until then, use it only with made-up data and volunteers in drills.

## Reporting a vulnerability

Do not open public issues, discussions or PRs for vulnerabilities. Use GitHub private vulnerability reporting on this repo's Security tab:
https://github.com/altradits/kengele/security/advisories/new

We will acknowledge your report within 72 hours. We will fix it or share a plan within 14 days. We credit the reporter unless they decline.

Include, where you can: what an attacker gains (location, guardian identities, the plan, silencing an alarm, faking an alarm, funds), what they need (a seized phone, a relay, the network operator, a guardian's device) and steps to reproduce with test data.

## In scope

* Anything that lets an adversary **silence** a real alarm or **fake** one.
* Anything that reveals an activist's guardians, plan, location or check-in pattern, including from a seized phone or from relay metadata.
* Weaknesses in the duress PIN, escalation rules, message signing or encryption.
* The legal-fund descriptor generator producing a wallet that guardians cannot spend, or that someone else can.

## Out of scope

Bugs in Nostr relays, Android, bitchat, Liana, Sparrow or other upstream projects. Report those upstream; tell us too if Kengele is affected.

## Supported versions

The latest release. Nothing is production-ready yet.
