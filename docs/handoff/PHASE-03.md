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

**Only wired into the host CMake target**, deliberately not the Android
`.so` build yet — nothing on the Android side calls any of this code, so
compiling it into the shipped library now would just be dead weight. It gets
added to `core/audio/src/main/cpp/CMakeLists.txt` alongside the JNI wrapping
work.

## What's left for Phase 3 (not started)

Roughly in the order the plan's architecture section implies:

- **JNI wrapping** of `generateSweepAndInverse`/`convolve`/`findPeak`/the
  stats functions, exposed through a Kotlin-facing calibration API.
- **Engine integration**: running N sweep repetitions through the *same*
  duplex engine record/playback path Phase 1 built (per the plan's "same
  engine path for calibration and real recording" principle) — play the
  sweep out, capture the loopback in, run it through this phase's math,
  repeat, aggregate via MAD + PNR gating.
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
