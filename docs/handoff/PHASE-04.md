# Phase 4 — Multitrack scratchpad engine (real overdubbing)

**Status (2026-07-30): Phase 4's own stated Done criterion is met and
verified on a real device** — "exported WAV is sample-identical to a JVM
reference mixer given the same clip list." Seven slices got here, each
verified before the next started: (1) the pure `Track`/`Clip` data model
and mixing/punch-in logic; (2) real-time engine integration
(`EngineMode::MultitrackPlaying`); (3) widening the JNI bridge to N clips
per track and exposing `dsp::punchIn` over JNI; (4) **real overdub
recording** — `armRecording()` now optionally mixes existing tracks into
the output during Recording, so a user can actually hear the song while
recording a new part onto it; (5) **offline mixdown to a real 32-bit float
WAV file**; (6) **a new `:core:domain` Gradle module holding a genuinely
independent JVM reference mixer**, cross-validated against the C++ engine
on-device — the two agree bit-for-bit; (7) **`MultitrackProject`**, the
single authoritative in-memory track list every call site now reads from
and writes back to, replacing the throwaway inline lists every earlier
diagnostics section built for itself — proven by refactoring the "Overdub
+ punch-in, end to end" section onto it and re-verifying on device.
**Still not started: product UI.** See "What's left" below.

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

**WAV export shipped (2026-07-30)**:

- **`dsp/wav_encoder.{h,cpp}`** (new, host-tested): `encodeWavFloat32(samples,
  sampleRate, channelCount)` — pure, allocating, no file I/O — encodes as
  32-bit float WAV (RIFF/WAVE, fmt tag 3 = `WAVE_FORMAT_IEEE_FLOAT`), the
  same layout libsndfile/soundfile write for float32 output. Chosen over
  16-bit PCM specifically because Phase 4's Done criterion is
  sample-identity against a JVM reference mixer — int16 quantization would
  add a rounding step two independent implementations could disagree on
  for reasons unrelated to whether the mixing math itself agrees.
  `writeWavFile(path, samples, sampleRate, channelCount)` is the thin
  file-writing wrapper actually used for export.
- **`host/test_wav_encoder.cpp`**: GoogleTest cases covering header field
  correctness (mono and stereo), exact sample round-tripping, the
  empty-input edge case, and that `writeWavFile`'s on-disk bytes match
  `encodeWavFloat32`'s in-memory bytes exactly.
- **Verified on device**: cross-compiled a standalone verification binary
  with the NDK's `aarch64-linux-android30-clang++` (needed `-static-libstdc++`
  this time — the device's system libc++ was missing a `basic_ifstream`
  symbol the statically-available one has; every prior Phase 1/4
  cross-compiled binary happened not to need `std::ifstream`, so this
  hadn't come up before) and ran it via `adb shell`. All 28 checks passed.
- **JNI + Kotlin**: `nativeExportMixdownToWav` (stateless, no engine handle
  needed — same pattern as `nativePunchIn`, since mixing + encoding are
  both engine-independent) and `AudioEngine.exportMixdownToWav(filePath,
  tracks, sampleRate)`. Computes `totalFrames` from the tracks' own clip
  end frames (same logic `startMultitrackPlayback` already uses), calls
  the allocating `dsp::mixTracks` (fine off the RT thread), encodes, writes.
- **Verified on device (app level)**: a new "WAV export smoke test"
  section in `DiagnosticsScreen.kt` mixes two overlapping synthetic tracks
  with a hand-computed expected result (`[1.0, 1.5, 1.5]`), exports via
  `AudioEngine.exportMixdownToWav`, then reads the written file back from
  app storage and checks BOTH the WAV header fields and the actual sample
  values against that hand computation. **PASS** on the physical device: a
  real 56-byte file (44-byte header + 12 bytes of data) with every header
  field correct and sample values matching exactly.

**JVM reference mixer shipped (2026-07-30) — Phase 4's Done criterion is
now actually met, not just possible**:

- **New Gradle module `:core:domain`** (`kotlin("jvm")`, no Android
  dependency) — created now rather than waiting for Phase 5 (the plan's
  own nominal origin phase for this module), since Phase 4 genuinely
  needed it for cross-validation and front-loading it a phase early cost
  nothing. Configured with `sourceCompatibility`/`jvmTarget` (not
  `kotlin { jvmToolchain(17) }` — that API triggers Gradle's toolchain
  auto-detection, which doesn't recognize this environment's `JAVA_HOME`
  as a usable JDK 17 candidate; matches the style the other modules
  already use). This is the **first code in the entire project runnable
  and testable without a physical device or an NDK cross-compile** —
  `./gradlew :core:domain:test` runs in seconds, no adb, no phone.
- **`ClipMixer.kt`**: `Clip`/`Track` data classes and a `mixTracks()`
  function — a genuinely independent implementation of the same algorithm
  `dsp::mixTracksInto` implements, written from the algorithm description
  (overlapping clips sum, gain scales, solo-overrides-mute) rather than
  translated line-by-line from the C++. One deliberate exception to
  "independent": it follows the **same iteration order** (tracks outer,
  clips inner, frame-by-frame accumulation) as the C++ version — not
  because that's the only valid order, but because IEEE 754 addition isn't
  associative, and matching order is what makes *exact* equality a
  meaningful comparison instead of one that needs an epsilon tolerance to
  paper over summation-order differences that have nothing to do with
  either implementation being wrong.
- **`ClipMixerTest.kt`**: 12 JUnit cases mirroring `test_track_mixer.cpp`'s
  coverage (independently written assertions, not ported) — single/multi
  track sums, overlap, gain, mute, solo (including solo-overrides-mute),
  windowed ranges, chunked-vs-whole-buffer identity, an already-spliced
  quiet/loud/quiet clip list, empty input, and a clip whose `lengthFrames`
  exceeds its buffer. **12/12 passing.**
- **`nativeMixTracks`** (new stateless JNI function, same pattern as
  `nativePunchIn`/`nativeExportMixdownToWav`) and
  `AudioEngine.mixTracksNative()`: returns the raw mixed samples with no
  WAV encoding involved, specifically so cross-validation can isolate "do
  the two mixing implementations agree" from "is the WAV encoding correct"
  (the latter already covered by the WAV export smoke test above).
- **Verified on device**: a new "JVM reference mixer cross-validation"
  diagnostics section mixes an identical 3-track scenario (overlapping
  clips, per-track gain, a clip with a gap on one track — deliberately
  exercising the arithmetic itself rather than mute/solo, whose simple
  conditionals are already covered identically by both implementations'
  own test suites) through both `AudioEngine.mixTracksNative` and
  `com.songnotes.core.domain.mixTracks`, and checks all three agree: each
  against a hand-computed expectation, AND the two implementations against
  each other, exactly (not an epsilon check). **PASS** on the physical
  device: `[0.5, 2.5, 3.0, 2.5, 2.5, 3.5, 1.5]` from all three sources,
  bit-for-bit identical between native and JVM.

**`MultitrackProject` shipped (2026-07-30) — the "current project" gap
(risk 5, above) is closed**:

- **`MultitrackProject.kt`** (new, in `:core:audio`): an immutable
  `data class MultitrackProject(val tracks: List<MultitrackTrackSpec>)`
  with reducer-style mutation methods (`addTrack`, `removeTrack`,
  `withTrackGain`/`Muted`/`Soloed`, `withPunchIn`, plus `play`/`armOverdub`/
  `exportToWav` as thin wrappers around the matching `AudioEngine` calls) —
  every mutation returns a new instance rather than changing one in place,
  the same pattern `EngineState`/`EngineCapabilities` already use for
  engine-observed state. Deliberately placed in `:core:audio`, not
  `:core:domain` — this is not the JVM reference mixer's data model
  (`:core:domain`'s `Clip`/`Track` exist only for cross-validating the
  mixing math independently) and punch-in recording is inherently a
  real-time engine operation, so this project model is tied to
  `AudioEngine`'s own `MultitrackTrackSpec`/`MultitrackClipSpec` types
  rather than requiring conversion back and forth. `withPunchIn` never
  reimplements splicing — it always calls `AudioEngine.punchIn` (the one
  C++-backed implementation), same reasoning as everywhere else in Phase 4.
- **`MultitrackProjectTest.kt`** (new): 8 JUnit cases covering every method
  that doesn't touch a real engine (`addTrack`, `removeTrack`, the
  per-track setters, `totalFrames` including the "mute doesn't shorten
  duration" case) — added `testImplementation(libs.junit)` to
  `core/audio/build.gradle.kts` for this, the first JVM unit test
  `:core:audio` has ever had. `withPunchIn`/`play`/`armOverdub`/
  `exportToWav` all construct or call a real `AudioEngine`, whose
  companion object loads a native library that doesn't exist in a plain
  JVM test process — those stay verified on-device, same as everything
  else that touches JNI in this project. **8/8 passing.**
- **Proven in real use, not just added unused**: refactored the "Overdub +
  punch-in, end to end" diagnostics section to build its 3-track setup via
  `MultitrackProject().addTrack(...).addTrack(...).addTrack()`, arm the
  overdub via `project.armOverdub(...)`, commit the take via
  `project.withPunchIn(...)`, and play the result via `project.play(...)`
  — replacing the hand-built-then-concatenated `backingTracks + MultitrackTrackSpec(...)`
  list construction that section used before. **Verified on device**: PASS,
  identical numbers to the pre-refactor run (97536 frames recorded, exactly
  1 spliced clip, `project.totalFrames` correctly reflecting the punched-in
  take, clean combined playback, 0 xruns) — the abstraction didn't change
  behavior, it just gave every future call site (a real punch-in UI,
  eventually) somewhere to hold state instead of reinventing it.

## What's left for Phase 4 (not started)

- **UI**: gain/mute/solo controls, punch-in triggering, track list — Phase
  10 territory per the plan's own phase table ("Scratchpad product UI"),
  though a diagnostics-screen-style verification harness (matching every
  prior phase's approach) will likely land before that.

## Known risks — check these first

1. **Solo semantics are a judgment call — now implemented identically in
   both mixers, but not cross-validated against each other for solo+mute
   specifically at the app level.** `ClipMixer.kt`'s `mixTracks()` encodes
   the same "solo overrides mute" convention as `dsp::mixTracksInto`, and
   both sides' own independent unit test suites (`test_track_mixer.cpp`,
   `ClipMixerTest.kt`) each directly assert it — but the on-device
   cross-validation section deliberately used a scenario with no solo/mute
   at all (see its own doc comment for why: isolating the arithmetic).
   If the two implementations' solo/mute conditionals were ever to drift
   apart, nothing currently would catch that specific case on-device — only
   each side's own tests, which by construction can't detect the other
   side disagreeing with them.
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
5. **RESOLVED (2026-07-30) — `MultitrackProject` is the single owner of
   "the current in-memory track list" now**, and the "Overdub + punch-in"
   diagnostics section is refactored onto it (see "What shipped" above).
   What's still genuinely open: (a) no persistence — `MultitrackProject`
   is purely in-memory, nothing writes it to disk or restores it across a
   process restart, which was explicitly out of scope ("this is not a
   persistence gap" was true when written and remains true — persistence
   is a distinct, not-yet-started concern); (b) it's held as a local `var`
   inside the one refactored diagnostics section, not lifted into any
   shared/app-level container (a `ViewModel` or similar) that multiple
   screens could read from — appropriate for a diagnostics harness, not
   yet for a real UI with more than one screen touching the same project.
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
8. **`exportMixdownToWav`'s `sampleRate` param is caller-supplied, not
   derived from anything.** Nothing checks it against the sample rate the
   clips' buffers were actually captured/generated at — passing the wrong
   value produces a structurally valid WAV file that plays back at the
   wrong pitch/speed, silently. Once a real project model exists (see risk
   5), the project's own sample rate should be threaded through here rather
   than trusting each call site to pass the right constant.
9. **WAV export has no UI or user-facing trigger yet** — it's exercised
   only by the diagnostics smoke test's synthetic 3-sample buffer. Export
   of a real, multi-minute mixdown hasn't been exercised for performance
   (mixing + encoding + writing a full song, still all synchronous/
   single-threaded) or storage-location correctness (`context.filesDir` is
   fine for a diagnostics test but is app-private storage — a real export
   feature will need to decide whether that's the actual target or if it
   should go through `MediaStore`/a user-chosen location instead).

## What Phase 4's next slice assumes

- `dsp/track_mixer.h`'s `Clip`/`Track` structs are the data model engine
  integration, mixdown, and the JVM reference mixer should all build on —
  changing the shape of `Clip` (e.g. adding fades) should extend, not
  replace, `bufferOffsetFrames`/`lengthFrames`'s "metadata-only trimming"
  property, since punch-in's whole cheapness depends on it.
- The chunked-vs-whole-buffer identity test is the thing to run first
  after any future change to `mixTracks()`, before trusting either
  real-time playback or offline mixdown against it.
- `dsp::encodeWavFloat32`'s 32-bit float format is what the JVM reference
  mixer's own WAV output (once it exists) needs to match byte-for-byte —
  if the reference mixer instead writes 16-bit PCM, sample-identity
  comparison would need to happen before encoding (on the raw mixed float
  buffers) rather than after, since the two encodings are never going to
  agree bit-for-bit.
