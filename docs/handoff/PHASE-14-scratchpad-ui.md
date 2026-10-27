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
