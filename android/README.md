# Kengele Android app (skeleton, issue #15)

> **Not for real use.** No security review yet, transports are stubbed, and
> nothing leaves the phone. See [threat-model.md](../docs/threat-model.md).

The activist app: guardian setup (public keys only), watch window, PIN entry
with a duress PIN, check-ins and a panic stub. Messages use the Kotlin port of
the v1 format ([message-format.md](../docs/message-format.md)); relay/SMS
sending, iOS and Play Store are out of scope.

## Setup

You need JDK 17, the Android SDK (platform 34, build-tools) and Gradle 8.7:

```
cd android
gradle :app:assembleDebug           # builds app/build/outputs/apk/debug/app-debug.apk
gradle :app:testDebugUnitTest       # local unit tests, no device needed
```

There is no `gradlew` wrapper jar vendored yet; run `gradle wrapper
--gradle-version 8.7` once with network access to create it. CI
([android.yml](../.github/workflows/android.yml)) installs the same toolchain
and runs both commands above.

## Layout

- `app/src/main/java/com/altradits/kengele/message/` — Kotlin port of
  `internal/message`: `KengeleMessage` (format), `Nip44`, `Secp256k1`
  (BIP-340, plain JDK), `ChaCha20`, `Hkdf`. No crypto dependencies.
- `pin/` — real + duress PIN, salted/stretched hashes only.
- `guardian/` — guardian keys only; no name field exists.
- `watch/` — watch window, same validation as Go `schedule.Window`.
- `checkin/` — check-in/duress routing; sender is a stub.
- `panic/` — panic trigger stub (deliberate gesture still to come).
- `store/` — encrypted on-device storage (EncryptedSharedPreferences).
- Screens: `MainActivity`, `GuardianSetupActivity`, `WatchWindowActivity`,
  `PinSetupActivity`, `PinEntryActivity`, `CheckInResultActivity`.
- `app/src/test/` — unit tests, including the duress UI-equality test.

## Shared test vectors

`internal/message/testdata/vectors.json` is owned by the Go package: Go
generates and pins it (`vectors_test.go`), and the Kotlin tests read the same
file, so the port cannot drift from the format. It covers the golden
end-to-end vector, the NIP-44 spec vector, the HKDF/ChaCha20 schedules and the
constant table.

## Security notes (threat model)

- Duress PIN shows the identical success screen and quietly seals a duress
  check-in instead (threat model §4.1); `PinTest` and `CheckInManagerTest`
  assert full UI-state equality.
- Guardians are public keys only, encrypted at rest (§4.3, §8). No names,
  numbers or photos exist anywhere in the app or tests.
- The activist types the place; there is no GPS anywhere (§4.4).
- Every message is the same 228 bytes / 304 characters whatever the kind, so
  carriers cannot tell kinds apart (§4.1, §4.2); tests pin the lengths.
- PINs and keys are hashed/encrypted at rest and never logged (§2).
- Reinstall means fresh keys: a restored activist key without its sequence
  counter is dropped and replaced, so the activist re-enrolls with the
  guardians and the old watch fails safe to alarm; every fresh key resets
  the counter.

## Still stubbed

Transports (Nostr #8, SMS #9), the power-button panic gesture, reminders and
background timers, quick-hide, the guardian app, reproducible builds (#17)
and F-Droid distribution. All user-facing strings ship in English and Swahili
(`res/values/strings.xml`, `res/values-sw/strings.xml`).
