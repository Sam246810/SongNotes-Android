# Phase 1 — Duplex engine core + record-to-file

**Status: written, not yet built or run.** Same environment constraint as Phase 0 —
no Java/Gradle/Android SDK/NDK/CMake/C++ toolchain available here, so none of this
has compiled. This phase is considerably higher-risk than Phase 0: real
multi-threaded concurrency (RT audio thread, writer thread, loader thread, UI
thread) that Phase 0 didn't have at all. Budget real time for the first build to
shake out mistakes — see the checklist at the bottom, and treat the plan's own
Phase 1 Done criteria (reproduced below) as the actual bar, not "it compiles."

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
   through the normal error-callback path. This is Oboe's documented mechanism
   and should be sufficient, but it's unverified on real hardware. If unplugging
   headphones mid-take doesn't trigger a rebuild on your device, this is the
   first place to look.
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
   places to check.
5. **TSan over the ring buffer specifically.** Building the whole app under
   TSan is heavyweight; the more targeted option is running the *host* test
   target (once it has ring-buffer tests — Phase 1 didn't add any, see "what
   Phase 2 assumes" below) with `-fsanitize=thread` added to the host
   CMakeLists.txt. Worth doing before trusting this under real recording load.
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
- No host-side GoogleTest coverage was added for the ring buffer, `Scene`, or
  duplex logic this phase — Phase 0's `dsp/sine_wave` tests are still the only
  host tests. Given this phase is a **hard gate** per the plan and TSan
  correctness matters a lot here, adding ring-buffer tests (single-thread
  functional tests at minimum; TSan-driven concurrent stress tests ideally) to
  the host CMake target is worth doing either at the tail end of this phase's
  verification pass or right at the start of Phase 2 — don't let it slide
  indefinitely.
- No device-specific numbers are recorded in this doc — it was never run on real
  hardware. Once you've been through the checklist above, it's worth appending
  actual observed numbers here (round trip estimate, xrun behavior, which
  fallback rungs your test device actually lands on) so Phase 2 has a real
  baseline instead of guesses.
