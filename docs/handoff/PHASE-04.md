# Phase 4 — Multitrack scratchpad engine (real overdubbing)

**Status (2026-07-30): core mixing math only, this slice.** Matching the
plan's own instruction elsewhere ("host-testable C++ lib first, then
JNI-wrapped") applied here too: this pass delivers the pure `Track`/`Clip`
data model and mixing/punch-in logic, fully tested, with **no engine
integration (no real-time playback mode using this yet), no JNI wrapping,
no WAV export, no JVM reference mixer, and no UI.** All of that is still
ahead — see "What's left" below.

## What shipped

**`dsp/track_mixer.{h,cpp}`** (new, host-tested):

- **`Clip`**: a source `buffer` (mono f32, shared, never mutated) plus
  `startFrame` (position on the track's timeline), `bufferOffsetFrames`
  (in-point within `buffer`), `lengthFrames` (how much plays). Trimming a
  clip is pure metadata — no audio copy — which is what makes `punchIn()`
  cheap.
- **`Track`**: a list of `Clip`s plus `gain`/`muted`/`soloed`.
- **`mixTracks(tracks, startFrameInclusive, endFrameExclusive)`**: mixes
  into a buffer covering exactly that frame range. Overlapping clips
  within a track sum; solo semantics are "if any track is soloed, only
  soloed tracks play, and a soloed track's own mute is ignored" (the
  common DAW convention — not specified by the plan, documented as a
  judgment call since a different reading would break sample-identity
  against whatever the eventual JVM reference mixer assumes). **This one
  function is meant to back both real-time playback (called
  chunk-at-a-time from `onAudioReady`, once that integration lands) and
  offline mixdown (called once with `[0, totalFrames)`)** — deliberately
  not two independently-written paths that could drift apart, per the
  plan's own "same engine path" principle applied here.
- **`punchIn(existingClips, insertClip)`**: returns a new clip list with
  `insertClip` punched in — overlapping existing clips are trimmed (or
  dropped, if entirely inside the punched range) via metadata only, clips
  outside the range are untouched, and a clip straddling both edges splits
  into a head fragment and a tail fragment.

**`host/test_track_mixer.cpp`**: 15 GoogleTest cases, most notably
`ChunkedMixingMatchesWholeBufferMixing` — mixes the same tracks in
irregular 3-frame chunks vs. one whole-buffer call and asserts
byte-identical output. This is the property real-time playback and offline
mixdown both depend on; if it ever breaks, that's the first thing to
distrust before assuming either playback or mixdown is wrong.

**Verified on device (2026-07-30)**: no desktop compiler exists in this
environment (same constraint as every phase before this one) — cross-compiled
a standalone verification binary directly with the NDK's
`aarch64-linux-android30-clang++` and ran it via `adb shell`, mirroring the
same technique used for `spsc_ring_buffer`/`scene` in Phase 1. All 13
checks passed, including chunked-vs-whole-buffer identity and a full
punch-in → mix round trip (a loud clip punched into the middle of a quiet
one reads quiet/loud/quiet exactly as expected on playback).

## What's left for Phase 4 (not started)

- **Engine integration**: a new `EngineMode` (e.g. `MultitrackPlaying`)
  reusing the existing `Scene`/`ScenePublisher` double-buffering pattern
  but publishing `std::vector<Track>` instead of a single buffer, with
  `onAudioReady` calling `mixTracks()` chunk-at-a-time. Deliberately
  additive — the existing single-buffer `Scene`/`Playing` mode (used by
  Phase 1/2/3's verification playback, all tested this session) stays
  exactly as-is, not replaced.
- **Punch-in recording integration**: wiring `armRecording()`'s existing
  machinery to produce a `Clip` at a specified position, then calling
  `punchIn()` against the target track's existing clips to commit it.
- **Offline mixdown to WAV**: a function that calls `mixTracks(tracks, 0,
  totalFrames)` once and writes a standard WAV file — needs a WAV encoder
  (none exists in this codebase yet; likely small enough to hand-write
  rather than pull in a dependency for one file format).
- **JVM reference mixer**: a second, independently-written implementation
  of the same mixing logic in Kotlin, for the phase's actual Done
  criterion ("exported WAV is sample-identical to a JVM reference mixer
  given the same clip list"). Given the module layout's `:core:domain` is
  described as pure-JVM logic including "clipEngine," and this phase
  genuinely needs it now (not front-loaded speculatively), creating
  `:core:domain` now — rather than waiting for Phase 5, which the plan
  names as the module's origin phase — is likely the right call, but
  hasn't been done yet.
- **UI**: gain/mute/solo controls, punch-in triggering, track list — Phase
  10 territory per the plan's own phase table ("Scratchpad product UI"),
  though a diagnostics-screen-style verification harness (matching every
  prior phase's approach) will likely land before that.

## Known risks — check these first

1. **Solo semantics are a judgment call, not verified against any
   reference.** If the eventual JVM reference mixer encodes different
   solo/mute interaction (e.g. mute always wins even under solo), the
   cross-validation Done criterion will fail loudly and specifically on
   solo+mute test cases — that's the first thing to check, not a sign the
   core summing logic is broken.
2. **No output clipping/limiting in `mixTracks()`.** Deliberate — a
   reference mixer almost certainly wouldn't add one either, and adding
   one here would need to be replicated exactly in the JVM reference to
   keep sample-identity. If summed tracks clip audibly once this is wired
   to real playback, that's a product decision to make deliberately later
   (e.g. a limiter at the very end of the signal chain), not something to
   quietly bolt into this function.
3. **`punchIn()` is untested against negative-frame edge cases beyond what
   `test_track_mixer.cpp` covers** (clips starting at negative
   `startFrame`, e.g. from `PunchInTrimsClipThatExtendsAfterThePunch`,
   work correctly in that specific test, but a punch range itself starting
   at a negative frame, or a zero-length insert clip, aren't explicitly
   exercised).

## What Phase 4's next slice assumes

- `dsp/track_mixer.h`'s `Clip`/`Track` structs are the data model engine
  integration, mixdown, and the JVM reference mixer should all build on —
  changing the shape of `Clip` (e.g. adding fades) should extend, not
  replace, `bufferOffsetFrames`/`lengthFrames`'s "metadata-only trimming"
  property, since punch-in's whole cheapness depends on it.
- The chunked-vs-whole-buffer identity test is the thing to run first
  after any future change to `mixTracks()`, before trusting either
  real-time playback or offline mixdown against it.
