# Phase 10 — Scratchpad product UI

**Status (2026-07-30): front-loaded from its nominal position in the
plan's phase table** — Phase 4's internals (real-time multitrack
playback, real overdub recording, punch-in splicing, WAV export, the JVM
reference mixer, and `MultitrackProject` as the state to drive all of it)
were built and verified first, per explicit instruction to keep UI work
scoped to "only when the internals need it." With every one of those
verified end-to-end on device, a first real UI became the natural next
step rather than more diagnostics-screen plumbing. **Deliberately
minimal** — this is "does the engine work reachable from actual UI," not
a DAW-grade editor. See "What's left" for the real gaps.

## What shipped

**`ScratchpadScreen.kt`** (new) — a single Compose screen, reachable via a
new "Open scratchpad" button on the diagnostics screen (`MainActivity.kt`
gained a `Screen.Scratchpad` case, same plain-enum-toggle navigation
pattern every other screen already uses — no navigation library, per the
plan's own "don't front-load" note).

- **Track list**: a plain `Column` (not `LazyColumn` — see "Known risks"
  below for why that swap happened) of `TrackRow`s, each showing clip
  count/duration, a gain `Slider` (0–2x), `Mute`/`Solo` `Checkbox`es, and
  a `Remove` button. Tapping a row (outside its controls) selects it as
  the record target, highlighted via `MaterialTheme.colorScheme.primaryContainer`.
- **Record/overdub**: tapping "Record" with no track selected creates a
  new empty track and targets it; with a track selected, targets that
  track (a real re-punch, not just always-append). Drives
  `MultitrackProject.armOverdub`/`withPunchIn` directly — every other
  track in the project becomes an audible backing track during capture,
  same real-time mixing verified in Phase 4. **Punch-in always starts at
  project frame 0** — the whole song plays as the backing track for every
  take. There's no timeline/scrub UI yet to pick an arbitrary punch-in
  point, so this was the honest scope for this pass (see "What's left").
  Reuses the existing `RECORD_AUDIO` permission flow, `RecordingForegroundService`,
  and `AudioRouteDetector`/`CalibrationStore`-based Rule C calibration
  offset lookup — all established patterns from `DiagnosticsScreen.kt`,
  not reinvented here.
- **Playback/export**: "Play" calls `MultitrackProject.play`, polls
  `engine.state()` for live frame position, auto-resets when playback
  ends. "Export mixdown to WAV" calls `MultitrackProject.exportToWav` on
  `Dispatchers.Default` and reports the written file's path.

## Verified on device (2026-07-30)

Drove the actual UI via adb (screenshot + uiautomator-dump-based tap,
same workflow every phase has used) through the full loop:

1. Tapped "Record (new track)" with no tracks — created Track 1, recorded
   a real ~58.4s take, tapped "Stop recording" — committed via
   `withPunchIn`, UI updated to "Track 1 (1 clip(s), 58.4s)".
2. Deselected Track 1 (tap-to-toggle), tapped "Record (new track)" again —
   created Track 2, recorded ~19.7s **while Track 1 played back audibly**
   (real overdubbing, driven entirely from the UI, no diagnostics-screen
   scaffolding involved).
3. Toggled Track 1's Mute checkbox on and back off — UI state updated
   correctly both times.
4. Tapped "Play" — live playback progress shown (`156288 / 2802624`,
   matching Track 1's 58.4s length at 48kHz), button correctly switched to
   "Stop".
5. Tapped "Export mixdown to WAV" — reported success with a real file
   path. Confirmed via `run-as`: **11,210,540 bytes** on disk — exactly
   `44 + 2,802,624 samples × 4 bytes`, matching the project's own
   `totalFrames` (2,802,624, i.e. 58.4s) precisely.
6. `logcat` checked after every step — **zero crashes, zero
   `AndroidRuntime` errors** across the entire sequence.

## Bug found and fixed during this verification

The track list was originally a `LazyColumn` with a hard-coded
per-row-height guess (`Modifier.height((tracks.size * 92).dp)`) to give it
a bounded height inside the screen's own scrollable outer `Column`. On
device, with 2 tracks, this undercounted each row's real height (title +
Remove row, Gain slider row, Mute/Solo row is taller than 92dp) and
**Track 2 was clipped off-screen entirely** — invisible, though its state
existed correctly underneath. Fixed by replacing the `LazyColumn` with a
plain `Column` + `forEachIndexed` loop: the track list is always short (not
a virtualization-scale dataset), and the screen is already inside a
scrollable container, so a plain `Column` just takes each row's real
height with no guessing needed. Re-verified after the fix — both tracks
render fully, confirmed by the walkthrough above.

## What's left (not started)

- **No timeline/scrub UI.** Punch-in always starts at project frame 0 —
  there's no way to record a take starting partway through the song. Real
  arbitrary-position punch-in needs a playhead/timeline control this pass
  didn't build.
- **No clip-level editing.** A track's clips (especially after several
  punch-ins fragment it) aren't individually visible, movable, or
  trimmable from the UI — `MultitrackProject`/`AudioEngine` support
  multi-clip tracks fully (verified in Phase 4), but nothing in this UI
  exposes that structure to the user yet.
- **No persistence.** `MultitrackProject` lives in a `remember { mutableStateOf(...) }`
  local to `ScratchpadScreen` — closing the screen (or the app) loses
  everything. Saving/loading a project is a distinct, not-yet-started
  feature.
- **BPM/time signature are effectively fixed.** The BPM field exists, but
  beats-per-bar and count-in beats are hard-coded to 4/4 with a 4-beat
  count-in, matching every other recording flow in this codebase so far —
  no per-project or per-take override.
- **No visual waveform, no metering, no undo.** All standard DAW-adjacent
  features, all out of scope for "prove the engine is reachable from real
  UI."
- **The metronome click during recording still isn't toggleable** — a
  Phase 4-documented gap ("Known risks" #6 there) that surfaces here too:
  every overdub take plays the count-in/metronome click over the backing
  tracks, with no way to turn it off once past the count-in.

## Known risks / things to check first if something breaks

1. **The `LazyColumn` → `Column` fix above is the reason this file doesn't
   use `LazyColumn` at all** — if a future change reintroduces one (e.g.
   for a much longer track list), remember the fixed-height trap: either
   size it correctly from real row measurements, or don't give it a fixed
   height at all (nest it in a non-scrolling parent instead, or use
   `Modifier.weight` with a bounded parent).
2. **`selectedTrackIndex` is a raw `Int?` tracking a list position, not a
   stable track identity** — caught during review (not device-exercised;
   the walkthrough above never removed a track mid-flow) and fixed before
   commit: `onRemove` now shifts `selectedTrackIndex` down by one when the
   removed track was *before* the selected one, not just clears it when
   they're equal. Worth a real on-device test of `Remove` + selection
   together next time the device is in hand — this fix is reasoned, not
   yet verified against actual touch input.
3. **Recording never auto-stops** (matches `armOverdub`'s own documented
   behavior) — a user who walks away mid-recording just keeps recording
   until they come back and tap Stop. No timeout, no maximum length.
