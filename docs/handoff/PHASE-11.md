# Phase 11 — Hardening + Play release

**Status (2026-08-10): R8 shrinking and baseline profile generation are
done and verified on-device. Privacy policy and Data Safety form are
drafted (not submitted). Real release signing, the internal testing track,
and a multi-device matrix are still open — see "What's NOT done" below.**

The navigation/dev-screen-gating gap found while scoping this phase was
already fixed and documented separately in
`docs/handoff/PHASE-11-prep-navigation.md` before this phase's own work
started.

## R8 / code shrinking

`app/build.gradle.kts`'s `release` build type now has `isMinifyEnabled =
true` and `isShrinkResources = true`, with rules in the new
`app/proguard-rules.pro`.

**Why this needed real research, not just flipping a flag:** this app has
three areas that are notorious for silently breaking under R8 if the
right keep rules aren't in place, and a broken JNI binding in particular
would have taken down the *entire* app (it's a native-audio-engine app):

- **JNI** (`core:audio`'s `AudioEngine`/`Calibration`) — binds to its C++
  side via classic implicit JNI name mangling
  (`Java_com_songnotes_core_audio_AudioEngine_nativeCreate`, not
  `RegisterNatives`), confirmed by reading `jni_bridge.cpp`/
  `calibration_jni.cpp` directly. Renaming either class or any `nativeXxx`
  method breaks every native call with `UnsatisfiedLinkError`. Covered by
  `-keepclasseswithmembernames class * { native <methods>; }` (redundant
  with `proguard-android-optimize.txt`'s own default, kept explicit given
  the stakes).
- **kotlinx.serialization** (`SongRow`, `UserKeysRow` in `:core:data`) —
  needs the officially documented keep rules for the compiler-generated
  `$serializer` companions, scoped to `com.songnotes.**` so any future
  `@Serializable` class is covered automatically.
- **WorkManager** (`SongSyncWorker`) — instantiated by class name via
  reflection; kept explicitly.
- Defensive rules for **BouncyCastle** (JCE-style provider lookup by
  string, not direct reference — a plain JAR with no consumer rules of its
  own) and **SQLCipher** (wraps its own native SQLite build).
- `-dontwarn` for Ktor/supabase-kt's Kotlin Multiplatform classes not
  present on Android (Darwin/JS engines etc.) — real "missing class" notes,
  not actual problems.

**Verification, on a physical device (Samsung Galaxy Z Fold, `RFCX70MEMRX`,
Android 16/API 36):**

- `mapping.txt` confirms `AudioEngine`, `Calibration`, `SongSyncWorker`,
  `SongRow`, `UserKeysRow`, and its `$$serializer` all kept their exact
  original names — the keep rules worked as intended, not just "the build
  didn't fail."
- Installed the R8-shrunk release APK and exercised, live: native
  multitrack **playback** (playhead moving, live position counter, no
  crash), a full **record → count-in → live capture → stop → punch-in
  splice → auto-persist** cycle (committed a real take, confirmed via the
  "Recorded 41.1s onto track 1" status line and the updated timeline), the
  **Piano** screen's native voice rendering (sample loading + key taps),
  Room/SQLCipher data loading correctly (existing `Demo` song + Scratchpad
  project intact), a Supabase **auth session silently restoring** from
  persisted storage (exercises JSON/JWT deserialization + the Ktor network
  stack), and a Room **write** via `SongEditorScreen` (an incidental blank
  song got created and cleaned up during navigation testing — evidence the
  editor UI loads fine too). No crash, no `UnsatisfiedLinkError`, no
  exception, across any of it (`logcat` checked after each step).
- Release APK size: **~50.5 MB**, down from an unshrunk **~119 MB** debug
  build (not a fully apples-to-apples comparison, since debug/release
  differ in other ways too, but directionally confirms shrinking is doing
  real work).
- Full unit test suite (`./gradlew test`) still green across all modules.

## Baseline profile

New `:baselineprofile` module (`com.android.test` + `androidx.baselineprofile`
1.4.1, `androidx.benchmark:benchmark-macro-junit4` 1.4.1,
`androidx.test.uiautomator` 2.4.0) — versions confirmed against Google's
Maven metadata directly rather than guessed, since a wrong version here
just fails Gradle sync.

`BaselineProfileGenerator.kt` drives the one journey that matters most for
perceived speed: cold launch → Songs (home) → open Scratchpad (the
heaviest screen — native engine init, Compose timeline/waveform rendering,
an existing multitrack project loading off disk). Piano and the editor
screens are lighter and weren't included; this profile covers the path
nearly every session takes, not every screen that exists.

Generated via `./gradlew :app:generateBaselineProfile` against the
connected physical device (`useConnectedDevices = true` — this repo has no
existing Gradle Managed Device setup, and every other on-device
verification here already goes through a real device). Ran clean (0
failed). Result: a 24,626-line profile at
`app/src/release/generated/baselineProfiles/baseline-prof.txt`, 629 lines
of which are this app's own classes (`MainActivity`, its `Screen`
navigation, etc.) — confirmed the journey actually captured our real hot
path, not just generic framework boilerplate.

Verified `:app:assembleRelease` picks it up: `assets/dexopt/baseline.prof`
and `baseline.profm` are present in the built release APK (checked via
`unzip -l`), plus `androidx.profileinstaller:profileinstaller` was added
as a normal `:app` dependency so the profile installs itself via
`ProfileInstaller` on devices that don't get it through Play's own
server-side processing.

An informational (non-blocking) build warning noted no rules were
generated for the separate, optional "startup profile" tier (would need a
test explicitly setting `includeInStartupProfile = true`) — left as-is;
this is a finer-grained enhancement, not a correctness gap.

## Privacy policy + Data Safety form (drafted, not submitted)

Both written by reading the app's actual code rather than from a generic
template — see each file's own "how this was written" section for exactly
what was checked:

- **`docs/PRIVACY_POLICY.md`** — covers what's collected (account email if
  you sign in; song content, but end-to-end encrypted client-side before
  sync via `ContentEnvelope`, confirmed the Supabase-side `SongRow.content`
  field is genuinely ciphertext, not plaintext with a flag); what's
  explicitly *not* collected (mic audio never leaves the device — verified
  against `AudioEngine`/`RecordingForegroundService`; no location/
  contacts/camera — verified against the declared `AndroidManifest.xml`
  permissions; no analytics/ads/tracking SDKs — verified against the
  Gradle dependency graph); biometric handling (Keystore/BiometricPrompt
  only, raw biometric data never reaches the app); the Supabase
  subprocessor relationship. Has `[FILL IN ...]` placeholders for contact
  email and the hosting date — needs the user's own info before publishing.
- **`docs/DATA_SAFETY_FORM.md`** — a section-by-section mapping to Play
  Console's Data Safety questionnaire, with the same fact-checked answers.

**Real gap surfaced while drafting, not something to paper over:** Google
Play requires apps with account creation to also offer account deletion
(in-app or web, no "email support" workaround) — this app currently has
sign-out only, no delete-account path anywhere in the code. Flagged
prominently at the top of `DATA_SAFETY_FORM.md`. This is a genuine
pre-submission blocker, separate from anything else in this phase.

## What's NOT done

These need the developer's own Play Console account, business/legal
decisions, or physical hardware this session didn't have access to:

- **Real release signing.** `release { signingConfig }` still points at
  the temporary debug-signing placeholder from
  `docs/handoff/PHASE-11-prep-navigation.md`, put there only so a
  release-configured build could be installed and verified on-device at
  all. A real signing key/config is still needed before any Play upload.
- **Account deletion feature.** Surfaced above — needs actual
  implementation (an in-app action, or a web form) before Data Safety can
  be truthfully submitted.
- **Hosting and submitting the privacy policy / Data Safety form.**
  Drafted; publishing the policy URL and filling out the live Play Console
  form are account-holder actions.
- **Internal testing track.** Play Console publishing action, needs the
  developer's own account access.
- **Manual device matrix.** Every on-device verification this phase (and
  every phase before it) ran on one physical device, a Samsung Galaxy Z
  Fold. No second device was available to cross-check against.
