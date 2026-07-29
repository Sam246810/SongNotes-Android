# Phase 3 — Automatic acoustic loopback calibration

**Status: DSP core only, this slice.** Phase 3 is the largest phase in the
plan ("most of the pain in Phase 3," per its own honest notes), so — matching
the plan's own instruction to build it "as a host-testable C++ lib first,
then JNI-wrapped" — this pass delivers exactly that first part: the pure
math, fully unit-tested on the host target, with **no JNI wrapping, no
engine integration, no wizard UI, no AEC handling, no per-route storage, and
no Bluetooth detection yet.** All of that is still ahead — see "What's left"
below. Same environment constraint as every prior phase: no local
toolchain, none of this has compiled.

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

## What's left for Phase 3 (not started)

Roughly in the order the plan's architecture section implies:

- **AEC/NS/AGC disabling** via `SessionId::Allocate` and Java's
  `AcousticEchoCanceler`/`NoiseSuppressor`/`AutomaticGainControl` — this is
  Java-side API surface (`android.media.audiofx.*`), not something Oboe or
  this C++ code touches directly; needs the stream's session ID threaded up
  to Kotlin.
- **AEC-defeat detection by convergence signature** (PNR high on rep 1,
  collapsed by rep 5 — the plan's specific heuristic for "adaptive AEC ate
  our sweep").
- **Per-route calibration storage** (`route_key` = device type + product
  hash), swapped on `AudioDeviceCallback` route changes.
- **Bluetooth handling**: refuse to auto-calibrate by default with a
  plain-language explanation, offer "measure anyway."
- **The wizard UI itself**, built to obey Rules A–I from the plan (no
  count-in on verification playback, pre-mixed single-buffer playback, fixed
  layout from mount so nothing pops in mid-flow, a `CalibrationAudio`
  interface narrow enough that the wizard architecturally cannot schedule a
  competing click) — none of this exists yet, this slice is pure DSP.
- **The manual slider fallback path**, needed on devices where AEC can't be
  defeated (the plan is explicit that on those devices, manual isn't a
  fallback, it's *the* path).
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
