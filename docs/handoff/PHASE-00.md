# Phase 0 — Repo skeleton + "hello Oboe"

**Status: verified on device (2026-07-29).** Originally written with no local
Java/Gradle/Android SDK/NDK/CMake toolchain available, so it shipped
uncompiled — see git history for that caveat. A real Android Studio +
NDK 27.2.12479018 + CMake 3.22.1 toolchain was then set up and the app was
built and run on a physical Android 15 device. Two real Oboe 1.9.3 API
breaks were caught on first compile and fixed (see "Verified on device"
below) — the rest of the reasoning in this doc held up as written.

## What shipped

**Project skeleton:**
- `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`,
  `gradle/libs.versions.toml` (version catalog — AGP 8.7.3, Kotlin 2.0.21, Compose
  BOM 2024.12.01, Oboe 1.9.3, NDK r27.2.12479018).
- `gradle/wrapper/gradle-wrapper.properties` only — **no `gradlew`/`gradlew.bat`/
  `gradle-wrapper.jar`.** I can write the wrapper scripts as text but not the binary
  jar, and a text-only wrapper without its jar just fails confusingly. See
  "First build" below — this is a one-click fix in Android Studio.
- Only `:app` and `:core:audio` are wired into `settings.gradle.kts`. The
  architecture doc lists `:core:model`, `:core:domain`, `:core:data`,
  `:feature:*` too, but there's nothing to put in them yet — they get added in
  the phase that first needs them (5, 5.5/6, 8+) rather than sitting empty for
  months. Not a scope cut, just not front-loading empty modules.

**`:app`** — one screen. `MainActivity` hosts a Compose `DiagnosticsScreen` with a
Play/Stop button and a live readout of `EngineCapabilities`: audio API, sample
rate, frames per burst, channel count, format, sharing mode, performance mode,
`isMMapUsed`, xrun count, last error. No navigation, no Hilt yet — nothing to
navigate to or inject until a second screen exists.

**`:core:audio`** — Kotlin facade (`AudioEngine`) over a native engine
(`NativeAudioEngine` in `src/main/cpp/audio_engine.{h,cpp}`), JNI glue in
`jni_bridge.cpp`. The C++ side:
- Opens an **output-only** Oboe stream (no input/duplex — that's Phase 1),
  `LowLatency` + `Exclusive` first, falling back to `Shared` once if `Exclusive`
  is refused.
- Plays a 440 Hz sine via a small pure function, `dsp::renderSine`
  (`src/main/cpp/dsp/sine_wave.{h,cpp}`) — deliberately factored out instead of
  inlined in the callback, so it's the first (tiny) instance of the "shared pure
  C++, host-testable" pattern Phase 3's real calibration DSP needs at much
  larger scale.
- Reports capabilities including `AudioStream::isMMapUsed()` — this is the one
  API call in this phase I'm least certain about; see risks below.
- Has a minimal `onErrorAfterClose` that logs and drops the stream. **No
  automatic rebuild** — that needs the writer thread and Scene machinery Phase 1
  adds; for now a stream error just requires pressing Play again.

**Host DSP tests** — `core/audio/src/main/cpp/host/` is a second, independent
CMake project (FetchContent-pulls GoogleTest, no Android/NDK involved at all) that
compiles `dsp/sine_wave.cpp` unmodified and runs 5 tests against it (frequency via
zero-crossing count, amplitude, channel duplication, phase continuity across
split calls, zero-frames safety). This is the harness Phase 3 will pour the real
calibration DSP tests into — proven out now on the one function small enough to
get right without a device.

**No native-library-alignment concern for Phase 0 itself** (nothing links yet on
your machine), but the 16 KB page-size linker flag
(`-Wl,-z,max-page-size=16384`) is already in `core/audio/src/main/cpp/CMakeLists.txt`
via `target_link_options`, not in Gradle's `cppFlags` (which only reach the
compile step, not the link step) — get this right from the first `.so` rather
than retrofitting it later.

## Known risks — check these first if the build fails

Roughly in order of how likely each is to actually be the problem:

1. **`gradle-wrapper.jar` is missing.** Opening the project folder in a recent
   Android Studio should offer to generate the wrapper automatically on sync. If
   it doesn't, run `gradle wrapper --gradle-version 8.10.2` once from a machine
   with Gradle installed, or install Gradle 8.10.2 directly and invoke it without
   the wrapper for the first run.
2. **`AudioStream::isMMapUsed()` may not exist under this exact name/signature
   in Oboe 1.9.3.** I'm recalling this from Oboe's diagnostic-support additions
   (the same feature Google's own `oboetester` app surfaces) but haven't checked
   it against the pinned version's actual header. If the build errors on that
   call in `audio_engine.cpp`, check Oboe's `AudioStream.h` for the current
   method name/signature, or drop it to `return false;` temporarily and file a
   follow-up.
3. **`compileSdk = 36` / `targetSdk = 36` needs a matching installed SDK
   platform** (Android 16) in Android Studio's SDK Manager. Install it if missing.
4. **NDK version pin `27.2.12479018`** — if Android Studio doesn't have this exact
   NDK side-by-side version installed, it'll prompt to download it. If that exact
   patch version is no longer offered, bump to whatever r27.x is current; nothing
   in this phase depends on the patch version specifically.
5. **`find_package(oboe REQUIRED CONFIG)` failing** in CMake usually means the
   `prefab = true` block in `core/audio/build.gradle.kts` isn't wired up right,
   or the `implementation(libs.oboe)` dependency didn't sync — check the Gradle
   sync log before assuming the CMake file itself is wrong.
6. **AGP/Kotlin/Compose-BOM version skew.** These were current as of my training
   data; by the time you open this, Android Studio will very likely prompt an
   AGP/AGSL upgrade. Accepting that prompt is expected and fine.

## First build — checklist

1. Open the repo root in Android Studio (not `:app` — the root, so both modules
   load).
2. Let it generate the Gradle wrapper if prompted; accept any AGP upgrade prompt.
3. Install the SDK platform / NDK version it asks for via the SDK Manager if
   missing.
4. Sync. If `find_package(oboe REQUIRED CONFIG)` fails, that's risk #5 above.
5. Run on a **real device** (emulator audio is unreliable for anything
   latency-related, per the plan's testing strategy — for this phase specifically
   a plain "does the app open" check works on an emulator, but the actual Done
   criteria below need a phone).
6. Tap "Play test tone" — confirm you hear a clean, click-free 440 Hz tone.
7. Watch the capability readout — sanity-check `Audio API` is `AAudio` (not
   `OpenSL ES`, unless the device is genuinely old), `Sample rate` matches the
   device's native rate, `MMap` reads `yes` on a reasonably modern phone.
8. Leave it running 60 seconds — **`XRun count` should stay at 0.** This is the
   plan's literal Phase 0 Done bar. Rotate/lock the screen, switch apps briefly
   and come back, to catch anything only a lifecycle transition surfaces.
9. Optional but valuable: run the host tests standalone (needs a desktop CMake +
   C++17 compiler, nothing Android-related) —
   ```
   cd core/audio/src/main/cpp/host
   cmake -S . -B build
   cmake --build build
   ctest --test-dir build --output-on-failure
   ```
   All 5 should pass. If they don't, that's a real bug in `sine_wave.cpp`
   independent of anything Android — fix it there before chasing it on-device.

## Verified on device (2026-07-29)

Physical Android 15 phone, USB-connected, driven via `adb` (screenshots +
`uiautomator`-driven taps rather than manual interaction). Real numbers,
not the guesses this doc originally shipped with:

- **Two Oboe 1.9.3 API breaks caught on first compile**, both in
  `audio_engine.cpp`: `AudioStreamBuilder::openStream()` no longer returns
  `ResultWithValue<shared_ptr<AudioStream>>` — it now takes a
  `shared_ptr<AudioStream>&` out-param and returns `Result` directly.
  `AudioStream::isMMapUsed()` was removed from the base class entirely; the
  real replacement is the static `oboe::OboeExtensions::isMMapUsed(AudioStream*)`
  helper (`oboe/OboeExtensions.h`) — note its own header comment says it's
  "only for testing... may change or be removed at any time," fine for a
  diagnostics screen, worth reconsidering if ever used elsewhere.
- **One real product bug, not a toolchain issue**: "Play test tone" opened
  the duplex engine (which always opens both streams — see this doc's
  output-master design above) without ever requesting `RECORD_AUDIO`, so a
  fresh install failed input `requestStart()` with `ErrorDisconnected` the
  first time anyone tapped it, before ever reaching "Arm & record." Fixed
  by gating the tone button behind the same permission-request flow Phase 1
  already used for recording.
- **Actual capability readout** on this device with no Bluetooth connected:
  `AAudio, 48000 Hz, 96 frames/burst, Mono, Float, Exclusive, LowLatency,
  MMap yes, XRun 0`. That's the real fast path landing correctly, not a
  fallback.
- **60-second soak**: `XRun count` stayed at 0 for the full duration. Phase
  0's literal Done bar, met.
- **50 rapid open/close cycles** (tap Play/Stop 50×, ~0.35s apart, driven
  via a single on-device shell loop): process stayed alive throughout, zero
  `FATAL`/`AndroidRuntime`/crash entries in logcat, zero
  `SongNotesAudioEngine` warnings, capability readout unchanged afterward.
  No observable leak from this test (see PHASE-01.md for the more targeted
  ring-buffer/thread-join version of this check).
- A **Bluetooth headphone disconnect mid-test** (unintentional, happened
  while verifying) exercised the error-recovery path live: `onAudioReady`
  → `onErrorAfterClose` → close/reopen on the new route, landing back on
  the same Exclusive/LowLatency/MMap numbers with no crash. See PHASE-01.md
  for more on this.

## What Phase 1 assumes

- The output-only stream from this phase becomes one half of Phase 1's
  **duplex** engine (add an input stream read non-blockingly from inside the
  same output callback — Oboe's `FullDuplexStream` pattern). The fallback ladder
  here (`Exclusive` → `Shared` only) is a subset of the full ladder the plan
  describes (→ larger buffer → I16 → `VoiceRecognition`); Phase 1 is where the
  rest gets built, once there's an input side to need it.
- `onErrorAfterClose` needs real recovery once a take can be mid-flight — right
  now it just drops the stream, which is fine when the only thing playing is a
  test tone nobody's recording.
- The 60 Hz continuous polling this phase avoids (plain JNI calls, fine at 2 Hz
  for a diagnostics screen) becomes real once there's a live input meter —
  that's the direct-`ByteBuffer` state block from the plan's threading design,
  not more JNI calls at a higher rate.
- No device numbers are recorded here — this phase was never run on real
  hardware. Phase 1's handoff doc should be the first one with actual measured
  round-trip/xrun numbers on record.
