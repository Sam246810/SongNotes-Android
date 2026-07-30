# Phase 4 — Multitrack scratchpad engine (real overdubbing)

**Status (2026-07-30): core mixing math + real-time engine integration,
verified end-to-end on device.** Matching the plan's own instruction
elsewhere ("host-testable C++ lib first, then JNI-wrapped") applied here
too: the first slice delivered the pure `Track`/`Clip` data model and
mixing/punch-in logic; this pass wires it into the real-time engine —
`EngineMode::MultitrackPlaying`, the JNI bridge, and the Kotlin
facade — and proves it actually plays back correctly on the physical
device with zero xruns. **Still not started: punch-in recording
integration, WAV export, JVM reference mixer, and product UI.** See
"What's left" below.

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

**Verified on device (2026-07-30, host-side mixing math)**: no desktop
compiler exists in this environment (same constraint as every phase before
this one) — cross-compiled a standalone verification binary directly with
the NDK's `aarch64-linux-android30-clang++` and ran it via `adb shell`,
mirroring the same technique used for `spsc_ring_buffer`/`scene` in Phase 1.
All 13 checks passed, including chunked-vs-whole-buffer identity and a full
punch-in → mix round trip (a loud clip punched into the middle of a quiet
one reads quiet/loud/quiet exactly as expected on playback).

**Engine integration shipped (2026-07-30)**:

- **`EngineMode::MultitrackPlaying`** (new): `Scene` now carries either
  `playbackBuffer` (existing single-buffer mode) or `multitrack` (a
  `shared_ptr<const vector<Track>>`) — mutually exclusive, same
  double-buffered publish pattern as before, no new publisher needed.
- **`NativeAudioEngine::startMultitrackPlayback()`**: computes total
  duration as the furthest clip end across all tracks (deliberately
  ignoring mute — a muted track still determines how long playback runs,
  matching what a real DAW's transport would do), publishes the Scene,
  reuses `mPlaybackCursor`/`stopPlayback()` from the existing single-buffer
  path (mutually exclusive modes, so no separate cursor field).
- **`onAudioReady`'s new branch**: calls `dsp::mixTracksInto()`
  chunk-at-a-time into a persistent pre-sized `mMultitrackScratch` buffer —
  no allocation on the RT thread. Caught and fixed one bug before it ever
  ran on device: the first draft clamped the mix length against `remaining`
  and `numFrames` but not against the scratch buffer's own size, a latent
  overrun; fixed with a three-way `std::min`.
- **JNI bridge** (`nativeStartMultitrackPlayback` in `jni_bridge.cpp`) and
  **Kotlin facade** (`AudioEngine.startMultitrackPlayback(List<MultitrackTrackSpec>)`
  in `AudioEngine.kt`): first slice is **exactly one clip per track** —
  `dsp::Track` natively supports multiple clips, but the JNI marshaling
  (parallel arrays keyed by track index) doesn't expose that yet. Documented
  in both the JNI comment and `MultitrackTrackSpec`'s kdoc as a known,
  deliberate simplification, not an oversight.

**Verified on device (2026-07-30, real-time engine integration)**: added a
"Multitrack playback smoke test" section to `DiagnosticsScreen.kt` — three
synthetic tracks (440Hz from frame 0, 660Hz staggered in 0.5s, and a
**muted** 220Hz track that's the longest of the three at 2.5s) played via
`AudioEngine.startMultitrackPlayback`. Ran on the physical device (adb
screenshot + uiautomator-dump-based tap, same workflow as every prior
phase): **PASS** — observed total frames 120000 (matches the 2.5s muted
track's length exactly, confirming mute doesn't affect the duration
calculation), cursor advanced cleanly to 120000 and `isPlaying` dropped to
false on its own (no manual stop needed), **xRun count 0 → 0** across the
whole playback, and a `logcat` check afterward showed no Oboe warnings, no
`AndroidRuntime` errors, no crashes. (Audible confirmation of "two tones,
staggered, no third tone" is a manual-listening claim built into the smoke
test's own result text — not separately re-verified by a human in this
pass, since the automated frame-accounting and xrun checks are what would
catch a real mixing bug; noted here for honesty, not glossed over.)

## What's left for Phase 4 (not started)

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
4. **The JNI bridge only supports one clip per track.**
   `nativeStartMultitrackPlayback`'s parallel-array marshaling
   (`clipBuffers[i]`/`clipStartFrames[i]` per track) has no way to express
   multiple clips on one track — real punch-in recording (which produces
   exactly that: several clip fragments per track) will need this widened
   before it can drive real-time playback, even though `dsp::Track` and
   `mixTracksInto()` already support it natively. Widen by changing the
   marshaling shape (e.g. a flat clip array plus a per-track clip-count
   array) rather than changing `dsp::Track` itself.
5. **The multitrack smoke test's "you should have heard two staggered
   tones, not three" claim is unverified by a human ear in the pass that
   added it** — the automated checks (frame accounting, clean auto-stop,
   zero xruns, clean logcat) are strong evidence the mixing itself is
   correct, but they can't catch every possible audible-mixing bug (e.g. a
   channel-interleaving mistake that garbles the tone without changing
   frame counts). Worth an actual listen next time the device is in hand.

## What Phase 4's next slice assumes

- `dsp/track_mixer.h`'s `Clip`/`Track` structs are the data model engine
  integration, mixdown, and the JVM reference mixer should all build on —
  changing the shape of `Clip` (e.g. adding fades) should extend, not
  replace, `bufferOffsetFrames`/`lengthFrames`'s "metadata-only trimming"
  property, since punch-in's whole cheapness depends on it.
- The chunked-vs-whole-buffer identity test is the thing to run first
  after any future change to `mixTracks()`, before trusting either
  real-time playback or offline mixdown against it.
