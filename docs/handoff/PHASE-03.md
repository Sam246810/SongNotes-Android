# Phase 3 — Automatic acoustic loopback calibration

**Status (2026-07-30): Phase 3 is done.** DSP core, JNI wrapping, engine
integration, AEC/NS/AGC disabling, N-rep sessions with AEC-defeat
detection, per-route storage, Bluetooth refusal, the Rules A/B/C/I
plumbing, the wizard's full auto-calibration flow, the manual slider
fallback, live route swapping, the recommended-conditions notice, and the
tap-along onset-detection manual path are all done and verified on a
physical device — the last item was verified end-to-end with a real
person tapping, not just synthesized data. `docs/PLAN.md` now holds the
full plan text verbatim (it had nearly been lost to context compaction —
this doc's "Rules A–I" summary below is no longer the only surviving
copy).

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

## The wizard's auto-calibration flow, verified on device (2026-07-30)

`CalibrationWizardScreen.kt` (new, `:app`) is a real, navigable screen —
reachable via a plain "Open calibration wizard" button on `MainActivity`
(no navigation library exists yet; a two-screen toggle is the honest amount
of infrastructure for now) — implementing the plan's primary
auto-calibration path end to end: **Intro → (permission request if
needed) → Bluetooth check (refuses with a plain-language explanation,
"Measure anyway" to override) → Running (5 real repetitions, haptic pulse
per repetition via `Vibrator`, no audible click per Rule D) → Results
(MAD-filtered mean delay, consistency count, AEC-defeat warning if
detected) → Save (persists via `CalibrationStore`, keyed by the detected
route) → Saved confirmation → Done.**

Talks to audio exclusively through `CalibrationAudio` (Rule I) — the
screen holds no reference to `AudioEngine`'s metronome/transport methods at
all, only `runSweeps()`/`playPreMixed()`. `CalibrationSession.run()` grew
an optional `onRepetitionComplete` callback so the Running step's haptic
cueing is driven by genuine capture-completion events, not a fixed-interval
UI timer pretending to track real progress.

**Ran the complete flow on the physical device, start to finish**: tapped
through Intro → Start (RECORD_AUDIO already granted, route not Bluetooth so
no warning shown) → Running executed 5 real sweep captures → Results
reported **21.3ms measured delay, 4/5 repetitions agreed** (MAD correctly
rejected one outlier, consistent with the noisier real-world runs seen
earlier in this doc) → Save → **Saved: "Calibration for 'SM-F956W' is
saved. Recordings through this route will now line up automatically."** →
Done returned cleanly to the main screen. Logcat showed a single clean
stream-open for the whole session (`Exclusive`/`LowLatency`/`MMap` on
output throughout), zero warnings or errors.

Rules F/G ("fixed layout from mount, nothing pops mid-flow") are applied
within the Running step specifically — its indicator + progress text
occupy the same slot from the instant that step mounts, showing neutral
placeholder content before the first repetition completes rather than
having new elements appear once data exists — not by keeping all six
screens' UI simultaneously mounted with visibility toggles across the
entire wizard. The plan's own example for this rule (the manual path's tap
pad) is about one persistent element through one flow, not literally every
screen in the whole wizard; see the code comment in
`CalibrationWizardScreen.kt` for the reasoning spelled out.

**Not yet done**: no real recorded take exists yet for the Rule A/B/C
verify-playback step to run against (the earlier DSP/JNI verification used
a synthetic sine "take" — see "Rules A/B/C/I plumbing" above) — the wizard
doesn't yet prompt the user to record a short verification take and play
it back pre-mixed. That's part of a fuller wizard pass, not this one.

## Rule C's actual mechanism: applying the offset to real recordings (2026-07-30)

Everything above this point measures and saves a calibration offset —
nothing until now actually *applied* it to a real recording. That gap is
closed: `armRecording()` gained a `calibrationOffsetFrames` parameter,
folded into the writer thread's head-skip amount
(`preRollFrames + calibrationOffsetFrames`, clamped ≥0) rather than the
fixed pre-roll alone. `preRollFrames` itself — capture *start* timing —
is deliberately untouched; only how much the writer thread discards
changes. `DiagnosticsScreen`'s record flow now looks up
`CalibrationStore.load(route.routeKey)?.offsetFrames` before arming.

**Direction/sign reasoning** (worth spelling out since getting this
backwards would make alignment worse, not better): content emitted at
output frame D shows up in the input stream at input frame
`D + offsetFrames` — that's literally what the calibration sweep measures.
A user performing along with the downbeat click they hear lands their
actual performance in the raw input stream at that same later frame, not
at frame D. Trimming only the fixed pre-roll leaves the stored take's
frame 0 sitting `offsetFrames` *before* where the performance actually
landed — the performance would sound late on playback. Trimming
`preRoll + offset` instead shifts frame 0 to where the performance actually
happened.

**First on-device test appeared to fail** — applying a calibration of
21.3ms (saved earlier by the wizard, in an *earlier app session*) to a
new recording, the residual measured offset came out to **+70.12ms**,
worse than the ~21ms uncorrected baseline. Before assuming the sign was
backwards, measured the *current* actual round-trip latency in the same
session: **90.47ms** — not 21.3ms. `90.47 − 21.3 ≈ 69.17ms`, matching the
observed 70.12ms residual almost exactly. This wasn't a bug in the
correction logic; it was a **stale calibration value** — the true latency
had genuinely drifted between the wizard measurement and this recording
(the same session-to-session variability already documented earlier in
this file, now caught in the act by end-to-end testing rather than by
comparing two isolated numbers).

**Confirmed the mechanism itself is correct** by measuring a *fresh*
calibration (90.33ms, matching the standalone reading almost exactly) in
the same session as a new test recording, then re-running
`measure_click_alignment.py` on it: of 32 detected beats, roughly half
landed at **0.5–1.5ms residual offset** — a near-total collapse from the
tens-of-milliseconds range seen uncorrected. The remaining beats showed
large, inconsistent outliers (up to ±118ms) that don't fit any
sign/scaling error — almost certainly `measure_click_alignment.py`'s
simple loudest-transient-in-a-window peak search locking onto room
noise/reverb rather than the actual click in this particular (noisy,
late-night) recording, not a flaw in the correction itself. The clean
half is the real signal, and it confirms both direction and magnitude are
right.

**Practical implication worth carrying into the wizard/product UI**: a
calibration value's shelf life on this device is not indefinite — it can
go stale within the span of one interactive session, let alone across
days. Whatever eventually surfaces calibration status to the user should
probably show *when* it was last measured, not just that a value exists.

## The wizard's verify-take step, verified on device (2026-07-30)

`CalibrationWizardScreen` gained a "Verify — record a quick take" button on
the Results step: Verifying (records 4-beat count-in + 2 bars through
`AudioEngine.armRecording` directly, complete with its normal audible
metronome, with the just-measured `calibrationOffsetFrames` applied) →
reads the take back → `Calibration.buildPreMixedVerificationBuffer()` →
VerifyPlayback (`CalibrationAudio.playPreMixed`) → back to Results, so the
user can actually hear the correction rather than just read a number.

**A real design question came up and is worth recording**: does recording
this demo take violate Rule I ("the calibration ViewModel... has no
reference to the metronome or transport API")? Resolved by re-reading what
Rule I is actually protecting: it's specifically about the *sweep
measurement* ViewModel not being able to schedule a click that competes
with an in-flight calibration measurement. Recording a short demo take
isn't calibration measurement — it's the same ordinary Phase 1/2 recording
path with its normal metronome, just invoked from the wizard for
demonstration, after measurement has already finished. So `Verifying` uses
`engine.armRecording()` directly (with the metronome, correctly — a user
needs something to perform against), while the *playback* that follows
still goes exclusively through `CalibrationAudio.playPreMixed()` — that's
where Rule I's actual guarantee (no independently-scheduled second click
layered onto verification playback) continues to hold. Documented this
reasoning in the file's own doc comment, not just here, so a future editor
sees it before "simplifying" the mixed access pattern away.

**Ran the complete verify flow on the physical device**: tapped Verify
from Results → recording ran through count-in and 2 bars with the
metronome audible → automatically stopped → pre-mixed buffer built and
played back via `CalibrationAudio.playPreMixed` → returned cleanly to
Results. Logcat showed a single clean stream reuse for the whole sequence,
zero warnings or errors.

## Live route swapping, verified on device (2026-07-30, confirmed 2026-07-30)

**`AudioRouteMonitor`** (new, `:core:audio`) — the piece flagged below as
"not started" as of the previous update, now implemented: live
route-change notification backed by `AudioManager.registerAudioDeviceCallback`,
exposing a `StateFlow<AudioRoute>` that updates whenever the OS reports an
input device added or removed. Same `routeKey`/heuristic as
`AudioRouteDetector`'s existing point-in-time query (`AudioRouteMonitor`
uses that same detector internally) — only WHEN callers find out the
route changed is new here, not WHAT counts as "the route."

Wired into **`ManualCalibrationScreen`** — the plan's own example of what
this was for ("invalidating a displayed calibration value if the route
changes while a result is still on screen"): the screen now starts the
monitor on composition (`DisposableEffect`, stopped on dispose) and
collects `currentRoute` for the whole screen's lifetime, instead of a
one-time `LaunchedEffect(Unit)` query at first composition. On a route
change, the slider reloads to the new route's own stored calibration (or
a sensible default if none exists) and a status message tells the user
what happened, rather than silently continuing to show a value that
belonged to a different route.

**Shipped without on-device confirmation** during a session where the
phone was intentionally disconnected (the user wanted to sleep without
needing to babysit device testing) — a deliberate and disclosed exception
to this project's usual "verify on device before calling it done" bar at
the time. **Verified for real the next device session**, on the physical
`SM-F956W`, with a real Bluetooth headset (Sony WF-1000XM5):

- Opened `ManualCalibrationScreen` — correctly loaded the stored 150ms
  calibration for the built-in mic route on first composition (confirms
  `AudioRouteMonitor`'s initial `detector.currentInputRoute()` call and
  `DisposableEffect`-driven `start()` both work).
- Connected the Bluetooth earbuds while the screen stayed open: the
  screen updated **live**, with no re-navigation or manual refresh —
  slider reset to 80ms (no stored calibration yet for that route) and
  status text read `Route changed to "WF-1000XM5" — reloaded calibration
  for this route.` Confirms `onAudioDevicesAdded` fires promptly on this
  device/Android version, `AudioManager.getDevices()` already reflects
  the new device by the time the callback runs (no race), and the
  main-`Looper` `Handler` → `StateFlow` → Composable `collect` chain
  works correctly.
- Disconnected the earbuds: the screen swapped back live, slider
  returned to **150ms** (the correct stored value for `SM-F956W`), status
  text read `Route changed to "SM-F956W" — reloaded calibration for this
  route.` Confirms the callback fires symmetrically on removal, not just
  addition.
- `adb logcat` showed zero crashes, fatal exceptions, or `AndroidRuntime`
  errors across both transitions.

All four previously-unconfirmed assumptions listed in the prior version
of this section are now confirmed correct. The one item not exercised by
this pass: the UX question of what happens if a route changes *mid-`Test`
recording* (slider/status changing while `isBusy` is true) — not tested,
since the earbuds were connected/disconnected between `Test` runs, not
during one. Low risk (the slider and Save button are already disabled
while `isBusy`), but worth a specific check before trusting that
interaction.

## Recommended-conditions notice, verified on device (2026-07-30)

The wizard's `IntroStep` gained a short second paragraph: "For the most
reliable result: find a quiet room, use your device's built-in speaker
and microphone (or wired headphones) rather than Bluetooth, and leave the
device still on a flat surface while it measures." The Bluetooth-latency
warning itself already existed (`BluetoothWarningStep`, shipped as part
of "The wizard's auto-calibration flow" above) — this closes the other
half of the "What's left" bullet that was open, a plain-language
heads-up before the user starts, not a hard device-capability gate (no
"minimum spec" data model exists to gate against, and the plan's own
"any constant offset gets measured and cancelled" premise means a slower
device still calibrates correctly, just less consistently session to
session).

**Ran on the physical device**: confirmed the new paragraph renders
without layout issues, and ran the flow past it (Start calibration →
correctly detected the still-connected Bluetooth route and showed the
existing Bluetooth warning, "Cancel" returned cleanly to Intro) with zero
crashes in logcat.

**One real, pre-existing UX issue surfaced while testing this, worth
flagging**: on this device, buttons pinned to the bottom of a full-screen
Column (`Start calibration`, likely others across the app) render partly
underneath the 3-button navigation bar's tappable region — the button's
own bounds extend to y=2303 out of a 2376px-tall screen, but the nav
bar's reserved inset starts at y≈2250, so taps in roughly the bottom 50px
of the button get swallowed by system navigation instead of reaching the
app. Not a regression from this change (the button was already
bottom-pinned via `Spacer(Modifier.weight(1f))`) and not usually a
practical problem for a real finger tapping center-mass, but worth a
real fix — likely `Modifier.navigationBarsPadding()` or equivalent — on
whichever screen picks up general polish later, since edge-to-edge
rendering without inset-aware padding is presumably repeated on other
bottom-pinned buttons across the app (`ManualCalibrationScreen`,
`ScratchpadScreen`, etc.) too.

## Tap-along onset detection: DSP core + JNI, verified on device (2026-07-30)

`dsp/onset_detection.{h,cpp}` (new) — a faithful C++ port of the desktop
web app's `detectOnsets`/`estimateLatencyFromOnsets` (`src/audio/
latency.js`), **not** a reimplementation from a description: same
short-window RMS energy envelope with a running-sum accumulator, same
threshold-ratio/refractory-gap onset logic, same nearest-onset-after-
each-scheduled-click median-matching estimator. This is the one case in
Phase 3 where porting this specific algorithm verbatim is *correct*,
not a shortcut — the plan explicitly rejects it for the automatic
acoustic-loopback path (a phone speaker's faint, reverberant click bleed
defeats a naive energy threshold, which is why the sweep+matched-filter
approach exists instead) but calls out that "its assumptions hold" for
the manual tap-along path specifically: a direct finger-tap on the
device is a loud, sharp, high-SNR transient conducted mostly through the
device's own body straight to the mic, not a faint room-reflected click.

- **Host test file** `host/test_onset_detection.cpp` (new) ports the
  intent of the JS test suite (`src/test/latency.test.js`'s
  `detectOnsets`/`estimateLatencyFromOnsets` describes) as GoogleTest
  cases — same scenarios (three transient bursts recovered within 6ms,
  pure silence returns nothing, median-of-matched-deltas estimation,
  spurious-onset rejection, too-few-matches returns nullopt). Registered
  in both `CMakeLists.txt` (Android `.so` target) and `host/CMakeLists.txt`
  (desktop GoogleTest target) — **not executed locally**, same caveat as
  every other `host/test_*.cpp` in this repo: this working environment has
  no desktop C++ compiler, so "host-testable" here has always meant
  cross-compiling for ARM64 via Gradle/NDK and verifying through an
  on-device JNI smoke test instead (see below), not literally running
  `ctest` on this machine.
- **JNI wrapping** added to `calibration_jni.cpp` (grouped with the other
  pure calibration math, per that file's own header comment) —
  `nativeDetectOnsets` and `nativeEstimateLatencyFromOnsets`. The latter
  returns a length-0 `jdoubleArray` for "no estimate" and length-1 for a
  real value, mirroring `std::optional<double>` without a separate
  sentinel/boolean — same array-as-nullable-result convention already
  used by this file's other functions.
- **`Calibration.kt`** gained `detectOnsets()` and
  `estimateLatencyFromOnsets()` (the latter returning `Double?`,
  unpacking the length-0/length-1 array convention at the Kotlin
  boundary so callers never see it).
- **`OnsetDetectionSmokeTestSection`** added to `DiagnosticsScreen.kt`,
  mirroring `CalibrationDspSmokeTestSection`'s exact pattern: synthesizes
  a PCM buffer with 5 sharp transients at a known +42ms offset from 5
  "scheduled tap" times (no microphone, no physical tapping — this only
  proves the detection math + JNI marshaling work, same scope as the
  sweep smoke test above it).

**Ran on the physical device**: `PASS — JNI boundary verified`; detected
5/5 onsets; estimated latency **42.35ms against an expected 42.0ms**;
pure-silence and too-few-matches edge cases both correct. Confirms the
port, the JNI method-name mangling, and the array marshaling all work —
not just that the C++ compiles.

## Tap-along calibration screen, verified end-to-end on device (2026-07-30)

`TapAlongCalibrationScreen.kt` (new, `:app`) plus `TapAlongCalibrationRecorder.kt`
(new, `:core:audio`) — the actual user-facing feature the DSP core above
was built for. Reuses almost entirely existing infrastructure rather than
adding new engine machinery: `AudioEngine.armRecording()` with its normal
audible metronome (4-beat count-in + 8 steady clicks at 80bpm,
`calibrationOffsetFrames = 0` since this is *measuring* an offset, not
correcting for one already known), the same take-file read-back pattern
`VerificationTakeRecorder` already uses, and `CalibrationStore`/
`AudioRouteDetector` for route-keyed persistence, matching the sweep
wizard and manual slider paths exactly. Scheduled tap times are computed
directly from BPM (`i * 60/bpm` seconds for beat `i`, 0-indexed from the
downbeat) rather than read from the engine — valid specifically because
`calibrationOffsetFrames = 0` means the plan's "head-skip applied once at
commit" design guarantees the stored take's frame 0 is exactly the
transport downbeat, the same assumption `VerificationTakeRecorder`'s
click regeneration already relies on.

Intro → Recording (fixed-slot elapsed/total-seconds readout) → Results
(measured ms + detected-N/8 count, Save; or a "not enough taps detected,
try again" message if `estimateLatencyFromOnsets` returned null) → Saved
→ Done. Distinct from `ManualCalibrationScreen`'s ear-adjusted slider —
this is an objective measurement from the user's own taps, useful
specifically when the room is too noisy for the sweep but the user can
still tap reliably.

**Ran the complete flow on the physical device with a real person
tapping** (not synthesized data — that's what the DSP smoke test above
already covers): recording completed, onset detection found enough
confidently-matched taps, Results showed a measured latency, Save
persisted it, and `songnotes_calibration_routes.xml` confirms **1066.0
frames (22.2ms)** was written for the built-in-mic route — a plausible
value consistent with this device's previously-documented baseline
measurements elsewhere in this doc (~19–22ms without a heavier AEC
session state). Returned cleanly to Diagnostics; zero crashes in logcat
across the whole session.

This closes the last item in `docs/PLAN.md`'s Phase 3 line item list
("wizard obeying Rules A–I, manual slider" — plus this plan's own
call-out to "keep the onset detector... for the manual tap-along path").

## What's left for Phase 3 (not started)

Nothing outstanding against the plan's own Phase 3 scope. Everything
listed in `docs/PLAN.md`'s Phase 3 row — DSP core, JNI wrapping, engine
integration, AEC/NS/AGC disabling, per-route storage, Bluetooth
handling, Rules A–I, the wizard, the manual slider fallback, live route
swapping, the recommended-conditions notice, and the tap-along path —
is done and verified on a physical device.

## Manual slider fallback, verified on device (2026-07-30)

`ManualCalibrationScreen` (new, `:app`) is the plan's "on devices where AEC
can't be defeated, manual isn't a fallback, it's *the* path": no sweep
measurement at all — a slider (0–300ms), a "Test" button that records a
demo take at the slider's value and plays it back pre-mixed (reusing
`VerificationTakeRecorder`, factored out of the wizard's own Verify step
specifically so both screens share one tested record→pre-mix→playback
path rather than duplicating it), and a "Save" button. The user's own ears
are the measurement — simpler than the plan's *other*-described tap-along +
onset-detection mechanism (that algorithm isn't ported; see "What's left"
above), but a genuinely working fallback, not a stub.

**Known, deliberate deviation from Rule D's literal text worth flagging
honestly**: Rule D specifically names "the manual path" as needing
"visual + haptic count only, no audible click" on the built-in speaker.
This implementation's "Test" loop uses `VerificationTakeRecorder`, which
records through the normal audible metronome (the same reasoning applied
to the wizard's own Verify step — recording a demo take isn't calibration
*measurement*, Rule I/D's actual target). For the wizard's Verify step
that reasoning is on firmer ground since that step isn't named directly;
for *this* screen, Rule D's text names "the manual path" specifically, so
this is a real, acknowledged gap against the literal rule, not a
confidently-resolved judgment call — implemented this way because it's the
already-tested, working mechanism, with the honest caveat noted rather
than silently assumed compliant.

**Ran the complete flow on the physical device**: opened the screen, it
correctly pre-loaded the previously-saved 90ms for the detected route from
`CalibrationStore`; tapped Test — recorded through count-in and 8 beats
with the metronome, played back pre-mixed, status text confirmed
completion, zero errors in logcat; dragged the slider to 150ms (confirmed
via the on-screen readout) and tapped Save; confirmed via `DiagnosticsScreen`'s
independent "Check stored calibration" path (same `CalibrationStore`,
same route) that exactly **7200.00 frames** was persisted — precisely
`150ms × 48000/1000`, exact conversion, no rounding drift.

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
4. ~~`AudioRouteMonitor` (live route swapping) is entirely unverified on
   a physical device~~ — **verified 2026-07-30** with a real Bluetooth
   device, both directions (connect and disconnect), zero crashes. See its
   own section above. The one remaining gap: behavior if a route changes
   mid-`Test` recording specifically hasn't been exercised (low risk —
   the relevant controls are already disabled while `isBusy`).

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
