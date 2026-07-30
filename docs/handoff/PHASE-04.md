# Phase 4 — Multitrack scratchpad engine (real overdubbing)

**Status (2026-07-30): the full punch-in loop works end to end on a real
device** — record a real take while existing tracks play audibly, splice
it in, play the combined result back. Four slices got here, each verified
on device before the next started: (1) the pure `Track`/`Clip` data model
and mixing/punch-in logic; (2) real-time engine integration
(`EngineMode::MultitrackPlaying`); (3) widening the JNI bridge to N clips
per track and exposing `dsp::punchIn` over JNI; (4) **real overdub
recording** — `armRecording()` now optionally mixes existing tracks into
the output during Recording, so a user can actually hear the song while
recording a new part onto it, matching the plan's own "same engine path"
principle (this reuses `dsp::mixTracksInto`, not a second mixing
implementation). **Still not started: WAV export, JVM reference mixer,
product UI, and a real "current project" state to replace the
diagnostics screen's throwaway in-memory track lists.** See "What's left"
below.

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
  in `AudioEngine.kt`): now supports **N clips per track** — the marshaling
  is flat, track-major arrays (`clipBuffers`/`clipStartFrames`/
  `clipBufferOffsetFrames`/`clipLengthFrames`) plus a `trackClipCounts`
  array saying how many consecutive flat entries belong to each track.
  (First shipped as exactly-one-clip-per-track; widened this pass — see
  the git history on `jni_bridge.cpp` if the old shape is ever relevant.)
- **`nativePunchIn`** (new JNI function, stateless) and **`AudioEngine.punchIn()`**
  (Kotlin facade): wraps `dsp::punchIn` so Kotlin can splice a newly
  recorded take into a track's existing clip list without reimplementing
  the trim/split logic — the actual splicing math has exactly one
  implementation (C++), not a Kotlin copy that could drift from it.

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

**Verified on device (2026-07-30, multi-clip + punch-in JNI)**: upgraded
the multitrack smoke test so one track carries **two clips with a silent
gap** (440Hz, gap, then a C5 tone on the same track), plus a new
"Punch-in smoke test" section that calls `AudioEngine.punchIn()` on a real
device and asserts the exact resulting clip shape and sample values (not
just "didn't crash"). First run of the punch-in test **crashed the app**
(`ArrayIndexOutOfBoundsException` at `AudioEngine.kt:167`) — the Kotlin-
side output-array capacity formula (`existingClips.size + 1`) assumed a
punched clip could only split into 2 fragments total, but a single
existing clip straddling both edges of the insert splits into a head *and*
a tail (2 fragments from 1 input) plus the insert clip itself (3 total from
1 existing clip) — exactly what the test's own inputs (a 1000-frame clip,
punched in the middle) triggered. Fixed by correcting the capacity formula
to `existingClips.size * 2 + 1` (the true worst case: every existing clip
splits into 2) **and** hardening the native side to return the count it
actually wrote rather than the true (possibly larger) result size, so a
future capacity miscalculation elsewhere degrades to silent truncation
instead of an out-of-bounds crash. Re-ran after the fix: both smoke tests
**PASS** — multi-clip playback again showed correct total frames (120000)
and 0 xruns; punch-in returned exactly 3 clips
(`[0,300)`/`[300,700)`/`[700,1000)`) with sample values reading
quiet/loud/quiet as expected, and a `logcat` check showed no crash. Left
in here deliberately as a reminder that "the math is right" (host
GoogleTest already proved `dsp::punchIn` itself) doesn't mean "the
boundary around the math is right" — this was a marshaling bug, not a
`dsp::punchIn` bug.

**Overdub recording shipped (2026-07-30)**:

- **`NativeAudioEngine::armRecording()`** gained two new (optional,
  default-empty) params: `backingTracks` and `backingTracksStartFrame`.
  Empty `backingTracks` reproduces plain click-only recording exactly —
  this is additive, not a new recording mode. When non-empty, `armRecording`
  publishes a `Scene` with `multitrack` set (same publish-before-mode-store
  handoff pattern used everywhere else in this class) and stores
  `mBackingTracksStartFrame`/`mBackingTracksTotalFrames` before enqueuing
  the Arm command — visible to the RT thread via the same SPSC-queue
  release/acquire pair the Command payload itself already relies on, not a
  new synchronization mechanism.
- **`onAudioReady`'s Armed/Recording output branch** now mixes backing
  tracks in (additively, `+=`, same as the click) alongside the existing
  metronome. The sync point: project-timeline frame
  `mBackingTracksStartFrame` is defined to land exactly at this take's
  downbeat (`mDownbeatFrame`) — the same anchor `headSkipFrames` already
  trims the take's own file frame 0 to, so a caller's `backingTracksStartFrame`
  argument is directly reusable as the resulting take's `Clip.startFrame`.
  Silent before the downbeat and once the backing tracks run out; unlike
  `MultitrackPlaying`, this never auto-stops the recording.
- **JNI bridge**: `nativeArmRecording` widened to also carry the flat,
  track-major backing-track marshaling (extracted into a shared
  `parseFlatTracks` helper, reused by `nativeStartMultitrackPlayback` too
  rather than duplicated).
- **Kotlin facade**: `AudioEngine.armRecording()` gained matching
  `backingTracks: List<MultitrackTrackSpec> = emptyList()` and
  `backingTracksStartFrame: Long = 0L` params — existing call sites
  (`VerificationTakeRecorder`, `DiagnosticsScreen`'s plain record/playback
  section) needed no changes, since both new params default to "no backing
  tracks." The flattening logic itself was extracted into a private
  `flattenTracks()` helper shared with `startMultitrackPlayback()`.

**Verified on device (2026-07-30, overdub recording end to end)**: added
an "Overdub + punch-in, end to end" section to `DiagnosticsScreen.kt` —
arms a real recording with 2 synthetic backing tracks (440Hz from frame 0,
660Hz staggered in 0.5s) audible during capture, records ~2s of real mic
input, reads the resulting take back from disk, splices it onto an
initially-empty third track via `AudioEngine.punchIn`, then plays all 3
tracks back together via `startMultitrackPlayback`. Ran on the physical
device: recorded 98112 frames (2.04s — matches the ~2.0s target),
`punchIn` onto an empty track correctly produced exactly 1 clip, combined
3-track playback started successfully, **xRun count stayed at 0** through
the whole recording + playback sequence, and a `logcat` check afterward
showed no crash. This is the first test in Phase 4 that exercises the
*entire* loop end to end (not one piece in isolation) — real mic capture,
simultaneous backing-track playback, disk round-trip, splicing, and
combined playback all in one pass. Audible correctness (right tones at
the right times) is, as with the other multitrack tests, a manual-listening
claim embedded in the result text, not separately confirmed by ear in this
pass.

## What's left for Phase 4 (not started)

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
4. **The multitrack smoke test's audible-correctness claims are unverified
   by a human ear** — the automated checks (frame accounting, clean
   auto-stop, zero xruns, clean logcat) are strong evidence the mixing
   itself is correct, but they can't catch every possible audible-mixing
   bug (e.g. a channel-interleaving mistake that garbles a tone without
   changing frame counts). Worth an actual listen next time the device is
   in hand.
5. **Still no engine- or app-level "current project" state exists.** The
   engine and JNI layers are now fully capable of overdub recording +
   splicing (verified end to end), but every call site — including the new
   "Overdub + punch-in" diagnostics section — still constructs its own
   throwaway `List<Track>` inline rather than reading from/writing back to
   any shared representation of "the tracks in this song." This is
   explicitly *not* a persistence gap (nothing needs to survive a process
   restart yet) — it's that no single owner of "the current in-memory track
   list" exists at all. This is now the most load-bearing remaining gap for
   turning the verified engine capability into an actual usable feature:
   whoever builds the real punch-in UI needs a place to hold and mutate
   that list (naturally Kotlin-side, given `AudioEngine`'s multitrack/punch-in
   methods are all already stateless per-call from the engine's own
   perspective).
6. **The metronome click is not toggleable during an overdub take.**
   `onAudioReady`'s Armed/Recording branch mixes the backing tracks in
   *alongside* the existing count-in/metronome click, unconditionally —
   there's no way for a caller to ask for backing-tracks-only (no click)
   once past the count-in. Many real DAWs let a user disable the click once
   they have a backing track to play along with; this wasn't in scope for
   "prove overdub recording works" but will likely matter for the eventual
   product UI.
7. **`backingTracksStartFrame` sync is trusted, not independently
   verified.** The alignment (`mStreamFrameCounter - mDownbeatFrame +
   backingTracksStartFrame` landing backing-track frame 0 exactly at the
   take's downbeat) is reasoned from the same frame-counter math Rule C's
   calibration-offset trimming already relies on and elsewhere in this
   codebase, but — unlike Rule C's offset, which was validated by measuring
   actual round-trip latency before/after correction — nothing has measured
   whether the backing tracks a user *hears* during a real take are
   sample-accurately in sync with where the take actually gets punched in.
   The end-to-end smoke test proves the pieces connect (recording runs,
   splicing happens, playback works) but not frame-accurate sync; that
   would need either a loopback-capture measurement (like calibration's
   own sweep-based approach) or careful manual listening for drift.

## What Phase 4's next slice assumes

- `dsp/track_mixer.h`'s `Clip`/`Track` structs are the data model engine
  integration, mixdown, and the JVM reference mixer should all build on —
  changing the shape of `Clip` (e.g. adding fades) should extend, not
  replace, `bufferOffsetFrames`/`lengthFrames`'s "metadata-only trimming"
  property, since punch-in's whole cheapness depends on it.
- The chunked-vs-whole-buffer identity test is the thing to run first
  after any future change to `mixTracks()`, before trusting either
  real-time playback or offline mixdown against it.
