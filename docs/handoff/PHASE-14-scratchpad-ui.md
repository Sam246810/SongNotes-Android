# Phase 14 — Scratchpad UI overhaul + per-song demos

Goal: make the Scratchpad look and behave like a small Ableton 12
Arrangement view in the app's paper-notebook style, and make "every song has
its own arranged demo, saved on the phone, offline, account or not" something
the app can be trusted with. The user's rule for UI and basic behaviour is
**"do it like Ableton 12"**, scaled down to a songwriting notes app (no
automation, MIDI, or effects). Work is on branch `scratchpad-ui-overhaul`, one
section per heading below, each written when that section landed.

To-do carried forward (not this phase):
- **Recordable piano in the Scratchpad** — the piano as a track source.
- **Stream takes from disk** instead of holding them in memory, which would
  lift the 10-minute take cap and the project-size ceiling (PHASE-10).

## §1 Demos are never lost by accident

**The bug.** `SyncEngine` gives a song a new UUID in two places:
`reconcileForAdoption`, when the account's row with the same id is deleted
or unreadable, and `pushInsert`'s retry after an insert collision. A
song's Scratchpad demo lives on disk at `filesDir/scratchpad/<songId>/`,
outside Room, so a re-id left it under the old id. `SongListScreen`'s
`sweepOrphanedScratchpads()` deleted every folder whose id had no live song
row, on every visit to the Songs list. Result: a re-id'd song's demo was
silently deleted the next time the list opened.

**The fix.**
- `SyncEngine` takes an `onSongReIded(oldId, newId)` callback (default
  no-op) and calls it at both re-id sites. `SongSyncWorker` passes
  `moveScratchpadDemo`, a plain directory rename, so `:core:data` still has no
  dependency on `:core:audio`. If the target folder already exists, the old
  one is left alone, not merged or overwritten.
- The sweep now deletes a folder only when its song is a **tombstone**
  (`SongRepository.isDeleted`). An id with no row at all is kept. Losing a
  demo is far worse than leaking a folder.
- Tests: `SyncEngineAdoptionTest` (deleted-on-account collision) and
  `SyncEngineTest` (insert-collision retry) both assert the callback reports
  the right old and new ids.

**What "never lost" does not mean.** The user's own edits are saved as made.
Delete one of three tracks and the saved demo has two. Reopening the song
shows two, and the deleted track is gone unless undone in that same session
(undo history is per session). Auto-save + undo already behave this way.

**Where demos live.** App-private `filesDir`: works offline with or without
an account, is invisible to other apps and the media store, so demos never
appear in Samsung Voice Recorder or the gallery. `data_extraction_rules.xml`
excludes `file` from cloud backup and device-to-device transfer, so **demos
don't move to a new phone**. The tutorial (§7) says so. Demos are not on
the web app either (a notice there is a separate change in the web repo).

**Verified:** unit tests only for the callback. The rename itself is three
lines of `File` calls; checked on device as part of the §6/§9 device pass
(a song with a demo survives open → Songs list → reopen).

## §2 Look and feel foundation

**Colour schemes.** `Theme.kt`'s Material schemes used to set only primary
and the surfaces, so every container/variant role (cards, chips, filled icon
buttons, most of the Scratchpad) fell back to Material's default purple and
lavender. Both schemes now set every role: secondary is a warm taupe
(ink on paper), tertiary a muted sage, and the surface-container ladder steps
from parchment toward a deeper kraft tone (dark: the cozy brown ladder). The
document palette (`SongNotesColors`) is unchanged, so the editor looks the
same.

**Track colours.** `TrackPalette` (in `Timeline.kt`, moving to the new
arrangement view in §3) is now muted earthy clip colours in the spirit of
Ableton's palette: amber, sage, clay, slate blue, ochre, plum.

**Nav bar.** `SongNotesTheme` now sets `isNavigationBarContrastEnforced` (and
the status-bar equivalent) to false. With 3-button navigation Android was
laying a translucent grey scrim over the bar, which is why it never matched
the theme. Every screen paints its background edge to edge, so the page
colour now shows through, with icons following the theme as before.

**Insets.** The full-screen column screens that only padded the status bar
now use `systemBarsPadding()`, so bottom buttons sit above the nav bar:
calibration wizard (the reported "Start calibration" button), manual and
tap-along calibration, auth, locked-account, recovery-unlock, sync opt-in,
and the account recovery-code view.

**Verified on device (dark theme):** calibration's button clears the nav bar,
the nav bar is the page colour, and the Scratchpad has no purple left.

## §3 Arrangement view (Ableton-style timeline)

`Timeline.kt` and `Waveform.kt` are gone; `ArrangementView.kt` replaces them.

**Why the old one looked broken.** It always scaled "project length = screen
width". Committing a drag that changed the project's length rescaled
everything, which is why a short clip "shortened" when dragged right. The
drag preview didn't apply the commit's clamp (`start ≥ 0`), which is why
dragging left jittered. And the waveform drew the clip's window stretched
to whatever width the preview had, so trimming stretched the audio instead
of cropping it.

**The model now.**
- Fixed **pixels-per-beat** zoom, independent of project length. Pinch to
  zoom around your fingers; drag empty lane space to pan (both axes). A
  fit-to-project button sits above the track headers. The view fits
  automatically when the project first has audio and whenever its width
  changes (rotation, unfold).
- All scale/grid/edit math is in `core:audio`'s `ArrangementMath` (JVM-tested
  in `ArrangementMathTest`). The live drag preview and the commit call
  **the same function**, so a clip lands exactly where it was shown.
- **Adaptive ruler**: label spacing doubles (1, 2, 4, 8… bars) until labels
  are ≥ 48dp apart, and unlabelled bar ticks thin out below 6dp. Zoomed in,
  beats get "1.2"-style labels. This fixes the overlapping bar numbers.
- **Adaptive grid + snap** (Ableton's): the grid unit is the finest of bar
  multiples → bar → beat → 1/2 … 1/128 beat whose lines are ≥ 12dp apart.
  Moves and trims snap to it, so zoomed far in it's effectively free
  placement. The grid button in the control bar turns snapping off.
- **Clips look like Ableton's**: a light body in the track colour, a darker
  header strip with the track name, and a dark waveform. Waveforms draw at
  the arrangement's absolute scale (pyramid when a pixel covers ≥ 1 peak,
  raw samples when zoomed in further), so **trim crops**. Only the on-screen
  part of a clip is laid out, because a 10-minute take at deep zoom would
  be millions of pixels wide.
- **Interaction**: tap a clip to select it, which also puts the insert
  marker there. Drag to move, including up/down onto another track.
  Selected clips show trim tabs at their real edges. A floating action bar
  offers **Split** (at the insert marker), **Duplicate** (placed right
  after) and **Delete**. Tap empty space to deselect and set the marker.
  The ruler taps and scrubs the marker.
- **Playhead**: playback now starts **from the insert marker** (native
  `startMultitrackPlayback` takes a start frame), and the view follows it a
  page at a time.
- **Recording** draws a red clip growing on the armed track from the
  marker, with a live waveform from the polled input level.
- **Tracks**: `MultitrackTrackSpec` gained `name` ("1 Audio" style, unique)
  and `colorIndex`, both persisted with defaults for old projects. Colour
  follows a track when it's reordered. New project ops: `moveTrack`,
  `renameTrack`, `deleteClip`, `splitClip`, `duplicateClip`, `moveClip`.

## §4 Scratchpad layout

- **Landscape by default** while the Scratchpad is on screen
  (`requestedOrientation`), with **Portrait view** in the ⋮ menu,
  remembered app-wide (`ScratchpadPreferences`). Other screens stay
  portrait. The manifest gained `configChanges` for orientation/size, so
  rotating (or unfolding) never recreates the activity, which owns the
  audio engine. Verified: a take keeps recording through a switch to
  portrait and saves.
- **Control bar replaces the "Session" card**, laid out like Ableton's
  control bar: back · `100 BPM` (tap: tempo dialog) · `4/4` (menu) ·
  **metronome** (tap toggles; the ▾ menu holds **Count-in: None / 1 bar /
  2 bars**, where Ableton keeps it) · grid/snap · **Play / Record** ·
  position `bar.beat.16th` + clock · undo / redo · ⋮ (export mixdown,
  portrait/landscape, calibrate, tutorial). In portrait it wraps to two
  rows. The first portrait attempt pushed ⋮ off screen (no way back to
  landscape), caught on device and fixed.
- **Track headers** beside their lanes: colour strip, name (tap → Rename /
  Volume (portrait) / Delete track), **Arm** (the record target, replacing
  "selected track"), **M**, **S**, and in landscape an inline volume
  slider. The armed track shows the input meter while recording.
  **Long-press-drag a header to reorder** (a line shows where it lands).
- **Add track** is Ableton's empty drop area below the last track: a
  dashed lane with "+ Add track" in the header column.
- Arm and Record use a fixed Ableton red (`RecordRed`), not the theme's
  error colour (pink in dark mode).
- Status messages float as a pill over the arrangement and fade after 4s;
  the Bluetooth notice floats at the top.

**Verified on device (Fold, dark theme):** landscape and portrait layouts;
fit-to-project; select → split → add track → drag the tail onto track 2;
left-trim snaps to a bar and crops; long-press reorder (colours and arm
follow, order persists); a short (4s) clip dragged hard left stops at 0 with
no jitter or shrink, and drags right cleanly; a live recording clip with the
header meter; rotation mid-take. **Not verified**: pinch zoom (adb can't
pinch; the gesture is Compose's standard `detectTransformGestures`).

## §5 Recording polish: notification and mic pointer

**Notification.** The app never requested `POST_NOTIFICATIONS`, so on
Android 13+ the recording foreground service's notification was never shown,
and its icon was a placeholder play glyph. Now:
- A mic status-bar icon (`res/drawable/ic_stat_mic.xml`), "Recording ·
  Scratchpad · <song title>", a running chronometer, and a tap target that
  brings the running app back (launcher-style intent, not a second
  activity).
- On Android 16+ it requests **promotion** (Live Updates:
  `android.requestPromotedOngoing` + `POST_PROMOTED_NOTIFICATIONS`). On
  One UI 8 that's the status-bar timer chip / Now Bar, like Samsung Voice
  Recorder. It's a request; the system decides.
- `POST_NOTIFICATIONS` is asked **once**, on the first Record (after mic
  permission), with a one-line reason; recording starts whatever the answer
  (`ScratchpadPreferences.notificationPermissionAsked`).
- `RecordingForegroundService.start(context, title)` / `.stop(context)`
  helpers; the calibration/diagnostics screens still start it plainly.

**Mic pointer.** `AudioManager.getMicrophones()` (no permission) reports
each built-in mic's position in metres. `locateBuiltinMic` takes the one
nearest the bottom (the usual recording mic) and turns it into a fraction
of the natural-orientation screen; `rotateNaturalFraction` maps that to
the current rotation. While a take is armed or recording, a pulsing red
"mic" tag with an arrow sits at the nearest screen edge, inside the safe
area. Hidden when the phone doesn't report positions or an external
(wired/USB) mic is in use.

The Fold 6 (cover screen) reports four mics; the primary is
`builtin_mic_1`, "bottom", at (0.027, 0.006) m on a 0.059 × 0.147 m
screen → 46% across the bottom edge. In landscape the tag appears on the
nav-bar side, which is the phone's natural bottom: correct.

**Verified on device:** the permission prompt appears on the first Record,
and the take still starts after dismissing it; the mic tag shows on the
correct edge during a take. **Not verified:** the notification and Live
Update themselves. Notification permission is a setting on the user's
phone, so it wasn't granted during testing, and the "already asked" flag
was reset afterwards so the user gets the prompt on their own first
Record. The inner (unfolded) display may report positions relative to the
cover screen; not checked.

## §6 Demo mini player and song-list badge

- `MultitrackProjectStorage.peekLengthFrames()` reads a demo's length from
  the manifest alone (no audio), tested in `MultitrackProjectStorageTest`.
- **Song list**: songs with a demo show a "Demo · 1:18" badge under the
  title. Lengths are read on IO whenever the list's song ids change.
- **Lyrics editor**: when the Scratchpad is hidden, not recording, the
  project has audio, and the keyboard is closed, `ScratchpadScreen` (always
  composed with the editor) shows a **Demo** bar at the bottom: play/stop,
  elapsed / total, a progress line; tap "Demo ›" to open the Scratchpad. It
  plays the arranged mix from the top through the same engine path, and
  sits clear of the editor's autoscroll button.

**Verified on device:** the demo plays from the lyrics with progress; the
badge appears on the song with a demo. The green mic dot while playing is
Android's privacy indicator: the engine always opens its input stream
alongside output (unchanged behaviour, not new in this phase).

## §7 First-run tutorial

`ScratchpadTutorial.kt`: a six-step spotlight tour over the real UI: a dimmed
screen with a rounded cut-out (offscreen layer + `BlendMode.Clear`) around
one target, plus a card with Skip / Back / Next.
1. control bar
2. Record (arm + record, the insert marker, recording while on the lyrics)
3. ruler (insert marker, pinch/scroll, finer snap when zoomed)
4. lanes (select, move across tracks, trim tabs, Split/Duplicate/Delete, Undo)
5. track headers (Arm/M/S, rename/delete, long-press reorder, add track)
6. no spotlight: **"Saved on this phone"** — auto-saved, offline, per song,
   no account needed, **not on the web app yet, coming soon** — and **"You
   can replay this tour anytime from Settings → Scratchpad tutorial."**

Targets register themselves with `Modifier.tutorialTarget(targets, key)`
(window bounds via `onGloballyPositioned`). The card goes below the target,
else above, else (a target as big as the arrangement) at the bottom over its
emptier part.

**When it shows:** once ever, on the first Scratchpad open of any song
(`ScratchpadPreferences.tutorialSeen`, set by finishing or skipping), after
a 700 ms beat so the rotation to landscape has settled. Never per song.
**Replay:** Settings → "Scratchpad tutorial" queues it
(`tutorialRequested`) for the next Scratchpad open (the Scratchpad lives
inside a song, so it can't open from Settings directly), and the row says
so. The ⋮ menu's "Scratchpad tutorial" opens it immediately.

**Verified on device:** shows on first open, steps and spotlights line up,
doesn't return on reopen, and the Settings replay plays once then clears.
The test phone's "seen" flag was reset afterwards so the user gets the real
first run.

## §8 Web notice, to-do, wrap-up

- **Web app** (separate repo, `C:\Users\Sam\Documents\SongNotes`, branch
  `scratchpad-phone-demo-notice`, committed locally, **not pushed**): a
  one-line note at the top of the DAW panel saying demos recorded in the
  Android app stay on the phone for now and seeing them on the web is
  coming soon. `vite build` is clean.
- **To-do (next phases):**
  1. **Recordable piano in the Scratchpad**: the piano as a track source
     (the native piano already renders through the same mixer and plays
     during recording, see PHASE-09).
  2. **Stream takes from disk**, to lift the 10-minute take cap and the
     project-size memory ceiling.
  3. **Demos on the web app / across devices** (the notice above promises
     this). Needs an audio sync design: the encrypted song sync carries
     JSON, not audio.
  4. Demos don't survive a phone change (`data_extraction_rules.xml`
     excludes `file`). Worth revisiting alongside #3.
  5. Pinch zoom and the Live Update notification are not device-verified.
     Check by hand.

## §9 Feedback round 1: scrolling, waveforms, rotation, Simple mode

From the user's first hands-on pass (pinch zoom confirmed working).

**Scrolling past three tracks.** Two causes. The vertical scroll limit was
computed in a function the gesture handler captured, so it used the track
count from an earlier composition: tracks added later were unreachable
(logged: `tracks=10` while 11 were on screen). The clamp now reads a
`ScrollExtent` holder refreshed every composition. Also, swiping the track
headers did nothing; they now scroll the tracks (a long press still starts a
reorder), and a slim draggable scrollbar appears on the right when tracks
overflow.

**Waveforms "virtually non-existent".** The takes really are quiet: a test
take peaked at 0.035 (about -29 dBFS), with RMS around -58 dBFS from the
phone's raw mic input, which at true scale draws as a flat line. Each
clip's waveform is now scaled for display to its own peak (capped at 40x so
near-silence stays flat) with a gentle 0.75 curve
(`waveformDisplayGain` / `waveformDisplayLevel`); the live recording
waveform uses the same scaling. **Display only**: the audio and playback
level are unchanged, so quiet takes still play back quietly (a per-clip
normalize/gain could follow if that bothers anyone).

**Rotation.** The Scratchpad now turns with the phone (`FULL_USER`, which
respects the system auto-rotate lock) instead of forcing landscape; the
"Portrait view" menu item is gone. Other screens stay portrait.

**Simple mode** (`SimpleScratchpad.kt`), the default: a multitrack
voice-notes recorder with a metronome, no bars, beats or grid.
- Ruled notebook paper with a margin line, a "Demo / song title" header.
- Layers as rounded cards: name, length, mute, solo, ⋮ (rename, volume,
  delete), and the layer's waveform across the whole demo on a shared time
  axis (at least 15 s, so a first short idea doesn't fill the card). Tap a
  waveform to move the playhead (where play and the next layer start).
- **Every take is a new layer**: the layer is only added when the take
  exists (one undo step for layer + take), so an empty take leaves nothing
  behind. While recording, a red "Recording a new layer…" card grows with a
  live waveform and the list scrolls to it.
- A cozy transport: big clock, a large record button, play, and a metronome
  pill (tap the icon for the click, tap the tempo to change it, ▾ for
  count-in). In landscape the transport sits beside the layers.
- Same project as Advanced, so switching never loses anything: ⋮ →
  "Advanced mode" / Advanced's ⋮ → "Simple mode". Mode is remembered
  app-wide (`ScratchpadPreferences.mode`).

**Tours per mode** (user's spec). The first ever Scratchpad open runs the
Simple tour (record, metronome, layers, saved on this phone), ending with
"Want more control?" → **Try it** switches to Advanced, whose own tour runs
the first time Advanced is ever used (by any route). The Advanced tour's
ruler step now says outright that the insert marker is grabbed **up in the
ruler** (tap or drag there). Settings has "Scratchpad tutorial: Simple" and
"…: Advanced"; each queues its tour (switching to that mode) for the next
Scratchpad open. The tour card now measures itself (it overflowed the
screen edge and covered its target), and round targets get a round
spotlight.

**Cozier Advanced.** Rounder clips (8dp), chips (12dp) and track buttons
(8dp), and a warm primary selection outline instead of a dark one.

**Mic tag** is smaller and no longer a Material `Surface`, which swallowed
touches: the Fold's mic sits right under Simple mode's record button.

**Verified on device (portrait, light theme):** scrolling a 12-track project
to the bottom via headers and lanes; visible waveforms; the Simple tour →
Try it → Advanced tour; recording in Simple adds a new layer with its take;
Settings rows. **Not verified:** rotation itself. Turning the phone or
changing its rotation settings wasn't done remotely.
