# Phase 16 (plan): record from the lyrics, Simple mode as a voice note

Status (2026-10-29, branch `scratchpad-ui-overhaul`): **steps 1–5 built**,
plus the follow-ups below. Steps 2–4 were committed as a791351
(2026-10-27); everything since then is not committed yet.

## For you to test (needs ears or two fingers; I couldn't check these)

1. **Metronome stays on the song's beat across takes (step 1).** Built and
   unit-tested (`dsp/record_click`, `host/test_record_click.cpp`: built with
   the NDK and run on the Fold through a GoogleTest shim, 6/6 pass). Never
   *heard*, because the phone was muted. To test, with headphones and the
   metronome on:
   - Record a few bars, stop partway through a beat, then record again.
     The count-in should lead into the song's next beat, not restart the
     beat from wherever the last take stopped.
   - Play the whole note back with the metronome on. The clicks should
     stay with both takes.
   - In Advanced, punch in on beat 3 with a one-bar count-in. The accent
     (high click) should fall on the bar's beat 1, not on the first click.
2. **Pinch to zoom in Simple mode** (adb can only send one finger):
   - Pinch the waveform in and out. The "N× · fit" badge should reset the
     zoom.
   - While zoomed, drag the playhead to either edge. The view should glide
     along like the Advanced ruler.
   - Drag a trim handle against an edge while zoomed. Same glide.
3. **Landscape Simple mode** (only seen mid-rotation): the bottom bar, and
   the voice-note block staying centred and on the ruled lines.
4. **Bluetooth warning layout fix:** it can only be seen with Bluetooth
   audio connected.
5. **Smaller items:**
   - Autoscroll on a song long enough to scroll.
   - The lyrics bar hiding under a chord's panel or the piano.
   - The Simple tour's new steps.

## Checked on the Fold

- **Lyrics bar:** count-in dots and recording state, takes landing on the
  picked track, undo.
- **Simple mode:**
  - One centred voice-note block on ruled paper.
  - Text sitting on the lines.
  - The track name (renamable) and the ▾ track picker.
  - Trim and Delete buttons. Delete removes the track and leads to the
    "No track picked" page; Undo restores it.
  - The "Play along with N tracks" toggle, including switching it
    mid-playback.
  - A timeline sized to what you hear.
  - The real waveform while recording (read from the take file), with the
    earlier audio leading in.
- **Recording from the playhead:**
  - The playhead is parked at the end by default.
  - Moved back, the take records over what it covers, and the caption
    says so.
- **Trim:**
  - The cut parts are hatched red with "✂ cut" and "Cuts N s".
  - Bracket handles with tabs. The playhead stays draggable, and taps
    seek.
  - The zoom state survives edits (fixed a stale-state bug).
- **Advanced:**
  - The Simple | Advanced switch.
  - The "In Simple" label on the picked track.
  - Sleeker clip trim tabs.
  - Ruler edge-scroll and lane-tap placement (both fixed for a stale zoom
    level).
- **Elsewhere:**
  - "Where's the mic?" in the ⋮ menus (no automatic pointer).
  - Autoscroll moved to the toolbar.

What step 1 does: `planRecordClick` puts the count-in and metronome on the
project's beat grid. A take starting between beats counts in to the next
grid beat, and the take still lands at its start point. Bar accents follow
the song's bars, so a punch-in on beat 3 no longer accents beat 3.

## The idea in one paragraph

Every song gets one **voice note**, and it *is* Track 1 of the Scratchpad.
You record it straight from the lyrics page using the bar at the bottom of
the sheet (today's `DemoMiniPlayer`), with a metronome switch and a
count-in, and the lyrics stay on screen (autoscroll included) while you
sing. Simple mode becomes a Samsung Voice Recorder–style screen for that
one recording: no layers, no grid, no tracks. Advanced mode is unchanged and
is the only place you can multitrack; there, the voice note is just Track 1.

## What already exists (and what we reuse)

- `ScratchpadScreen` is composed unconditionally under the editor, so all
  record state, permission launchers, the foreground service, the take cap
  and auto-save already work while the lyrics are showing. The lyrics bar
  lives in its `else` branch (`ScratchpadScreen.kt` ~line 840), so it can
  call `onRecordPressed()` directly. No new state owner needed.
- Back-press / leave-the-song safety (`isScratchpadRecording` gate in
  `SongEditorScreen`) already covers a take started from the lyrics.
- Tempo comes from the song's chart BPM (`songBpm`), so "the metronome can
  already be set" holds — the bar only needs the switch and count-in.
- `project.metronomeOn` / `project.countInBars` are per-song, persisted, and
  live (`engine.setMetronomeOn`). Count-in always clicks, Ableton-style.
- Punch-in (`withPunchIn`) already splices a take over part of a track.

## Behaviour

### The voice note (Track 1)

- Always `project.tracks[0]`. If the song has no tracks, recording creates
  one named "Voice note". Positional, like "the top track" in Ableton: if the
  user reorders tracks in Advanced, whatever is on top is the voice note.
- **Record starts at the playhead**, which waits at the end of the track
  by default (on open, on picking a track, after each take), so a take
  carries straight on. Move it back and the take records over what it
  covers; the caption under the clock says so, and Undo brings the old
  audio back. (Built append-only on 2026-10-27; changed to this on the
  user's request 2026-10-29.)
- Stop saves (auto-save already does this). No separate "Save" step.

### The lyrics recorder bar (replaces `DemoMiniPlayer`)

Full width at the bottom, hidden while the keyboard is up (as today).

```
 idle, nothing yet:  [ ● Record ]          [♩ on ▾]
 idle, has a note:   [ ● ] [ ▶ ]  0:42 ━━━━━○──── 1:30   [♩ ▾]   [⤢]
 count-in:           [ ■ ]   ● ● ○ ○  "count-in"
 recording:          [ ■ ]   ● 0:17   ▁▃▅▂▇▃ (level)      [♩]
```

- `●` Record / `■` Stop. Same permission + notification flow as now.
- `▶` plays the whole demo (every unmuted track), from the playhead.
- Thin seek line: tap/drag to move the playhead (reuses `seekSimple`).
- `♩` metronome switch: tap toggles click; `▾` opens Count-in Off / 1 / 2
  bars (the menu `MetronomePill` already has — extract and share it).
  Tempo is shown read-only next to it, from the chart.
- `⤢` opens the Scratchpad (current "Demo ›" tap).
- Count-in shows beat dots on the bar itself, big enough to read at arm's
  length. The red recording state replaces `MinimizedRecordingBubble` (a
  take started from inside the Scratchpad and then hidden shows the same bar).

### Autoscroll button (per your note: move it)

The floating Play button at bottom-end goes away so the bar can take the
full width. Recommendation: **move autoscroll into the top bar** as an icon
next to Scratchpad / Piano; while it runs, the speed pill shows at the top
of the lyrics instead of the bottom. The touch-to-cancel behaviour stays.
Optional extra: a "Start autoscroll when I record" toggle in the bar's `▾`
menu, so you can hit Record and both start together after the count-in.

### Simple mode (the Scratchpad, simple)

Rebuilt as a voice recorder for Track 1 only:

- One big waveform of the voice note (Samsung style: while recording, it
  scrolls under a fixed centre playhead; otherwise the whole note, tap/drag
  to seek). Trim stays (it already exists per layer — keep it for the one).
- Big time readout, big Record, Play, the shared metronome pill, undo/redo.
- No layer cards, mute, solo or volume. `LayerList` / `LayerCard` go.
- If the song has other tracks (made in Advanced, or by old Simple-mode
  layers), Simple plays them along and shows a chip: "+2 tracks · open in
  Advanced". It never edits them.
- "Try Advanced" stays in the overflow.

### Advanced mode

Unchanged except the voice note is visibly Track 1 (named "Voice note" by
default; renamable). This is the only place to add tracks.

### Multitracking from the lyrics?

Recommendation: **not in the first version.** The bar stays one-button
simple. If you want it later, the least confusing form is an item in the
`▾` menu, "Record on a new track (use headphones)", which plays the demo
as backing and records onto a new track at the bottom — visible only in
Advanced and in Simple's "+N tracks" chip.

## Implementation steps

1. **Engine: keep the click on the song's beat grid when recording starts
   mid-song.** Today `armRecording` starts the click at the take's own
   downbeat (`audio_engine.cpp` ~line 662: `mNextClickFrame = mDownbeatFrame
   - countInFrames`), so a take appended at 0:42.3 gets a click grid that's
   shifted off the bars of the earlier audio. Fix: snap the record start to
   the next beat after the playhead when the metronome or count-in is on
   (Kotlin side, `beginRecording`), or phase the click from
   `backingTracksStartFrame` in the engine. Snapping in Kotlin is the
   smaller change; host test in `test_click_track.cpp` either way.
2. **Record target.** In `ScratchpadScreen`, replace `recordingNewLayer` with
   a target: `VOICE_NOTE` (track 0, create if missing) or `ARMED` (Advanced's
   armed track). Simple mode and the lyrics bar use `VOICE_NOTE`; Advanced
   uses `ARMED`. After a commit, set `insertFrame` to the end of the take.
   One undo step removes a created track and its take together (as now).
3. **Lyrics recorder bar.** New `LyricsRecorderBar` in
   `ScratchpadControls.kt` replacing `DemoMiniPlayer` and
   `MinimizedRecordingBubble`; shown whenever the Scratchpad is hidden and
   the project has loaded (not only when there's audio). Extract the count-in
   menu from `MetronomePill` so both use it. Wire record/seek/metronome to
   the existing functions in `ScratchpadScreen`.
4. **Autoscroll to the top bar** in `SongEditorScreen`: remove the
   bottom-end `AutoscrollControls`, add a top-bar toggle, move the speed
   pill to the top. Remove the 88 dp end padding the demo bar used to leave.
5. **Simple mode rebuild** in `SimpleScratchpad.kt`: single-note layout,
   `+N tracks` chip, waveform that follows recording. Update the Simple
   tour (`ScratchpadTutorial.kt`: drop `simple_layers`, point at the
   waveform) and give `TutorialExample` a one-track variant for Simple.
6. **Existing songs.** Nothing to migrate: old multi-layer Simple projects
   simply show Track 1 plus the "+N tracks" chip. Rename an untouched
   default "Track 1" to "Voice note" on load? Optional; skip unless wanted.
7. **Docs + on-device test** on the Fold: record from lyrics with count-in
   (0/1/2 bars) and click on/off, continue a note, punch over the middle,
   back-press while recording, rotate, keyboard up/down, autoscroll during
   a take, open Advanced and see the note as Track 1. Update
   `PHASE-14-scratchpad-ui.md` / a PHASE-16 handoff and the web notice if
   the web app shows demos.

Rough order: 2 → 3 → 4 (lyrics side usable), then 1, then 5. Steps 2–4 alone
deliver "record from the lyrics"; 5 is the Simple-mode rework.

## Open questions

All decided: Simple plays other tracks along; Record always appends to the
end; autoscroll moved to the toolbar (second row, beside text size).
