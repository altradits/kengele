# Kengele distribution plan: F-Droid and reproducible APK

> **Status: plan. Not for real use.** There is no Android app yet (it lands in
> [#15](https://github.com/altradits/kengele/issues/15)). Do not rely on this
> plan to protect anyone until the app exists, the builds are reproducible in
> practice, and an independent security review is complete. See
> [threat-model.md](threat-model.md) and [SECURITY.md](../SECURITY.md).

This document plans how the Kengele Android app reaches activists and
guardians: F-Droid metadata, reproducible builds, signing-key custody, user
verification and the update channel.

## 1. Goal and non-goals

**Goal:** anyone can install a Kengele APK and check, without trusting any
single server or store, that it matches the published source.

**Non-goals (out of scope for this issue):**

- Publishing Kengele to F-Droid. This issue only writes the plan and the
  verification workflow.
- The Android app itself (issue #15). The workflow below tolerates `android/`
  not existing yet and passes with a skip notice until it lands.

## 2. Threat-model review

This plan answers [threat-model.md](threat-model.md) §3 adversary **A9 (app
store and supply-chain attackers)** and the rows that point at this issue:

| Threat-model item | How this plan answers it |
|---|---|
| §4.1 "Ship a malicious app update that disables alarms" (A9) | Reproducible builds (§4) plus independent rebuilds: a malicious build differs from what anyone rebuilds from source |
| §5 "Reproducible builds, signed APK, F-Droid" | This document and `.github/workflows/reproducible-apk.yml` |
| §3 A1 "requests to app stores" (removal pressure) | F-Droid plus verified sideload (§7), so removal of one channel does not remove the tool |
| §4.4 "an F-Droid install or a sideloaded APK can be visible on a searched phone" | **Residual, accepted.** Distribution cannot hide the installed app from a phone search (A2). Quick-hide and encrypted storage are #15's job |

Related residual risks we keep: a compromised build machine can ship a bad
binary until someone rebuilds and compares (§4, §6), and users who skip
verification get no protection from it (§6 keeps the steps short for that
reason).

## 3. F-Droid metadata plan

Target package id: `org.altradits.kengele` (placeholder until #15 fixes it).

- **Build mode:** F-Droid builds from source. Kengele additionally publishes
  its own reproducible-build check (§4) so F-Droid's binary can be cross-checked
  against an independent rebuild.
- **Metadata:** `metadata/org.altradits.kengele.yml` in fdroiddata (or a
  `fastlane/metadata/android/` tree in this repo) with `Categories: [Security]`,
  `UpdateCheckMode: Tags`, per-release changelogs, and a pinned commit per
  version code.
- **Languages:** full `Summary`/`Description` in English and Swahili first
  (see [CONTRIBUTING.md](../CONTRIBUTING.md)); other languages later.
- **AntiFeatures:** none expected. Kengele has no tracking, no ads and no
  non-free dependencies. If that ever changes, the AntiFeature ships in the
  metadata before the release, not after.
- **Screenshots and art:** made-up data only, never real people, places or
  keys, per [CONTRIBUTING.md](../CONTRIBUTING.md). No screenshots showing a
  real watch plan or real guardian keys.
- **First submission:** only after the app exists (#15), two independent
  rebuilds match (§4), and the security review passes. Publishing itself is a
  later issue, not this one.

## 4. Reproducible build steps

Reproduce the release APK from a tagged commit with only pinned inputs:

1. Check out the release tag. Record `SOURCE_DATE_EPOCH` from the commit time:
   `git log -1 --format=%ct`.
2. Use the pinned toolchain: exact JDK, Android Gradle Plugin, Gradle wrapper
   and SDK versions recorded in `android/` (see #15). The CI workflow selects a
   pinned JDK and refuses to build when it is absent.
3. Verify the Gradle wrapper before running it: the wrapper checksum pinned in
   `gradle-wrapper.properties` must match the downloaded distribution. Never
   run an unverified wrapper.
4. Build with a fixed environment:
   `TZ=UTC LC_ALL=C.UTF-8 SOURCE_DATE_EPOCH=<commit time>` and Gradle
   `--no-daemon`, from a clean checkout, twice, in separate directories.
5. Compare the two unsigned release APKs byte for byte:
   `sha256sum android/app/build/outputs/apk/release/*.apk`.
   Equal hashes pass; any difference fails the release until the
   nondeterminism is found and removed (usual suspects: timestamps in outputs,
   absolute paths, dependency version ranges, parallel file ordering).

CI demonstrates this on every push and pull request touching `android/` (see
`.github/workflows/reproducible-apk.yml`): it builds twice and fails when the
two hashes differ. Until #15 lands `android/`, the job passes with a skip
notice instead of failing. What CI compares is the **unsigned** release APK
built with the debug key; the release signature is applied later (§5), and
signature bytes are verified separately, never by hash equality with CI output.

## 5. APK signing-key custody

- The release key is generated offline on hardware security tokens. Two
  custodians each hold one token; signing needs both. The private key never
  touches CI, GitHub secrets, developer laptops or this repository.
- CI signs with the throwaway debug key only, for the reproducibility check in
  §4. A CI-signed APK is never distributed to users.
- The release certificate fingerprint is published in the release notes and in
  this document (example format, not a real key):
  `SHA-256 EXAMPLE 00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB`.
- **Rotation:** a new key is generated the same offline way, both fingerprints
  are published side by side for one release cycle, and the F-Droid metadata is
  updated. `versionCode` stays monotonic so devices refuse downgrades.
- **Compromise:** if a token is lost or a fingerprint mismatch is reported,
  stop signing, publish what happened and which versions are affected, rotate
  the key, and ask F-Droid to rebuild from the last good tag. Never rotate
  silently: a silent key change is indistinguishable from an A9 attack.

## 6. User verification

Every install or update page shows the fingerprint from §5 and these steps.
Keep the check short: verification users skip is verification that fails.

1. Download the APK and note its SHA-256 hash.
2. Confirm the hash matches the release notes **and** a second independent
   source (for example, F-Droid's published hash or an organisation's mirror).
3. Confirm the signing certificate (needs `apksigner` from the Android SDK):
   `apksigner verify --print-certs kengele.apk` and compare the SHA-256
   certificate digest with the published fingerprint.
4. Only then install. If anything mismatches, delete the file and fetch it
   from another channel.

User-facing strings for the app and install pages, in English and Swahili
(draft; Swahili translators review before release):

| English | Kiswahili |
|---|---|
| Verify before you install. | Thibitisha kabla ya kusakinisha. |
| Compare the fingerprint shown here with one from a second source. | Linganisha alama hii na ile kutoka chanzo kingine cha kuaminika. |
| Update available. Install it from F-Droid. | Sasisho linapatikana. Lisakinishe kutoka F-Droid. |
| This copy could not be verified. Do not use it for a real watch. | Nakala hii haijathibitishwa. Usitumie kwa ulinzi wa kweli. |

## 7. Update channel

- **Primary channel: the F-Droid client.** F-Droid notifies the user and
  installs updates. Kengele itself never phones home: per
  [design.md](design.md), Altradits runs no servers, so there is deliberately
  no in-app updater, no update check and no analytics ping. An app that checks
  for updates over the network would add the traffic-analysis surface the
  threat model (§4.4) already worries about.
- **Continuity:** F-Droid enforces signature continuity (same release key) and
  monotonic `versionCode`, which blocks silent downgrades to a vulnerable
  build.
- **Sideload path:** users who cannot reach F-Droid install the APK from a
  trusted organisation or guardian and verify it per §6. Release hashes are
  published in the GitHub release notes and mirrored by partner organisations,
  so one removed page does not remove every copy of the hash.
- **Store removal (A1/A9):** if Kengele is removed from a store, installation
  continues through the sideload path with §6 verification. Removal must be
  announced publicly with new hash locations; unverified copies circulating
  after a removal are treated as untrusted.

## 8. Done checklist for this issue

- [x] This plan, reviewed against the threat model (§2).
- [x] `.github/workflows/reproducible-apk.yml` builds twice and compares
  hashes, and skips cleanly until `android/` exists (#15).
- [ ] Independent second rebuild matches (needs #15's app; future work).
- [ ] F-Droid submission (explicitly out of scope; future issue).
