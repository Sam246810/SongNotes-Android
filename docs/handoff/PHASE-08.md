# Phase 8 — Editor UI: chord diagrams, custom voicings, metadata

**Status (2026-08-06): Done.** Chord diagrams (Compose `Canvas`, ported
pixel-for-pixel from the web app's SVG), the full `CHORD_DB` fretboard
dictionary, custom voicing editing, and an editable BPM/Key/Tuning/Capo bar
all shipped and verified live on a physical device.

## What shipped

**`CHORD_DB`/`lookupChord` (`core/domain/.../Chords.kt`)** — Phase 5 had only
ported `CHORD_DB_KEYS` (the key set), explicitly deferring the actual
`frets`/`baseFret`/`barre` voicing data to this phase. Since `CHORD_DB` is
hand-written data rather than a function's output, it doesn't fit the
"record the real implementation's output as a fixture" strategy the rest of
this project's golden fixtures use — instead, `src/test/generate-golden-fixtures.test.js`
(web repo) gained two new fixture writers:

- `spec/chord-db.json` — a literal `JSON.stringify(CHORD_DB)` dump of the
  real JS object, never hand-retyped.
- `spec/lookup-chord.json` — fixtures from the real `lookupChord`, covering
  the `customChords`-overrides-`CHORD_DB` priority rule.

`CHORD_DB`/`lookupChord` were then hand-transcribed into Kotlin (there's no
way around a human typing ~75 chord entries once) and verified byte-for-byte
against those fixtures by `ChordDbGoldenFixtureTest.kt` — so a transcription
slip fails a fast JVM test instead of silently shipping a wrong fretboard
diagram. `CHORD_DB_KEYS` now derives from `CHORD_DB.keys` instead of being a
second hand-maintained list that could drift from it.

**`customChords` on `Song`** — added as `Map<String, ChordVoicing>`
(`core/domain/.../Song.kt`), matching `WIRE-FORMAT-v2.md` §4 and the web
app's own runtime shape exactly. Threaded through:
- `SongEntity` (`customChordsJson` column, Room migration 2→3, same
  "JSON blob, not a normalized table" precedent as `linesJson`).
- `SyncEngine`'s content-JSON push/pull. This *removed* code rather than
  adding it: the previous Phase-7-era `SyncEngine` had to fetch-and-preserve
  the remote row's `customChords` before every push, since Android's domain
  model didn't carry the field at all yet and would otherwise silently wipe
  it. Now that `Song` models it, the same optimistic-concurrency check
  (`updateWithRevCheck`) that protects every other field from a lost update
  protects this one too — a local entity's `customChords` is only stale if
  its `rev` is, which that check already catches. `SyncEngineTest.kt`'s old
  "preserves customChords despite not modeling it" test (now describing
  behavior that no longer exists) was replaced with two tests verifying
  normal push/pull round-trip, including a barre-shaped voicing.

**`ChordDiagram` (`app/.../ChordDiagram.kt`)** — Compose `Canvas` port of
the web app's `ChordDiagram.jsx`, same layout constants (`W`/`H`/`LEFT`/
`RIGHT`/`NUT_Y`/`FRET_GAP`/dot and barre radii) reused as dp, same colors,
same row/barre/dot geometry math. Text (X/O markers, fret-number label,
string names) drawn via `androidx.compose.ui.text.drawText(textMeasurer,
...)`, hand-centered against measured layout size the same way the SVG
version uses `textAnchor="middle" dominantBaseline="middle"`. Deliberately
excludes the editing UI — that's `ChordVoicingPanel` in `:app`, which wraps
this pure-rendering composable the same way the JS component wraps its own
`<svg>` inside a popup `<div>` with editing controls beside it.

**Tap-to-view/edit (`SongEditorScreen.kt`)** — the web app's popup is
hover-anchored (`ChordTokenDisplay.jsx` portals it under the hovered token
on mouse hover), which has no touch equivalent. Android uses a full-width
bottom-anchored panel instead (`ChordVoicingPanel`), dismissible by tapping
the scrim or a Close button — the mobile-appropriate equivalent of a hover
popup, not a literal port of the positioning. Each non-whitespace chord
token in `ChordTokenRow` got its own `Modifier.clickable` (tapping a token
opens the panel for that chord); whitespace/background taps still fall
through to the row's existing `onClick` (raw chords-text edit mode) — nested
`clickable`s let the more specific one win without extra plumbing.
`tokenizeChordLine` is now called with the song's `customChords` (previously
called with none), so a chord that's *only* recognized via a custom voicing
renders as a real chord token, not dimmed as unknown, matching the web app.

Editing itself mirrors `ChordDiagram.jsx`'s flow exactly: `formatFretsForInput`
pre-fills the draft from the current voicing (built-in or custom),
`parseFretsInput` validates on Save (same error copy: `"Enter 6 values
(0–24 or x)..."`), Reset clears a custom override back to the `CHORD_DB`
default (only shown when a custom override exists), and the button label
switches between "Suggest a different voicing" (built-in shown, no override
yet) / "Edit voicing" (override exists) / "+ Add voicing" (nothing at all
to show) — the exact three-way wording the JS version uses.

**`SongMetaBar` (`SongEditorScreen.kt`)** — 4 free-text fields (BPM, Key,
Tuning, Capo) matching `SongMetaBar.jsx`'s labels/placeholders exactly
(`"—"` / `"—"` / `"Standard"` / `"—"`). One deliberate divergence from a
literal port: the JS version passes BPM/Capo through as raw unvalidated
strings (`SongMeta.bpm`/`capo` are typed `String` there); Kotlin's
`SongMeta.bpm`/`capo` are `Int` (0 = unset, the existing sentinel — see
`Song.kt`), so the field's `onValueChange` filters to digits and parses
before writing back, rather than carrying an untyped string through the
domain model. Transpose stays in the existing button row above rather than
duplicating it into this bar — this bar is reference fields only, matching
what actually changed.

## A real bug found by on-device testing, not code review

The bottom-anchored `ChordVoicingPanel`'s save/edit controls sat far enough
down the screen that on the physical test device (3-button gesture nav) the
"Suggest a different voicing" / "Save" buttons rendered *underneath* the
system navigation bar — untappable, confirmed by a tap landing on the nav
bar instead of the button (visible in `uiautomator`'s dump as the button's
`bounds` overlapping the `navigationBarBackground` node's own bounds).
Fixed with `Modifier.navigationBarsPadding()` on the panel's content
column. Verified after the fix: the same button's bounds moved clear of the
nav bar and the tap landed correctly.

## Verified end-to-end on a physical device

Opened the shipped "Demo" song, tapped the "G" token → diagram popup opened
showing the correct built-in G-major voicing (dot/open-string positions
pixel-matched by inspection). Tapped "Suggest a different voicing", cleared
the pre-filled `"3 2 0 0 0 3"`, typed a barre-shaped voicing
(`"3 5 5 4 3 3"`), saved → diagram re-rendered with 6 dots at the correct
positions and a `"3fr"` base-fret label (since the highest fret > 4 pushes
`baseFret` off 1), button label switched to "Edit voicing". Fully killed and
relaunched the app process (not just navigated away) and reopened the same
song — the custom voicing was still there, confirming it round-tripped
through Room, not just held in transient Compose state. Reset back to the
default voicing afterward to leave the shipped "Demo" song's own content
unchanged. `SongMetaBar`'s four fields rendered correctly with the expected
placeholders on that same song (no metadata set yet).

Both repos' full test suites pass: Android (`:core:domain:test`,
`:core:data:testDebugUnitTest`, `:app:assembleDebug`) and web
(`npx vitest run`, 266/266).

## What's left (deliberately deferred)

- **No `locked`/read-only concept exists yet** — the web app's `ChordDiagram`
  hides its editing controls when a song is locked/shared read-only; Android
  has no such state yet (nothing in this project has built sharing), so the
  editing controls are unconditionally shown.
- **Transpose does not re-key `customChords`**, matching the web app's own
  behavior exactly (documented as deliberate there too): transposing a
  chord's *name* (e.g. F#→G) never migrates a custom voicing stored under
  the old name, so it can become orphaned. Not a regression introduced by
  this port — carried over faithfully from the source behavior.
- **No barre editor** — same as the web app, a custom voicing can only ever
  be `frets`/`baseFret` via the 6-value text editor; `barre` only ever
  appears on `CHORD_DB` entries.
