# Phase 5.5 — Minimum shippable lyrics+chords editor

**Status (2026-07-30): Done criterion met and verified on a physical device.**
The plan's own Done criterion — "you can write a real song on it and
prefer it to a notes app" — is satisfied by a real, working flow: create
a song, type a title, add lines, type lyrics, place chords at a cursor
position, transpose the whole song, paste in an existing chord sheet, and
have all of it survive an app restart. Deliberately simple compared to
Phase 8's real editor UI ("budget real time for typography" is explicitly
that later phase's job, not this one's) — see "What's left" below for the
honest list of rough edges.

## What shipped

**Data model, `:core:domain`** (`Song.kt`, `ChordAnchors.kt`):

- `Song`/`SongLine`/`SongMeta`/`ChordAnchor` — shaped to match
  `docs/WIRE-FORMAT-v2.md` §4's song document field-for-field (including
  `bpm`/`capo` as numbers with `0` = unset, not strings), even though
  Phase 5.5 touches neither sync nor encryption. This is a deliberate
  bet: getting the shape right now means Phase 6/7's data layer wraps
  this model in Room/crypto rather than redesigning it. Matches
  `docs/PLAN.md`'s own locked-in decision: "Chord binding: Per-chord
  anchors `{i, c}` — not the padded parallel string."
- `customChords` (wire-format §4's user-authored voicing overrides) is
  deliberately **not** modeled yet — nothing in this phase renders a
  chord diagram (that's Phase 8), so nothing would read it. Same
  "front-load only when a phase's own criterion needs it" judgment call
  applied elsewhere in this project.
- `chordsLineToAnchors`/`anchorsToChordsLine`/`transposeChordAnchors` —
  new logic (not a JS port; the desktop app has no anchor model to port
  from) written directly from wire-format §4's own conversion rules,
  needed for pasting/exporting plain-text chord sheets. 14 hand-crafted
  unit tests in `ChordAnchorsTest.kt` cover the spec's own called-out
  tricky cases: overlapping chords, a chord past end-of-lyrics, an
  all-instrumental (empty-lyrics) line, adjacent chords with zero gap.
  All pass. `transposeChordAnchors` is the anchor model's whole
  point made concrete: it reuses the already-ported `transposeChordToken`
  per-anchor and never touches `i` — no realignment step exists because
  none is needed, unlike the padded-string model transposition shifts.

**Storage, `:app`** (`SongStorage.kt`): one JSON file per song under
`filesDir/songs/<id>.json`, same "small scoped storage class, no
front-loaded data layer" precedent as `CalibrationStore` and
`MultitrackProjectStorage` — just for song documents. Plain unencrypted
local JSON, matching Phase 5.5's own scope ("local only, no audio, no
sync"); Room + SQLCipher (Phase 6) replaces the storage mechanism, not
the `Song` shape.

**UI, `:app`**:

- **`SongListScreen.kt`** — every saved song, newest-edited first, with a
  one-line lyrics preview. "New song" creates an empty `Song` and hands
  off to the editor immediately (no separate creation dialog — an empty
  title is a valid starting state, same as any notes app). Delete per
  row.
- **`SongEditorScreen.kt`** — title field; an "Import text" flow (below);
  whole-song "Transpose -1"/"Transpose +1" (maps `transposeChordAnchors`
  over every line); one `LineEditor` per line, each showing:
  - a read-only chords-above-lyrics preview line, rendered via
    `anchorsToChordsLine`, monospace;
  - an editable lyrics field, tracked as a `TextFieldValue` so the
    current cursor/selection position is known;
  - existing chords as tappable chips (tap removes — the only edit
    interaction for an existing chord right now, see "What's left");
  - a small chord-name field + "Add" button that inserts a new
    `ChordAnchor` at wherever the lyrics field's cursor currently sits.

  Autosaves on a 400ms debounce after any edit (plus an immediate flush
  on "Done") rather than requiring an explicit save action — the "prefer
  it to a notes app" bar fails immediately if the user has to remember
  to hit Save.
- **Import**: pastes into `parseLyricsText` (already ported in Phase 5),
  converts each resulting line's chords string via `chordsLineToAnchors`,
  and now also carries `parsed.meta` (Key/BPM/Capo/Tuning, if a header
  block was present) into `SongMeta` — this was initially wired to
  discard `meta`, caught and fixed during on-device testing before
  committing.
- Wired into `MainActivity` via two new `Screen` enum values
  (`Songs`/`SongEditor`) and an `editingSongId` state var alongside the
  existing plain-enum navigation — same "no navigation library yet, a
  toggle is the honest amount of infrastructure" precedent every other
  screen in this app already follows.

## Verified on the physical device (2026-07-30)

Full flow exercised via `adb` (typed text, tapped buttons, force-stopped
and relaunched the app), zero crashes throughout:

- Created a song, typed title "Amazing Grace", added a line, typed
  lyrics, tapped near the start of the lyrics field to place the cursor,
  typed "G" into the chord field and tapped Add — **the chord landed at
  exactly column 0**, confirmed visually in the rendered chords-above-
  lyrics preview.
- Tapped "Transpose -1" — **"G" became "F#"**, at the same column,
  confirming the anchor model's core promise (position independent of
  chord-text width) holds on-device, not just in the unit tests.
- Force-stopped the app completely and relaunched: title, lyrics, and
  the transposed "F#" chord all survived — genuine cross-process-restart
  persistence.
- Pasted a chord sheet with a `Key: G` / `BPM: 90` header block, one
  chord line (`G`), and one lyric line into the Import flow. Confirmed
  via the raw persisted JSON
  (`{"bpm":90,"key":"G",...,"chords":[{"i":0,"c":"G"}]}`) that both the
  meta header and the chord anchor were parsed and stored correctly —
  numbers as numbers, anchor position exactly matching the source
  column.
- Delete (both from the list and after creation) works cleanly; the
  empty-state message renders correctly with zero songs.

**One real layout bug found and fixed during this pass**: the
transpose-buttons row, laid out as a plain `Row` with no wrap or scroll,
squeezed its third button into a few pixels of remaining width, wrapping
"Transpose +1" letter-by-letter down the screen. Fixed by making the row
horizontally scrollable (`Modifier.horizontalScroll`) instead of letting
buttons shrink to fit — same "don't squeeze, scroll" fix, not yet applied
proactively to every other multi-button row in the app.

**One navigation-bar inset issue confirmed again**, same as Phase 3's
wizard Intro step: buttons pinned to the bottom of a screen (the
Import/Cancel pair, in this case) can sit partly underneath the 3-button
nav bar's tappable region — several test taps landed on the system nav
bar instead of the app during this pass before finding the safe zone.
Not a new bug, same root cause flagged in `PHASE-03.md`, still
unaddressed (`Modifier.navigationBarsPadding()` or equivalent) — now
confirmed to recur on every bottom-pinned button row across the app, not
a one-off.

## What's left (deliberately deferred, not bugs)

- **No chord-diagram rendering or `customChords` editing** — Phase 8's
  job; nothing here needs it yet.
- **No line reordering** — lines can be added (always at the end) and
  deleted, not moved. A real drag-to-reorder is more Phase 8 "budget
  real time for typography" territory than a Phase 5.5 MVP concern.
- **Chord editing is remove-only** — to change an existing chord's name,
  delete it and add a new one; there's no in-place rename/tap-to-edit
  yet.
- **No meta (Key/BPM/Capo/Tuning) UI fields** — the model and the import
  path both carry this data correctly (verified above), but there's no
  way to view or hand-edit it directly in the editor screen yet, only
  via pasting a header block.
- **`Modifier.navigationBarsPadding()` (or equivalent) not applied
  anywhere in the app** — confirmed twice now (Phase 3's wizard, this
  phase's editor) that bottom-pinned buttons can sit partly under the
  system nav bar's tappable region on this device. Worth a real,
  app-wide fix before this becomes a genuine user-facing usability
  problem rather than just an adb-testing annoyance.
- **No cloud sync, no encryption** — explicitly out of scope per the
  plan; Phase 6/7's job, and the `Song` model was shaped in anticipation
  of that migration (see "What shipped" above).
