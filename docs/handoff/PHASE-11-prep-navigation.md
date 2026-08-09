# Phase 11 prep — real home screen, dev screens gated out (planned, not yet implemented)

**Status (2026-08-09): scoped and approved, implementation not started.** This
is a decision record for a gap found while discussing what Phase 11
("Hardening + Play release") needs to cover — not a completed phase, and not
formally part of Phase 11 itself (see "Why this isn't blocking on Phase 11"
below).

## The gap

`MainActivity.kt`'s navigation is a plain `Screen` enum toggle (`Screen {
Diagnostics, Wizard, Manual, TapAlong, Scratchpad, Songs, SongEditor, Auth,
Piano }`, no navigation library — deliberate per `docs/handoff/PHASE-00.md`'s
"don't front-load" note). **`Screen.Diagnostics` is the app's default/launch
screen in every build**, debug and release alike. That screen renders a
sign-in/sign-out row, a "Sync now" button, six navigation buttons with no
visual distinction between real product screens ("Open songs," "Open
scratchpad," "Open piano") and dev-only calibration tools ("Open calibration
wizard," "Open manual calibration," "Open tap-along calibration"), followed
by the full `DiagnosticsScreen` — capability readouts, a test-tone button,
and every smoke-test section built up since Phase 0.

Nothing in `docs/PLAN.md` or any earlier `docs/handoff/PHASE-*.md` ever
addressed removing or gating this before a real release — it's a genuine
gap, not a deferred, already-tracked task.

**A second problem surfaced while scoping the fix**: Scratchpad and Piano —
both real, already-shipped product features (Phase 4/10 multitrack
recording, Phase 9 on-screen piano) — have **zero navigation entry points
outside the diagnostics screen**. Simply gating Diagnostics out of release
builds without addressing this would make both completely unreachable to a
real user.

## The plan

- **`Screen.Songs` becomes the default/home screen in both debug and release
  builds** — one behavior to reason about, and it means the real launch path
  gets exercised during normal development too. (Confirmed safe:
  `SongListScreen.kt`, Phase 5.5's screen, is `SongRepository`-backed
  (Room + SQLCipher) with zero auth dependency — it already works fully
  standalone without sign-in.)
- The sign-in/sign-out + "Sync now" row moves out of the old `Diagnostics`
  branch (real users will never reach that branch) into the new `Songs`
  branch, so real users still have access to account controls from their
  actual home screen.
- `SongListScreen.kt` gains `onOpenScratchpad`/`onOpenPiano` callbacks wired
  to two new top-bar buttons (same visual weight as the existing "New song"
  button) — real, permanent entry points, not routed through Diagnostics.
  Its existing `onDone`/"Done" button is dropped entirely: Songs is home now,
  there's nothing to go "back" to.
- One `BuildConfig.DEBUG`-gated button on the Songs screen — "Diagnostics" —
  is the **only** remaining way to reach `Screen.Diagnostics` (and
  transitively `Wizard`/`Manual`/`TapAlong`, whose own nav buttons still live
  inside the unchanged `DiagnosticsScreen` UI). Nothing else in a release
  build ever sets `screen` to one of those four values, so they're
  unreachable to a real user even though the build is release-configured.

## Deliberate choice: runtime guard, not a debug-only source-set split

Two ways to achieve "a real user never sees this" were considered:

1. **Move `DiagnosticsScreen.kt`/`CalibrationWizardScreen.kt`/
   `ManualCalibrationScreen.kt`/`TapAlongCalibrationScreen.kt` into a
   `app/src/debug/java/...` source set** — genuinely absent from the release
   APK's compiled code, verifiable today via `apkanalyzer`. More invasive:
   requires file moves and a small stub composable so `MainActivity.kt`
   (which lives in `src/main`, compiled for both variants) still compiles
   against a consistent API in release builds.
2. **A single `BuildConfig.DEBUG` runtime guard** around the one "Diagnostics"
   entry-point button. Far less code churn. Fully satisfies "a real user can
   never reach these screens" today — but the compiled code for all four
   files still ships inside the release APK (recoverable via decompilation)
   until Phase 11 turns R8 on, at which point R8's dead-code elimination
   treats `BuildConfig.DEBUG` as a compile-time-constant `false` in a release
   build and strips the unreachable branch for free — reaching the same end
   state as option 1 without ever needing the source-set split.

**Chosen: option 2.** Given Phase 11 will enable R8 anyway and finish the job
automatically, the extra invasiveness of a source-set split now buys nothing
that R8 won't deliver shortly, for a problem (literal code presence in an
unsigned/unreleased debug artifact) that isn't the actual risk being guarded
against (a real user navigating into dev tooling).

## Why this isn't formally part of Phase 11

This is pure `:app`-module navigation/UI work, fully decoupled from Phase
11's actual scope (R8, baseline profile, Data Safety form, privacy policy,
internal testing track, manual device matrix). Blocking a small, independent
correctness fix behind a "hardening" phase that hasn't started yet — for a
problem a real user could hit *today* if this app were installed from a
release build — has no upside. Doing it now and noting it in `docs/PLAN.md`'s
Phase 11 row keeps the phase table honest without inflating Phase 11's own
scope.

## What's NOT done yet

Everything above is a decision record, not an implementation report. Still
to do, once implementation starts:

- `app/build.gradle.kts`: add `buildFeatures { buildConfig = true }` (not
  currently set for `:app` — `BuildConfig.DEBUG` isn't accessible yet).
- `MainActivity.kt`: default-screen change, `AccountRow` extraction, the
  `BuildConfig.DEBUG`-gated Diagnostics button, rewiring Scratchpad/Piano/
  Auth's `onDone` to `Screen.Songs`.
- `SongListScreen.kt`: signature change (drop `onDone`, add
  `onOpenScratchpad`/`onOpenPiano`), top-bar button swap.
- On-device verification of both a debug build (Diagnostics button present
  and fully functional) and a release-configured build (Diagnostics button
  absent, Scratchpad/Piano/sign-in still work) — no release `signingConfig`
  exists yet either, so verifying the release variant needs a temporary
  `signingConfig = signingConfigs.getByName("debug")` in the `release {}`
  block, clearly marked as a placeholder for Phase 11's real signing setup.
