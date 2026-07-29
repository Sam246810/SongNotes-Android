# Phase 1 — Duplex engine core + record-to-file

**Status: verified on device (2026-07-29).** Originally written uncompiled,
same as Phase 0 — see PHASE-00.md for the toolchain setup and the two Oboe
API breaks that surfaced on first compile (both live in this phase's core
`openStreamsLocked()`/`isMMapUsed()` code). Once building, a real
record → stop → playback round trip and a 50-cycle open/close stress test
were run on a physical device; results below. The threading/concurrency
reasoning in this doc held up as written — no deadlocks, no crashes, no
leaks observed.

## What shipped

**The engine is now a real output-master duplex engine**, not output-only:
- `openStreamsLocked()` opens an output stream (as before) *and* an input stream,
  matched to whatever sample rate the output negotiated. The input stream has no
  callback of its own — `onAudioReady` reads it non-blockingly
  (`read(..., timeoutNanoseconds=0)`) from inside the output callback. This is
  Oboe's own documented "FullDuplexStream" pattern.
- Input fallback ladder: `Exclusive+Unprocessed` → `Shared+Unprocessed` →
  `Shared+VoiceRecognition`. This is a subset of the plan's full ladder (no I16
  fallback yet) — extend it if a real device needs it; nothing else changes if you
  do.
- `mInputScratch` is sized once, generously (8192 frames × channel count), at
  stream-open time — the RT thread never allocates, and a callback larger than
  that (extremely unlikely on real hardware) is clamped rather than risking a
  buffer overrun.

**Three real threads beyond the RT callback**, matching the plan's threading model:
- **Writer thread** (`writerThreadLoop`): drains a lock-free SPSC ring buffer
  (`spsc_ring_buffer.h`, ~50 lines, templated) that the RT thread fills during
  recording, and appends raw float32 samples to a file. Polls every 5ms rather
  than blocking on a condvar — the RT thread must never touch a synchronization
  primitive heavier than an atomic, so there's nothing for it to signal.
- **Loader thread** (`loaderThreadLoop`): reads a previously-recorded `.f32` file
  into memory and publishes it as a `Scene` (`scene.h`) for the RT thread to play
  back. `ScenePublisher` is the "poor-man's RCU" the plan calls for — an atomic
  raw pointer plus a small retired-generations deque, not `std::shared_ptr`
  directly (a bare `std::atomic<shared_ptr<T>>` has real portability risk on the
  NDK's libc++ and I didn't want Phase 1's correctness resting on that).
- **UI thread**: polls `EngineState` via a direct `ByteBuffer`
  (`engine_state_block.h` ↔ `EngineState.kt`, offsets kept in sync by hand — no
  codegen) at 5Hz while something is active. Zero JNI calls on the poll path
  itself; the one `NewDirectByteBuffer` call happens once, at engine creation.

**Real error recovery**, replacing Phase 0's stub: `onErrorAfterClose` now closes
both streams and reopens them (Oboe's own documented recovery pattern — this
callback runs on a normal thread, not the RT one, so allocation/logging/reopening
is safe there). If a recording or playback was active, it resumes in the same mode
after rebuild; if the rebuild itself fails, it now correctly tears down the writer
thread too — an earlier draft of this file left that thread spinning forever on a
failed rebuild, since nothing would have cleared its run flag once no more audio
callbacks were coming. Fixed before commit, not after — flagging it here anyway
since it's exactly the kind of bug that's easy to reintroduce if this function
gets touched again.

**Concurrency primitives**, all new: `SpscRingBuffer<T>` (templated, reused
as-is rather than duplicated for command-queue use if one's needed later),
`ScenePublisher`/`Scene`, `EngineStateBlock`. A `std::mutex` guards stream
open/close/rebuild (`mRebuildMutex`) and another guards `mLastError`
(`mErrorMutex`) — both are only ever touched from non-RT threads, so this doesn't
violate the RT-safety rule; the actual `onAudioReady` callback still touches
nothing but atomics and pre-sized buffers.

**App-level additions**: `RECORD_AUDIO` runtime permission flow in
`DiagnosticsScreen` (Compose `rememberLauncherForActivityResult`, retries the
start automatically once granted rather than making the user tap twice),
`RecordingForegroundService` (mic-type foreground service + notification, started
only while recording, explicitly *not* stopped on `onStop()` — that's the entire
point of it), manifest permissions (`RECORD_AUDIO`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MICROPHONE`).

**Diagnostics screen** now has a second section: Record / Stop / Play last take /
Stop playback, plus a live readout of recording/playback progress, frames
recorded, and frames dropped (ring-buffer/read overflow counter — should read 0
in normal operation; a nonzero value here is a real signal worth chasing, not
noise).

## Known risks — check these first if something's wrong

1. **Oboe API surface I'm least certain about**: `AudioStream::getState()`
   (used to avoid double-`requestStart()`), the exact `ResultWithValue<T>`
   `operator bool()`/`.value()`/`.error()` usage, and `InputPreset::Unprocessed`/
   `VoiceRecognition` as exact enum names. These are all recalled from
   training-data familiarity with Oboe's samples, not checked against the pinned
   1.9.3 headers. If the build fails on one of these specifically, check the
   actual header rather than assuming the surrounding logic is wrong.
2. **`android.R.drawable.ic_media_play`** as the foreground-service notification
   icon is a deliberate placeholder (compiles for certain, looks wrong — a
   "play" triangle, not a mic). Real branding is Phase 8's job; don't spend time
   polishing this now.
3. **`FOREGROUND_SERVICE_TYPE_MICROPHONE` requires `RECORD_AUDIO` already
   granted** before `startForeground()` is called with that type, or it throws.
   The Compose flow checks permission before calling
   `context.startForegroundService(...)` — if you ever refactor this ordering,
   re-verify that constraint holds.
4. **The `mRebuildMutex` / `stopRecordingInternal()` interaction is subtle.**
   `onErrorAfterClose` holds `mRebuildMutex` while calling
   `stopRecordingInternal()`/`stopPlaybackInternal()` on the failure path — this
   is safe *only* because neither of those functions ever tries to acquire
   `mRebuildMutex` itself. If you add stream-touching logic to either of them
   later, that invariant breaks silently (deadlock, not a compile error) — grep
   for `mRebuildMutex` before changing either function.
5. **No raw `AudioDeviceCallback` registration** — route-change handling relies
   entirely on Oboe surfacing device disconnects as `oboe::Result::ErrorDisconnected`
   through the normal error-callback path. This is Oboe's documented mechanism.
   **Verified on real hardware (2026-07-29)**: a Bluetooth headphone disconnect
   during a test-tone session triggered `onErrorAfterClose` → close/reopen on
   the new route, with no crash and the engine landing back on clean
   Exclusive/LowLatency/MMap numbers. This was mid-tone rather than mid-take
   specifically — the mid-*recording* case (frame gap handling in the ring
   buffer/writer thread) is still worth a dedicated test per item 3 below.
6. **Playback double-start race** (minor, documented not fixed): calling
   `startPlayback()` again while a previous `loaderThreadLoop` is still mid-load
   isn't fully guarded — the UI disables the Play button while `isPlaying` is
   true, which should prevent this in practice, but it's not structurally
   impossible to trigger. Worst case is a stale late publish, not a crash.

## First build + test — checklist

Beyond Phase 0's build steps (still apply), specifically exercise Phase 1's Done
criteria from the plan:

1. **Record 60 seconds, then play it back — should match** (by ear). Speak or play
   music into the mic, stop, hit Play, listen for anything obviously wrong
   (silence, garbling, wrong speed, clicks at the start/end).
2. **Watch "Frames dropped" during a take** — should stay at 0 on a healthy
   device. A climbing counter means the writer thread can't keep up with the ring
   buffer, or the input stream is underrunning.
3. **Unplug wired headphones (or switch Bluetooth) mid-recording.** The take
   should keep going after a brief gap, not crash or silently stop. Check
   Logcat for `SongNotesAudioEngine` tag — you should see the "stream error
   after close" → "Duplex streams opened" sequence.
4. **50 start/stop cycles** (Record → Stop → Record → Stop, ×50, or Play → Stop
   similarly) — watch for memory growth in Android Studio's Memory Profiler.
   Growth here likely means a `std::thread` isn't being joined somewhere, or a
   `Scene` generation isn't being retired — both `writerThreadLoop`/
   `loaderThreadLoop` and `ScenePublisher`'s retention count are the first
   places to check. **Verified (2026-07-29), approximated without Android
   Studio**: no GUI Memory Profiler available in this environment, so this
   was approximated by sampling `VmRSS` from `/proc/<pid>/status` after each
   of 60 real Arm-record→Stop cycles on the physical device. Pattern: RSS
   climbs for 20-24 cycles, plateaus, then drops sharply (a ~10-22MB drop) —
   classic GC-reclaim sawtooth, not monotonic growth. The ending RSS after
   60 cycles (~170.8MB) was *lower* than the starting RSS (~178MB). This
   matters specifically for the concern named above: ART's garbage collector
   cannot reclaim native (C++) heap — only the managed Kotlin/JVM heap — so
   a full, repeated recovery like this is evidence against exactly the
   thread-join/Scene-retention leak this check exists to catch. Not a full
   replacement for a real Memory Profiler session (no native heap dump was
   taken), but a meaningfully strong signal in the meantime.
5. **TSan over the ring buffer specifically.** Building the whole app under
   TSan is heavyweight; the more targeted option is running the *host* test
   target (once it has ring-buffer tests — Phase 1 didn't add any, see "what
   Phase 2 assumes" below) with `-fsanitize=thread` added to the host
   CMakeLists.txt. **Attempted (2026-07-29)**: no desktop compiler exists in
   this environment either, so `host/test_spsc_ring_buffer.cpp` and
   `host/test_scene.cpp` were instead cross-compiled directly with the NDK's
   `aarch64-linux-android30-clang++` and run as standalone console binaries
   on the physical device via `adb shell` — real concurrent execution, not
   just single-threaded reasoning. A `-fsanitize=thread` build was also
   attempted the same way but TSan's own runtime segfaults on this device
   during its ASLR-disabling re-exec (`ThreadSanitizer: CHECK failed:
   tsan_rtl.cpp:1036`) — a TSan/Android-runtime compatibility issue, not a
   bug in this project's code; confirmed via `TSAN_OPTIONS=verbosity=2`
   before giving up on it. The non-sanitized concurrent run is still real
   signal: 2,000,000 ring-buffer elements and 200,000 Scene publishes against
   a busy reader, both with zero drops/corruption/crashes. TSan itself
   remains a genuine gap — worth revisiting with a different device/API
   level or a proper desktop build if the ring buffer's memory-ordering
   correctness is ever in doubt.
6. **Background the app mid-recording** (home button, lock screen) — the
   notification should stay visible, recording should continue, "Frames
   recorded" should keep climbing when you return. Then **force-stop the app
   from Recents** mid-recording — per the known limitation above, this *will*
   lose the take; confirm that's what actually happens rather than something
   worse (crash, corrupted file that hangs on next playback attempt).

## What Phase 2 assumes

- The duplex engine, ring buffer, Scene, and state-block machinery from this
  phase are the foundation Phase 2's metronome/transport/click-track code builds
  on directly — it adds sample-accurate scheduling on top of `onAudioReady`,
  it doesn't replace this.
- The **0.75s pre-roll** and **head-skip-applied-once-at-commit** design from the
  plan aren't implemented yet — this phase's playback starts exactly where the
  recording started, frame 0 to frame 0, with no transport/downbeat concept at
  all. That's Phase 2's job.
- `EngineMode` currently has four values (`Idle`, `Recording`, `Playing`,
  `TestTone`). Phase 2 will likely add at least `Metronome`/`Armed` — extend the
  enum and the `onAudioReady` mode switch rather than bolting a parallel state
  machine on top.
- **Closed (2026-07-29)**: `host/test_spsc_ring_buffer.cpp` and
  `host/test_scene.cpp` now exist, covering both single-thread functional
  behavior (wraparound, overflow, underflow, clear, and — for `Scene` — the
  generation-retention eviction policy itself) and real two-thread
  concurrent stress runs, added to `host/CMakeLists.txt`'s `dsp_tests`
  target for whenever a desktop compiler is available. TSan-driven
  concurrent testing was attempted but blocked by a TSan/Android-runtime
  compatibility bug on-device (see the "First build + test — checklist"
  section above) — the concurrent tests still ran and passed without the
  sanitizer, cross-compiled via NDK clang and executed directly on the
  physical device.
## Verified on device (2026-07-29)

**Superseded for the input stream specifically, same day**: the numbers
below were captured before Phase 3's AEC/NS/AGC work added
`setSessionId(oboe::SessionId::Allocate)` to the input stream builder — at
capture time, input was still Exclusive/MMap, matching output. It no longer
is; see PHASE-03.md's "AEC/NS/AGC disabling, verified on device" section
for what changed and why it was kept permanent rather than
calibration-only. Everything below about frame counts, dropped frames, and
the pre-roll math is still accurate — only the input stream's own
sharing/performance-mode/frames-per-burst characteristics changed.

Same physical Android 15 device as PHASE-00.md, driven via `adb`
(screenshots + `uiautomator` taps). The recorded take was room-tone/ambient
noise picked up by the phone's own mic (no human spoke/played into it) —
this validates the technical pipeline exactly (frame counts, threading,
file I/O, exact pre-roll math) but says nothing about audio *quality*;
that still needs a real human take.

- **Full round trip**: armed at 80 BPM, recorded for real wall-clock time
  spanning the tap → screenshot → scroll → tap sequence, stopped, played
  back to completion. `Frames recorded: 3,506,688`. `Frames dropped: 0`
  throughout record *and* playback. `XRun count: 0` throughout.
- **Pre-roll math checked out exactly**: `3,506,688 − 36,000` (0.75s
  pre-roll @ 48kHz) `= 3,470,688`, which is precisely the
  `playbackTotalFrames` the engine reported — confirms the head-skip logic
  in `writerThreadLoop` trims exactly the intended amount, not
  approximately.
- **Playback auto-stopped correctly** at `3,470,688 / 3,470,688` (exact
  match), UI buttons returned to their idle-enabled state without manual
  intervention.
- **50 rapid start/stop cycles** on the test-tone path (not the
  record/playback path specifically — see PHASE-00.md's verification
  section): process stayed alive, zero crashes, zero engine warnings,
  capability readout unchanged after. The Memory Profiler / TSan-based
  checks this doc originally called for (items 5–6 in the checklist above)
  are still outstanding — the black-box crash/logcat check is a lower bar
  than those, not a replacement for them.
- **Not yet done**: a deliberate mid-*recording* Bluetooth/wired
  disconnect (the one that happened was mid-test-tone), and the
  force-stop-from-Recents mid-recording check (item 6 in the checklist
  above).
