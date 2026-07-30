# Phase 3 — Automatic acoustic loopback calibration

**Status (2026-07-29): DSP core, JNI wrapping, engine integration, AEC/NS/AGC
disabling, N-rep sessions with AEC-defeat detection, per-route storage,
Bluetooth refusal, and the Rules A/B/C/I plumbing (click-track rendering,
offline pre-mix, buffer-based playback, the `CalibrationAudio` interface)
are all done and verified on a physical device.** Only the wizard UI itself,
the manual slider fallback, and product-copy items remain — see "What's
left" below. `docs/PLAN.md` now holds the full plan text verbatim (it had
nearly been lost to context compaction — this doc's "Rules A–I" summary
below is no longer the only surviving copy).

Phase 3 is the largest phase in the plan ("most of the pain in Phase 3,"
per its own honest notes). The earliest pass through this phase matched the
plan's own instruction to build it "as a host-testable C++ lib first, then
JNI-wrapped" — delivering exactly that first part: pure math, fully
unit-tested on the host target, no JNI wrapping, no engine integration, no
wizard UI. Everything below this point in the doc reflects what's shipped
since.

## What shipped

Four new pure-C++ modules under `core/audio/src/main/cpp/dsp/`, each with
host GoogleTest coverage, plus one dedicated end-to-end test file that
matters more than the rest combined:

- **`fft.{h,cpp}`** — a standard iterative radix-2 Cooley-Tukey FFT over
  `std::complex<float>`. Tested against a DC signal, a pure sinusoid's bin
  location, round-trip (`ifft(fft(x)) == x`), and Parseval's theorem
  (energy conservation between time and frequency domains). This is
  textbook, low-risk code — the actual risk in this phase is everything
  built on top of it.
- **`sweep.{h,cpp}`** — generates an exponential sine sweep (ESS) and its
  Farina inverse filter (`generateSweepAndInverse`). This is a **direct
  implementation from the published formula** (Farina, AES 2000), not
  ported from anything in the web app (which has no equivalent — the plan
  explicitly rejected porting the web app's dead onset-detection code for
  this automatic path). Tested structurally (correct length, frequency
  actually moves from f1 toward f2, stays within the requested amplitude,
  and the inverse filter is exactly the reversed-and-envelope-scaled sweep
  per the formula) — but structural tests can't prove the *formula itself*
  is right. That's what the next file is for.
- **`matched_filter.{h,cpp}`** — FFT-based fast convolution (`convolve`) and
  peak-finding with parabolic sub-sample interpolation (`findPeak`), plus a
  noise-floor estimate (median magnitude away from the peak) that feeds PNR.
  Tested against hand-computed convolutions, the identity property
  (convolving with an impulse returns the original signal unchanged), and
  both symmetric and asymmetric synthetic peaks for the interpolation math.
- **`calibration_stats.{h,cpp}`** — MAD (median absolute deviation) outlier
  rejection across repeated measurements, and peak-to-noise ratio in dB.
  Both are the specific statistical tools the plan names for turning "5
  sweep repetitions, maybe one corrupted by a passing noise" into one
  trustworthy number, or for recognizing "this room is too noisy, don't
  trust any of this."

**`host/test_calibration_roundtrip.cpp`** is the one that actually matters:
synthesizes a fake "recording" — silence, then the real sweep at a *known*
delay, then more silence, with noise added at a chosen SNR — and proves the
full pipeline (convolve against the inverse filter, find the peak, subtract
back out the sweep's own length) recovers that known delay. This is
literally the plan's own words under "Verification": *"Host C++ + GoogleTest
(highest value in the project): calibration DSP against synthesized
recordings... assert recovery within ±1 sample at 20dB SNR, graceful at
10dB, clean failure at 0dB."* One correction to that phrasing worth being
explicit about: at 0dB input SNR, an exponential sweep's processing gain
(the entire reason the plan specifies a sweep instead of a click) should
still recover the delay quite precisely — the test asserts exactly that
rather than asserting failure, and says so in a comment. If this test fails
once built, that's a strong, specific signal that either the sweep/inverse
formula has a real bug, or the processing-gain assumption underpinning this
whole approach needs a second look — not a tolerance to loosen until it's
green.

**Now wired into the Android `.so` build too** (2026-07-29) — see "JNI
wrapping, verified on device" below. Originally deliberately excluded from
`core/audio/src/main/cpp/CMakeLists.txt` since nothing on the Android side
called any of it; that's no longer true.

## JNI wrapping, verified on device (2026-07-29)

`generateSweepAndInverse`/`convolve`+`findPeak`/the stats functions are now
exposed through a Kotlin-facing `Calibration` object
(`core/audio/src/main/java/com/songnotes/core/audio/Calibration.kt`,
backed by `core/audio/src/main/cpp/calibration_jni.cpp`):

- `convolve()` and `findPeak()` are **not** exposed as separate JNI calls —
  they're kept paired on the native side in
  `nativeMeasureRoundTripDelay(recording, inverseFilter, sweepLength)`,
  mirroring `host/test_calibration_roundtrip.cpp`'s own `recoverDelay()`
  helper exactly. The intermediate convolved buffer (up to
  `recording.size() + inverseFilter.size() - 1` samples, padded to the next
  power of two internally) never crosses the JNI boundary — only the
  4-field result does. Exposing the low-level primitives separately would
  have meant marshaling that full buffer across JNI twice per measurement
  for no reason.
- `generateSweepAndInverse` returns one packed `float[2N]` (sweep, then
  inverseFilter) rather than a constructed Kotlin object — no existing code
  in this repo builds Java objects from JNI (class/method-ID lookups), and
  a single primitive array keeps `calibration_jni.cpp` free of that
  machinery. `Calibration.kt` unpacks it into a `SweepData` data class.
- **JNI method-name-mangling risk is real, not theoretical, and C++
  compiling clean does not catch it** — a mismatch between
  `Java_com_songnotes_core_audio_Calibration_nativeXxx` and the Kotlin
  `external fun` declarations only surfaces as `UnsatisfiedLinkError` at
  first *call*. Added a one-tap smoke test to `DiagnosticsScreen.kt`
  (`CalibrationDspSmokeTestSection`) specifically to exercise this at
  runtime: generates a sweep, synthesizes a recording with a known
  500-frame delay in Kotlin (no engine/audio hardware involved — pure
  array manipulation), and confirms `measureRoundTripDelay` recovers it.
  **Ran clean on the physical device**: `recovered delay: 500.003 frames
  (expected 500)`, `pnr: 84.5 dB`, MAD rejection correctly dropped a
  synthetic outlier, `peakToNoiseRatioDb(10,1)` exact. Confirms the array
  marshaling (`jfloatArray`/`jdoubleArray`, both directions) and the method
  linkage all work, not just the pure C++ underneath.

## Engine integration, verified on device (2026-07-29)

A new `EngineMode::Calibrating` plays a sweep through the *same* duplex
engine record/playback path Phase 1 built, per the plan's "same engine path
for calibration and real recording" principle — no new RT-thread machinery,
just a new combination of two already-proven ones:

- **Output** reuses the exact Scene/`ScenePublisher` mechanism Playing
  already uses (`startCalibrationCapture` wraps the sweep in a `Scene` and
  publishes it), except it does **not** auto-stop-to-Idle when the sweep
  buffer runs out — capture keeps going into silence for a tail-padding
  window, which is the entire point: that's where the real round-trip delay
  and reverb tail actually show up in the recording.
- **Input** reuses `mRecordRing`, the same ring buffer Recording already
  fills, but with no pre-roll skip logic — capture starts the instant the
  mode is observed and runs for exactly `sweep.size() + tailPaddingFrames`
  frames, then the RT thread itself transitions back to Idle (mirroring how
  Playing already auto-stops itself, just driven by frames-captured instead
  of playback-cursor-position).
- Deliberately **no new consumer thread**: since the capture duration is
  short and known in advance, `takeCalibrationCapture()` does a single bulk
  `mRecordRing.read()` directly from the calling (JNI/UI) thread after
  polling confirms `isCalibrating` has dropped back to false — safe because
  the RT-thread producer side is provably done by then, same precondition
  `mRecordRing.clear()` already documented.
- `EngineStateBlock` grew two fields (`isCalibrating`,
  `calibrationFramesCaptured`), 36 → 44 bytes — `EngineState.kt`'s offsets
  updated to match by hand, same as always.

**Ran three real captures on the physical device** via a new "Engine
calibration capture" section on `DiagnosticsScreen` (distinct from the
earlier JNI smoke test — that one used a synthesized-in-Kotlin recording to
prove only the JNI boundary; this one plays a real sweep out the speaker
and captures the real acoustic loopback via the mic):

```
Run 1: Captured 48000/48000 frames — delay 937.17 frames (19.52 ms), PNR 62.7 dB
Run 2: Captured 48000/48000 frames — delay 932.01 frames (19.42 ms), PNR 58.3 dB
Run 3: Captured 48000/48000 frames — delay 932.01 frames (19.42 ms), PNR 56.7 dB
```

Zero frame loss across all three (exactly the expected frame count every
time), spread of ~0.11ms across the three runs, PNR consistently well above
any reasonable trust threshold. Also **cross-validates against Phase 2's
independent measurement**: this matched-filter deconvolution's ~19.5ms
figure and the RMS-peak-search click-alignment script's ~21.25ms figure
(PHASE-02.md) are measuring the same physical speaker→air→mic path on the
same device via two unrelated methods and land in the same ballpark — a
meaningful cross-check that neither measurement is a fluke of its own
method.

**Not yet exercised**: repeating this automatically N times with MAD
aggregation baked in (currently each tap is one manual repetition; the
wizard's own repeat-and-aggregate loop is still ahead), and what happens
under a genuinely noisy room rather than a quiet one.

## AEC/NS/AGC disabling, verified on device (2026-07-29)

The input stream now requests `oboe::SessionId::Allocate` (in
`openStreamsLocked()`), and a new `AudioEngine.inputSessionId()` +
`CalibrationAudioEffects` (pure `android.media.audiofx.*` Kotlin, no
JNI/Oboe involvement — matches the plan's own note that this is Java-side
surface) attach and disable `AcousticEchoCanceler`/`NoiseSuppressor`/
`AutomaticGainControl` on that session before a calibration capture starts.
A new `AudioEngine.ensureReady()` opens streams without starting a mode, so
the session ID is real *before* `startCalibrationCapture()` begins, rather
than racing effect setup against the first captured frames.

**On the physical test device**: `AcousticEchoCanceler` and
`NoiseSuppressor` are both available and both successfully report disabled
after the request; `AutomaticGainControl` isn't implemented on this device
at all (`isAvailable() == false` — not uncommon; many OEMs bake AGC into
mic hardware/gain-stage rather than exposing it as a toggleable platform
effect).

**A real, significant, and non-obvious side effect surfaced immediately**:
requesting a session ID dropped the recovered round-trip delay measurement
from ~932-937 frames (~19.4ms, the three Engine-integration baseline runs
with no session/AEC handling) to **4664.51 frames (97.18ms)** — roughly 5x.
Confirmed via logcat this is NOT a bug: the *output* stream stayed
Exclusive/MMap/96-frames-per-burst throughout (only the input builder got
`setSessionId`), but the *input* stream fell from Exclusive/MMap
(`sharing=0`, 96 frames/burst) to Shared (`sharing=1`, 882 frames/burst) the
moment a session was requested. Oboe's own doc comment on `SessionId::Allocate`
warns exactly this: "the use of this flag may result in higher latency" —
Exclusive/MMap streams get their speed by bypassing the platform's effects
processing chain entirely, which is structurally incompatible with
attaching a Java `AudioEffect` that needs to sit in that chain.

**Decision: this is now permanent for every input stream, not toggled per
calibration.** The alternative — only requesting a session during
calibration, reverting to session-less Exclusive input for plain Phase 1/2
recording — was seriously considered and rejected: calibration's entire
purpose is measuring *this stream's* round-trip latency so it can be
corrected for later. If real recording used a different (faster, no
session) input configuration than what calibration measured, the measured
offset would be correcting for the wrong path — actively wrong, not just
imprecise. Keeping the session permanent means Phase 1/2 recording's own
input stream now runs at the same ~5x-larger buffer Shared-mode calibration
measures against, which costs nothing in recorded audio *quality* (no live
monitoring depends on this stream's own round-trip latency being small)
but is a real, worth-knowing change from Phase 0/1's original
Exclusive/MMap/96-frame numbers for the input side specifically. Output is
untouched.

**Not yet done**: the diagnostics screen's capability readout still only
reports the *output* stream's `sharingMode`/`performanceMode`/
`framesPerBurst`/`isMMapUsed` — it has no visibility into the input
stream's now-independently-variable path. Worth adding equivalent
input-side capability getters before this becomes confusing to debug later.

## N-repetition sessions + AEC-defeat detection, verified on device (2026-07-29)

`CalibrationSession` (new, `:core:audio`) runs N repetitions of the same
sweep back-to-back through `AudioEngine`, aggregates the recovered delays
via `Calibration.rejectOutliersMad`, and implements the plan's specific
AEC-defeat heuristic ("PNR high on rep 1, collapsed by rep 5") as a
first-vs-last-repetition PNR drop exceeding a reasoning-based 15dB
threshold — not measured against a real AEC-can't-be-disabled device (none
available to test against), documented as such in the source. Deliberately
separate from `EngineCalibrationCaptureSection`'s single-capture flow
(both stay in `DiagnosticsScreen` as distinct, still-useful diagnostics)
and placed in `:core:audio` rather than the app module specifically because
this is real reusable business logic the eventual wizard needs directly,
not verification scaffolding. Required adding an explicit
`kotlinx-coroutines-core` dependency to `:core:audio` — it was previously
only available there transitively through nothing, since this module has
no Compose dependency to inherit it from.

**Ran a real 5-repetition session on the physical device**: all 5
repetitions landed on the *exact same* delay (1059.07 frames / 22.06ms,
spread 0.01 frames — essentially zero jitter within one session), PNR held
flat around 56dB across all 5, all 5/5 accepted by MAD rejection, correctly
reported "no AEC-defeat signature detected" (nothing to detect — PNR never
collapsed). This confirms the aggregation and defeat-detection logic
itself works correctly against a real measurement run.

**One finding worth flagging honestly, not glossing over**: this 22.06ms
figure is a *third* distinct number from the same device, following the
engine-integration baseline's ~19.4ms (no session, no AEC) and the
single-capture AEC test's 97.18ms (session + AEC, run moments earlier in a
different app process). Same code path both times, same input
configuration (`sharing=1` in both logs) — the difference is real
session-to-session variability, not a bug in the measurement. Most likely
explanation: the device was actively being used for phone calls between
runs, which plausibly leaves the audio subsystem in a different state
(possibly Bluetooth reconnecting, possibly a lingering communication-mode
audio route) for some time after. Not chased down further — if anything
this is a point *in favor* of Phase 3's whole premise: if latency were
always identical regardless of system state, per-session calibration
wouldn't be necessary at all. Worth keeping in mind once the real wizard
exists: a "measure again" affordance is probably worth having, not just a
one-shot measurement trusted forever.

## Per-route storage + Bluetooth handling, verified on device (2026-07-29)

`AudioRoute`/`AudioRouteDetector` (new, `:core:audio`) queries
`AudioManager.getDevices(GET_DEVICES_INPUTS)` and picks a best-guess active
route by OS precedence (wired > Bluetooth > built-in — a heuristic, Android
doesn't expose "the device a not-yet-open stream will actually route to"
directly pre-API 31), producing a `routeKey` from device type + a hash of
`productName`, matching the plan's own "device type + product hash"
phrasing exactly. `CalibrationStore` (new, `SharedPreferences`-backed, not
a real database — no broader data layer exists yet to justify one for a
handful of key-value pairs) persists a measured offset per `routeKey`.
Bluetooth handling lives in `DiagnosticsScreen`'s calibration-session flow
for now (the real wizard doesn't exist yet): a detected Bluetooth route
refuses the first tap with a plain-language explanation and requires a
second explicit tap ("Measure anyway") to proceed, per the plan.

**Verified for real** (no Bluetooth device connected during this pass —
see caveat below): route detection correctly identified the built-in mic
(`SM-F956W_...`, `bluetooth=false`) with nothing stored initially; a real
5-repetition session ran against genuinely noisy conditions this time —
reps 0-1 clustered around 975-984 frames while reps 2-4 clustered tightly
around 1079.95, and MAD rejection correctly kept the majority 3/5 cluster
rather than blending all five into a meaningless average — then persisted
`1079.95 frames` for that route. **Force-stopped the app completely**
(confirmed via `pidof` returning empty, not just backgrounded) and
relaunched fresh: "Check stored calibration" (a separate path that does
*not* run a new capture or call `save()` first) returned the exact same
value and timestamp — genuine cross-process-restart persistence, not just
a same-run save-then-load echoing itself back.

**Not verified**: the Bluetooth-refusal path specifically needs a real
Bluetooth device connected to exercise `isBluetooth=true`, which wasn't
done this pass. The logic is simple (one boolean check gating a
confirmation state) and structurally exercises the same code paths already
verified for the non-Bluetooth case, but "structurally sound" isn't the
same bar the rest of this doc holds everything else to — worth an explicit
live check with a real Bluetooth device before trusting it fully.

## Rules A/B/C/I plumbing, verified on device (2026-07-29)

The Rules A–I text quoted throughout this doc was, until this pass,
reconstructed from a partial paraphrase — the actual plan (now
`docs/PLAN.md`) had nearly been lost to context compaction. With the real
text in hand, built the pieces the wizard needs but that don't depend on
any UI existing yet:

- **`dsp/click_track.{h,cpp}`** (new, host-tested) — `renderClickTrack()`,
  the offline equivalent of the click-scheduling logic
  `audio_engine.cpp`'s `onAudioReady` runs live during Armed/Recording.
  Needed because Rule A's verification playback can't replay a *live*
  click against a pre-recorded take (that's the two-independently-
  scheduled-sources problem the rule exists to prevent) — it has to
  regenerate the click track and mix it in *offline*, once, into one
  buffer.
- **`dsp/mix.{h,cpp}`** (new, host-tested) — `mixAndNormalize()`, a plain
  sample-wise sum with clip-safe scale-down (never scales up a quiet mix).
- **`Calibration.buildPreMixedVerificationBuffer()`** (new JNI wrapping,
  `calibration_jni.cpp`) — pairs `renderClickTrack()` + `mixAndNormalize()`
  in one native call, same reasoning as `measureRoundTripDelay()`'s
  `convolve`+`findPeak` pairing: the intermediate click-track buffer (same
  length as the take, potentially several seconds) never crosses the JNI
  boundary on its own.
- **`AudioEngine.startPlaybackFromBuffer()`** (new engine method) — plays
  an in-memory buffer directly, skipping `loaderThreadLoop`'s file I/O
  entirely. Notably required **zero new RT-thread code**: it just publishes
  a `Scene` and stores `EngineMode::Playing`, exactly what
  `loaderThreadLoop` already does once its file read finishes — Playing
  mode's existing `onAudioReady` logic (auto-stop at buffer end,
  `playbackFrame` tracking) was already exactly what Rule A/B needs.
- **`CalibrationAudio`** (new interface, Rule I) — exactly `runSweeps()`
  and `playPreMixed()`, no reference to `AudioEngine`'s metronome/transport
  methods at all. `RealCalibrationAudio` wraps the real engine;
  `FakeCalibrationAudio` is the test double Rule I's "a fake throws on any
  unexpected call" refers to — written now, not yet exercised by an actual
  `@Test` since no JVM test source set exists in this module yet.

**Verified on device** via a new `DiagnosticsScreen` smoke test: a
synthetic 3-second, 144000-frame fake take (standing in for a real
already-aligned recorded take — Rule C means this step does no offset math
of its own) mixed with a regenerated 80bpm click track produced exactly
144000 mixed frames, played via `CalibrationAudio.playPreMixed` — the same
narrow interface the eventual wizard uses, not `AudioEngine` directly —
with no crash and clean logs (still `Exclusive`/`LowLatency`/`MMap` on
output throughout).

**Not yet exercised**: this used a synthetic sine "take," not a real
recorded one — the wizard's actual take-alignment step (applying the
calibration offset once at commit time, per Rule C, so a *real* recorded
take needs zero further offset math by the time it reaches
`buildPreMixedVerificationBuffer`) doesn't exist yet. That's part of "the
wizard UI itself" below, not a gap in what's built so far.

## What's left for Phase 3 (not started)

Roughly in the order the plan's architecture section implies:

- **AudioDeviceCallback-driven route swapping** — `AudioRouteDetector`
  above is a point-in-time query called right before a capture starts, not
  a live listener. Fine for "what route is this capture about to
  measure/apply to," not yet sufficient for e.g. invalidating a displayed
  calibration value if the route changes while a result is still on
  screen.
- **The wizard UI itself** — see "Rules A/B/C/I plumbing, verified on
  device" below for what's already built and ready for it to call. Still
  needed: the actual screens (intro, running sweeps with visual/haptic
  cueing per Rule D, results, the Rule A/B/C verify-playback step, save-
  per-route), and Rules F/G's fixed-layout tap pad present from the instant
  Record is pressed with the count-in number rendered inside it.
- **The manual slider fallback path**, needed on devices where AEC can't be
  defeated (the plan is explicit that on those devices, manual isn't a
  fallback, it's *the* path) — including Rule D's constraint that the
  built-in-speaker manual path uses **visual + haptic count only, no
  audible click** (the existing metronome click sound is Rule-D-incompatible
  for this specific path; don't reuse it here without re-reading the rule).
- **Recommended-minimum-specs notice** and the Bluetooth-latency warning
  copy — product/content work, not DSP.

## Known risks — check these first

1. **The sweep/inverse-filter formula is implemented from a
   from-memory recollection of Farina's published method, not verified
   against a reference implementation.** `test_calibration_roundtrip.cpp`'s
   delay-recovery tests are the actual check on this — if they fail on
   build, trust them over the structural tests in `test_sweep.cpp`, which
   only check the formula was applied *consistently*, not that the formula
   is *correct*.
2. **FFT size and performance**: `convolve()` pads to the next power of two
   above `a.size() + b.size() - 1`. For a multi-second sweep at 48kHz plus a
   multi-second recording, this FFT size could run to hundreds of thousands
   of points — the current radix-2 implementation is O(N log N) so this
   should still be fast (well under a second), but this hasn't been
   benchmarked. If real calibration runs feel slow once wired up, profile
   before assuming the FFT itself is the bottleneck.
3. **No handling yet for a recording shorter than the sweep** (e.g. if
   engine integration ever calls this with a truncated capture) —
   `convolve()` doesn't crash on mismatched lengths, but the caller should
   validate input lengths once real engine integration exists, since a
   malformed recording would currently just produce a meaningless peak
   rather than an explicit error.

## What Phase 3's next slice assumes

- All four `dsp/` modules here are pure, host-buildable, Android-agnostic
  C++ — nothing about JNI wrapping them should need to change their
  interfaces, only add bindings around them.
- The synthetic round-trip test's tolerances (±1 sample at 20dB, ±5 at
  10dB, ±20 at 0dB) are reasoning-based, not measured against a real device
  or even a real compile. Once this builds, running these tests for real is
  the first thing to check before writing another line of Phase 3 code —
  if the roundtrip tests don't pass as-is, don't patch around it in the
  engine-integration layer; come back and fix the DSP core first.
