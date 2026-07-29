# Phase 2 — Transport clock, metronome, sample-accurate placement

**Status: written, not yet built or run.** Same environment constraint as
Phases 0–1 — no local Java/Gradle/Android SDK/NDK/CMake/C++ toolchain, so
none of this has compiled. This phase's actual Done criterion from the plan
is a *measurement*, not a checkbox: "across 5 takes, transients sit at a
constant offset from gridlines with spread < 3 ms." A script to make that
measurement concrete is included — see "Measuring the Done criterion" below.

## What shipped

**A real transport clock.** `mStreamFrameCounter` (int64_t, RT-thread-owned,
incremented by `numFrames` every `onAudioReady` call) is the "master clock is
output frames rendered" the plan calls for — it never resets for the life of
the engine, including across an error-recovery stream rebuild.

**A 64-slot command queue** (`command.h`, `SpscRingBuffer<Command>`) carrying
exactly one command type today: `Arm`. This is new versus Phase 1, and exists
for a specific reason — arming needs the RT thread's *own* frame counter to
compute count-in/downbeat/pre-roll timing, not a UI-thread guess that would
race with it. Starting/stopping the test tone, stopping a recording, and
stopping playback still go through direct atomic mode stores, same as Phase 1
— they don't need frame-accurate timing, and routing them through the queue
too would just be indirection for no benefit.

**Metronome**, rendered in C++ (`dsp/click.h/.cpp`): a short
exponentially-decaying sine burst, host-tested (`host/test_click.cpp`) for
length, click-free truncation (explicit fade to exactly zero at the buffer's
end — the decay envelope alone gets close but never reaches zero, and a
nonzero sample at a boundary is itself a tiny click), peak amplitude, and
decay shape. Downbeat clicks are pitched higher (1800 Hz) than regular beats
(1200 Hz), matching how most physical metronomes cue bar 1. Click scheduling
in `onAudioReady` is sample-accurate within a callback (computes the exact
output-buffer offset a click starts at, carries an in-progress click's
remaining samples across a callback boundary if the click is still playing
when the buffer ends) — not just "whichever callback contains the nearest
frame," which would have added up to one callback's worth of jitter.

**Count-in, pre-roll, and head-skip**, the three pieces the plan calls out
by name:
- **Count-in**: `armRecording(path, bpm, beatsPerBar, countInBeats)` schedules
  `countInBeats` audible clicks before the downbeat. `EngineState.isArmed` /
  `countInBeatsRemaining` let the UI show "3, 2, 1...".
- **Pre-roll**: capture into the ring buffer starts `kPreRollSeconds` (0.75s)
  *before* the downbeat, not at the downbeat and not at Arm time — this is
  sample-accurate too (a callback that straddles the pre-roll start only
  writes its tail into the ring buffer, not the whole callback).
- **Head-skip at commit**: the writer thread discards exactly `preRollFrames`
  worth of samples before it starts actually writing bytes to the file, so
  the stored `.f32` file's frame 0 is the true downbeat — no downstream
  consumer needs to know pre-roll ever happened. The compensation amount is
  a placeholder (0 — i.e. `headSkipFrames == preRollFrames` exactly) until
  Phase 3 provides a real measured round-trip latency to fold in; the
  mechanism doesn't change when that number does, only the value passed to
  the writer thread does.

**Armed → Recording is autonomous**, not command-driven: the RT thread
checks its own `mStreamFrameCounter` against `mDownbeatFrame` every callback
and flips the mode itself once it's reached. Click rendering and ring-buffer
capture treat `Armed` and `Recording` identically (both are "count-in or
take, either way capture and click"), so this transition only affects the
UI-facing state flags — no acoustic consequence from checking it at
callback (rather than sample) granularity.

## Two retroactive fixes to Phases 0–1

Both were real, latent bugs — not something the plan asked for, just things I
found while touching this code again for Phase 2's Arm logic.

1. **`mRecordRing` used to be a `std::unique_ptr`, reallocated per take.**
   `stopRecordingInternal()` called `.reset()` on it (on the UI/JNI thread)
   after joining the writer thread — but nothing actually prevented the RT
   thread from being mid-`onAudioReady`, having read a stale "still
   Recording" mode a few instructions before the mode flip, and calling
   `mRecordRing->write(...)` concurrently. In practice the timing windows
   never really overlapped (a single callback completes in microseconds; the
   join+reset sequence takes milliseconds), but that's "safe under realistic
   scheduling," not "safe" — exactly the gap TSan exists to catch. Fixed by
   making `mRecordRing` a permanent member, constructed once and never
   destroyed until the engine itself is destroyed; `SpscRingBuffer::clear()`
   (new) resets its indices between takes instead. The RT thread can now
   always safely dereference it.
2. **The capability getters (`audioApi()`, `sampleRate()`, etc.) read
   `mOutputStream` without holding `mRebuildMutex`.** Since Phase 1 added
   `onErrorAfterClose`'s reopen-on-a-different-thread logic, these getters —
   called from the UI thread for the Diagnostics screen — could race against
   a concurrent stream rebuild resetting that same `shared_ptr`. All of them
   now lock `mRebuildMutex` (they're called at most a few times a second from
   a diagnostics screen, so the extra lock costs nothing that matters).

## Known risks — check these first if something's wrong

1. **Same Oboe-API-surface uncertainty as Phase 1** (`getState()`,
   `ResultWithValue` usage, `InputPreset` names) — unchanged, still unverified
   against the pinned 1.9.3 headers.
2. **Click pitch/length/decay constants are unvalidated by ear.** 1800/1200 Hz,
   10ms length, 5-time-constant decay were chosen to sound like a plausible
   metronome click, not measured against one. If it sounds wrong, these are
   four numbers in `audio_engine.h` (`kDownbeatClickHz`, `kRegularClickHz`,
   `kClickLengthSeconds`) — cheap to retune, no architecture change needed.
3. **The Diagnostics screen hardcodes 4/4 time and a 4-beat count-in.** BPM is
   the only exposed control; `beatsPerBar`/`countInBeats` are constants in
   `DiagnosticsScreen.kt`'s `beginRecording()`. Real controls for these are a
   product-UI concern (Phase 8/10), not worth building into a diagnostics
   screen.
4. **Click-scheduling edge cases are defensively handled but unexercised**:
   a degenerate BPM is clamped so the beat interval can never be ≤0 (which
   would otherwise spin the scheduling loop), and a click whose start time
   has already passed (only reachable if a previous click's tail ran
   unexpectedly long) renders immediately rather than being silently
   dropped. Neither should be reachable in practice — click length (~10ms) is
   two orders of magnitude below any realistic beat interval — but there's no
   test exercising them because they're pure defensive code for cases that
   "can't happen."
5. **No change to the error-recovery path's interaction with Arm state.** If
   a stream rebuild happens mid-count-in or mid-take,
   `mArmFrame`/`mDownbeatFrame`/`mNextClickFrame` are untouched (they're
   expressed in `mStreamFrameCounter` terms, which keeps counting through a
   rebuild) — so the beat grid shouldn't shift. This is reasoned, not tested
   on a device; if you unplug headphones mid-count-in specifically (as
   opposed to mid-take, which Phase 1 already asked you to test), that's a
   new scenario worth checking.

## Measuring the Done criterion

The plan's bar for this phase is "across 5 takes, transients sit at a
constant offset from gridlines with spread < 3 ms" — not a thing you can
verify by ear. `tools/measure_click_alignment.py` (stdlib-only, no venv
needed) does it directly:

1. Set a BPM on the Diagnostics screen, tap "Arm & record", either tap/clap
   along with the count-in and beats or just let the click bleed into the mic
   via the phone's own speaker (both are legitimate — see the script's
   docstring for which one you're actually testing).
2. Stop recording, pull the file: `adb pull
   /data/data/com.songnotes.android/files/takes/phase2_test.f32 .`
3. Run: `python tools/measure_click_alignment.py phase2_test.f32 <sample_rate> <bpm>`
   (read the sample rate off the Diagnostics screen's capability readout).
4. Repeat 5 times. Compare the printed "mean offset" and "spread" across
   runs — a consistent mean offset is fine (that's exactly what Phase 3's
   calibration is for); a spread that blows past a few ms, or a mean offset
   that jumps around between takes, is this phase's actual bug to chase.

## What Phase 3 assumes

- **`headSkipFrames` is currently just `preRollFrames`** (zero latency
  compensation). Phase 3's job is producing a real measured round-trip
  latency number and folding it into this same computation
  (`headSkipFrames = preRollFrames - measuredCompensationFrames`, passed to
  `armRecording`/the writer thread) — the pre-roll/head-skip *mechanism*
  built this phase doesn't change, only the number fed into it does.
- **The metronome click IS the acoustic signal Phase 3's automatic
  calibration will need to detect** in a loopback recording — though Phase 3
  uses a proper exponential sine sweep + matched filter for the actual
  calibration measurement (per the plan, explicitly not a click, for the
  processing-gain reasons documented there), not this metronome click.
  Don't confuse the two: this click is for musicians to play along with;
  Phase 3's sweep is a separate, inaudible-in-spirit measurement signal.
- **`EngineMode` now has five values** (`Idle`, `Armed`, `Recording`,
  `Playing`, `TestTone`). Phase 3 will likely need at least a `Calibrating`
  mode — extend the enum and the `onAudioReady` mode switch the same way this
  phase extended Phase 1's four-value version, rather than layering a
  parallel state machine on top.
- **No device numbers recorded here** — never run on real hardware. Once
  you've built this and run the 5-take measurement above, it's worth
  appending the actual mean-offset/spread numbers here (and which device)
  so Phase 3 has a real baseline instead of a guess about how far off
  "uncalibrated" actually is.
