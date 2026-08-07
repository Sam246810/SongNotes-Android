# Phase 10 — Scratchpad product UI

**Status (2026-08-07): timeline, waveform, clip drag/trim, and scrub-to-punch-in
all shipped and on-device verified** — closes out the plan's Phase 10 line
item (minus a few explicitly-deferred UI-polish items, see "What's left").
Built in two passes: the initial Scratchpad screen + persistence
(2026-07-30, described below), then the timeline/waveform/drag-trim/scrub
work (2026-08-07) once a peak-pyramid C++ DSP primitive existed to render
waveforms efficiently at any zoom level.

Phase 4's internals (real-time multitrack playback, real overdub
recording, punch-in splicing, WAV export, the JVM reference mixer, and
`MultitrackProject` as the state to drive all of it) were built and
verified first, per explicit instruction to keep UI work scoped to "only
when the internals need it." With every one of those verified end-to-end
on device, a first real UI became the natural next step rather than more
diagnostics-screen plumbing. Persistence was added in the same pass, on
the judgment call that a scratchpad which loses everything on close isn't
really usable — see "Persistence shipped" below. **Deliberately minimal
otherwise** — this is "does the engine work reachable from actual UI," not
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
  same real-time mixing verified in Phase 4. **(As of 2026-07-30) punch-in
  always starts at project frame 0** — the whole song plays as the backing
  track for every take. There's no timeline/scrub UI yet to pick an
  arbitrary punch-in point, so this was the honest scope for this pass.
  *Closed 2026-08-07 — see "Timeline, waveform, clip drag/trim,
  scrub-to-punch-in" below.*
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

## Persistence shipped (2026-07-30)

**`MultitrackProjectStorage.kt`** (new, in `:core:audio`) — persists a
`MultitrackProject` as one `manifest.json` (track gain/muted/soloed, and
per-clip `startFrame`/`bufferOffsetFrames`/`lengthFrames`) plus one raw
`.f32` file per clip holding its actual audio, at a single fixed
`context.filesDir/scratchpad/` location (no multi-project management UI
exists yet, so there's only ever "the" scratchpad to persist). Uses
`org.json` (built into Android, no new dependency) for the manifest —
matches `CalibrationStore`'s own "small scoped storage class, no
front-loaded data layer" precedent, just applied to bigger (audio) data
than a few key-value pairs.

`ScratchpadScreen` auto-loads once on first composition, auto-saves after
every mutation that matters (a committed recording, adding/removing a
track), and exposes an explicit "Save" button for gain/mute/solo tweaks —
those specifically aren't auto-saved on every slider drag tick (that would
mean a file write per pixel of drag), so a deliberate Save is how they get
persisted.

**Verified on device**: recorded a take, confirmed the manifest + `.f32`
clip file on disk via `run-as` (clip file was exactly `lengthFrames × 4`
bytes, matching the manifest), then `am force-stop`'d the app entirely and
relaunched — the scratchpad reopened with the exact same track/clip state
auto-loaded from disk, and **playback of the reloaded audio worked
correctly** (`172032 / 1309440` frames, matching the original recording's
length exactly) — not just that the metadata round-tripped, but that the
actual audio samples did too. Zero crashes across the whole sequence.

## Timeline, waveform, clip drag/trim, scrub-to-punch-in (2026-08-07)

**`dsp/peak_pyramid.h`/`.cpp`** (new, `:core:audio`'s C++) —
`buildPeakPyramid` computes a multi-resolution min/max waveform: level 0
chunks the raw buffer into `baseSamplesPerPeak`-sample peaks, each level
after that combines adjacent pairs from the level below (doubling
`samplesPerPeak`), stopping once a level would have fewer than
`minPeaksPerLevel` peaks. `selectLevelForZoom` picks the coarsest level
whose `samplesPerPeak` still fits a caller's samples-per-pixel budget, so
rendering never walks more samples than there are pixels for regardless of
zoom. 9 GoogleTest cases written and registered in `host/CMakeLists.txt`
alongside `dsp/piano_voice.cpp`'s tests — like those, compile-checked
clean (all 3 ABIs, `-Wall -Wextra`, zero warnings) via
`buildCMakeDebug`, never actually executed (no desktop C++ compiler on
this machine; same documented limitation as every earlier phase's host
tests). Judged lower cross-language risk than piano's floating-point
envelope math (this is integer chunking/comparison logic), so — unlike
piano's bit-exact on-device cross-validation — no separate
Kotlin-vs-C++ agreement check was built for this one.

Marshaled across JNI as a self-describing flat `FloatArray`
(`nativeBuildPeakPyramid` in `jni_bridge.cpp`) rather than constructing
nested Kotlin objects from native code: `[numLevels, (samplesPerPeak,
peakCount, min0, max0, min1, max1, ...) per level]`, decoded by
`AudioEngine.buildPeakPyramid` into `PeakLevel`/`PeakPyramid` data classes
(`AudioEngine.kt`). Stateless — no engine handle needed, same shape as
`renderPianoVoiceNative`.

**`Waveform.kt`** (new) — pure-rendering Compose `Canvas` composable,
same pattern as `ChordDiagram.kt` (Phase 8): layout math lives in
`DrawScope` extension functions, the composable itself just measures and
draws. Takes a pre-built `PeakPyramid` plus a clip's
`bufferOffsetFrames`/`lengthFrames` trim window (mirroring
`MultitrackClipSpec`'s own field names) and draws min/max bars for
exactly that trimmed view, picking the zoom-appropriate pyramid level via
`PeakPyramid.selectLevelForZoom`.

**`Timeline.kt`** (new) — one row per track, each clip drawn as a
`Waveform` positioned/sized against the project's `totalFrames`, plus a
tappable scrub ruler above the rows and a single position-marker line
(red while playing, at the live playback frame; blue otherwise, at the
scrub/punch-in frame) spanning the ruler and every track row. Peak
pyramids are built once per clip buffer (`remember(clip.buffer)`), not on
every recomposition. Wired into `ScratchpadScreen` right below the track
summary text, above the per-track gain/mute/solo rows.

Each clip supports two touch gestures when the timeline isn't disabled
(disabled during recording/playback, same as the rest of the screen's
controls):
- **Whole-clip drag** (anywhere in the clip's waveform) moves its
  `startFrame`.
- **Trim handles** at each edge (`TrimHandle` composable, a narrow
  draggable strip) resize it — the left handle moves
  `bufferOffsetFrames`/`startFrame` together so the untrimmed audio's
  timeline position doesn't jump, and shrinks `lengthFrames`; the right
  handle only changes `lengthFrames`.

Both gestures are live-previewed locally (`dragPx`/`leftTrimPx`/`rightTrimPx`
state in `TimelineClip`) during the touch and only committed on release —
same "no callback per pixel of drag" reasoning already established for
the gain slider elsewhere in this screen.

**Scrub-to-punch-in**: a `scrubFrame` state var in `ScratchpadScreen`,
settable by tapping `Timeline`'s ruler. `beginRecording()` now passes it
as `armOverdub`'s `backingTracksStartFrame`, and the committed take's
`MultitrackClipSpec.startFrame` uses the same value — both were previously
hardcoded to `0L`, which was Phase 10's originally-documented "always
starts at project frame 0" gap (see the old "What's left" below, now
closed).

**Same-track overlap invariant**: partway through building clip drag/trim,
explicit user direction clarified that two clips on one track must never
overlap during playback — recording over an existing clip should replace
it, not sum with it, and the same rule applies to dragging/trimming.
`MultitrackProject.withClipTransform` (the mutation hook both gestures
call) doesn't just splice the transformed clip into its track's list in
place; it re-runs it through `AudioEngine.punchIn` — the exact same
splicing primitive `withPunchIn` already uses for recording — against the
track's *other* clips, so a drag/trim that lands on top of a neighbor
trims or drops the neighbor's overlapped region instead of leaving both
clips there.

### Verified on device (2026-08-07)

Built and installed the updated APK on a physical device (`RFCX70MEMRX`),
drove it via `adb shell input tap`/`swipe` + `uiautomator dump` +
screenshots, same workflow every phase has used:

1. **Waveform rendering**: recorded a real ~23s take onto a fresh track —
   the timeline row showed the actual recorded audio's min/max peaks, not
   a placeholder.
2. **Playhead**: hit Play, screenshotted mid-playback — the marker line
   sat at the correct fractional position for the reported engine state
   (`152160 / 1124918` ≈ 13.5%, matching its on-screen x position).
3. **Whole-clip drag**: dragged a clip right — project total grew from
   23.4s to 27.8s, matching the drag distance; no crash.
4. **Right trim**: dragged the right handle left — total shrank from
   27.8s to 22.7s; no crash.
5. **Left trim**: dragged the left handle right — total stayed at 22.7s
   (the clip's *end* frame is invariant under a left-trim by design:
   `startFrame` and `lengthFrames` move by equal and opposite amounts),
   while the clip visibly shifted right and narrowed. Played the trimmed
   project back afterward and confirmed the engine actually honors the
   new `bufferOffsetFrames`/`lengthFrames` — reported playback total
   frames matched the *trimmed* duration (1090887 frames ≈ 22.7s @
   48kHz), not the original take's — proving the trim reached the real
   mixer, not just the timeline's visual.
6. **Scrub-to-punch-in**: tapped the ruler to set a 12.8s punch-in point,
   recorded a new take onto a second track, and confirmed the committed
   clip's `startFrame` + recorded length summed exactly to the new
   project total (12.8 + 152.3 = 165.1s) — landed at the scrubbed point,
   not frame 0.
7. **Same-track overlap splicing**: recorded two short non-overlapping
   clips onto one track (clip A at 0s, clip B at 19.3s; track total
   38.7s), then dragged clip B on top of clip A. Track total collapsed to
   exactly clip B's own length (19.3s) with a single continuous
   edge-to-edge waveform in the timeline row — no stacked/overlapping
   region — confirming clip A's overlapped range was spliced away via
   `punchIn`, not left layered underneath clip B.

`logcat` checked after every step above — **zero crashes, zero
`AndroidRuntime`/`FATAL EXCEPTION` entries** across the whole sequence.

### Bug hit during this verification (tooling, not app)

Not an app bug: mid-sequence, taps on "Stop recording" repeatedly failed
to register once the screen had grown tall enough (two tracks + the new
timeline + ruler + punch-in text) that the button's accessibility bounds
fell entirely within the 3-button system nav bar's zone at the bottom of
the screen — a more severe case of the nav-bar tap-target overlap already
seen in Phase 9. Recovered by scrolling the screen up (`input swipe`)
before tapping, then re-reading bounds fresh from a `uiautomator dump`
taken immediately before the tap (not reused from an earlier dump, since
the fling-scroll was still settling when the first re-tap attempt fired
too early). No code changed for this — it's a testing-methodology note,
not a layout bug in the app (a real user scrolling normally wouldn't hit
this).

## What's left (not started)

- **BPM/time signature are effectively fixed.** The BPM field exists, but
  beats-per-bar and count-in beats are hard-coded to 4/4 with a 4-beat
  count-in, matching every other recording flow in this codebase so far —
  no per-project or per-take override.
- **No metering, no undo.** Standard DAW-adjacent features, out of scope
  for "prove the engine is reachable from real UI."
- **No minimized/collapsible transport strip, no theme-in-settings.** The
  plan's Phase 10 line also mentioned a "minimized transport strip, DAW
  collapsible to tempo/BPM/start-stop" layout and moving the theme toggle
  into a settings surface — both pure UI polish with no engine dependency,
  not attempted this pass.
- **The metronome click during recording still isn't toggleable** — a
  Phase 4-documented gap ("Known risks" #6 there) that surfaces here too:
  every overdub take plays the count-in/metronome click over the backing
  tracks, with no way to turn it off once past the count-in.
- **`host/test_peak_pyramid.cpp` has never actually run** — compile-checked
  as part of the real `.so` build only, same standing limitation as every
  other host GoogleTest target in this repo (no desktop C++ compiler on
  this machine).

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
4. **`MultitrackProjectStorage.save()` isn't atomic.** It deletes every
   existing clip file, then writes the new set, then writes the manifest
   last. A crash or kill between the delete and the last clip write would
   leave a partially-overwritten project on disk — the next `load()` would
   either fail (missing a file the manifest references) or silently load
   a stale mix of old and new clips. Not exercised by the device
   walkthrough above (which didn't kill the process mid-save); a real
   fix would write to a temp location and rename over the old one only
   after everything succeeded.
5. **Gain/mute/solo changes are only persisted on explicit Save**, not
   automatically — documented as a deliberate tradeoff (continuous
   auto-save on every slider drag tick would mean a file write per pixel
   of movement), but it does mean a user who adjusts a slider and force-
   quits without tapping Save loses that specific change, even though
   their recordings are safe (those auto-save on commit). Worth surfacing
   more visibly in the UI than a plain "Save" button if this trips
   someone up in practice.
6. **Every `save()` rewrites every clip's full audio, even unchanged
   ones** — fine at the scale exercised so far (a couple of tracks,
   under a minute each), but would get slow for a genuinely large
   project. No incremental/delta save exists.
