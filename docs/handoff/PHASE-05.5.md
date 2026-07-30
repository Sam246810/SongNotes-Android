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

## Second pass, same day — chord UI redesign + crash fixes (2026-07-30)

User feedback after using the first version: the chord-placement UI was
"absolutely atrocious" — no visual way to see or control where a chord
would land before committing it (place cursor in an invisible position in
one field, type the chord name into an unrelated field, hope it landed
where intended). Also reported two crashes: one adding a line to a song,
one recording a new Scratchpad track and playing it back.

**Chord placement, fully redesigned.** `LineEditor` now renders the
lyrics as tappable text (`Modifier.pointerInput` + `detectTapGestures`),
using `TextLayoutResult.getOffsetForPosition` to translate the tap into
a character index and `TextLayoutResult.getHorizontalPosition` to
position each chord chip exactly above its target character — the same
`i`-is-a-character-index anchor model the wire format already mandates,
just with an actually-visual way to control it. Tapping shows a caret
marker at the exact target position and an inline chord-name field with
Save/Remove/Cancel right there; tapping an existing chip re-opens it
pre-filled. **Verified on device**: tapped mid-word, caret landed exactly
there; typed "G", tapped Save, the chip rendered exactly above the tapped
word; confirmed correct behavior across several (imprecise, adb-scripted)
taps. Chip padding also increased (6dp/2dp → 10dp/8dp) after the first
attempt's tap target proved too small to hit reliably.

**Crash investigation.** Found one real crash in logcat
(`SongStorage.save` → `FileNotFoundException`), but its actual cause was
self-inflicted during this session's own testing (an adb `rm -rf` on the
songs directory while an editor session was still alive with a pending
debounced save) — not something a real user could trigger. Still fixed
defensively: **`SongStorage.save()` now calls `songsDir.mkdirs()`**
before writing, so a missing directory (however it got that way) no
longer crashes the save.

Could not reproduce either of the user's two reported crashes despite
substantial effort — repeated single-line and multi-line "add line"
attempts, and single-track, two-track-overdub, and mixed-playback
Scratchpad record/play sequences, including a mid-recording
navigate-away-and-back scenario, all completed cleanly. Two real,
independently-justified fixes were made anyway:

- **`SongEditorScreen`'s `LineEditor` had a genuine Compose anti-pattern**:
  it wrote to a `MutableState` directly during composition
  (`if (fieldValue.text != line.lyrics) { fieldValue = ... }`, evaluated
  on every recomposition) instead of via a proper side effect. This is a
  known crash/infinite-recomposition risk specifically when multiple
  instances of a composable recompose together — exactly what "add
  another line" does to every `LineEditor` in the list at once — and is
  the kind of timing-sensitive bug adb's slow, deliberate scripted taps
  are unlikely to trigger even though rapid real typing might. Fixed by
  moving the sync into `LaunchedEffect(line.lyrics)`, the correct pattern
  already used elsewhere in this codebase.
- **`ScratchpadScreen` never stopped an in-progress recording if the user
  left the screen mid-take** — only the explicit "Stop recording" button
  called `engine.stopRecording()`. Navigating away (or the Activity being
  torn down) left the native writer thread and RT engine mode dangling
  indefinitely; a later `armRecording()` call does clean up a stale
  session first, but nothing guaranteed one would ever be made, and
  attempting playback/export against a still-Recording engine is exactly
  the kind of state this project should never be able to enter. Fixed
  with a `DisposableEffect` that calls `engine.stopRecording()` (and stops
  the foreground service) on final disposal if a recording was still in
  flight.

**Honest status**: the chord UI fix is fully verified and clearly the
right redesign regardless of the crash question. The two crash fixes are
well-justified by code review (both are real bugs, not guesses) but
neither was confirmed against the user's actual crash by reproducing it
first — if either recurs, the exact repeat steps (especially anything
involving rapid typing immediately before/after a structural change, for
the editor; or backgrounding/navigating mid-recording, for Scratchpad)
would help confirm or rule out these fixes as the actual cause.

**Not done**: PDF import was explicitly ruled out of scope for the
Android app per user direction — nothing to do here (it was never built;
Phase 9's plain-text/PDF import work applies to the desktop app, and
Android's own Phase 9 scope should be revisited with this in mind when
that phase starts).

## Third pass, same day — editor rebuilt to match the desktop app (2026-07-30)

Direct, blunt feedback on the second pass's chord UI: "still looks
disgustingly bad... the chords look awful and block the lyrics... i dont
think anchoring chords is going to work... i want it to actually feel
like a notes app not whatever this is." Rather than iterate again on an
Android-native design of my own invention, read the desktop web app's
actual editor source (`src/components/SongLine/SongLine.jsx`,
`ChordTokenDisplay.jsx`, their CSS) and ported its real behavior and
visual language faithfully, instead of guessing at what "notes app"
should mean.

**What the desktop app actually does, that the first two Android passes
didn't**: chords are a plain space-padded string typed directly into a
row above the lyrics — not floating badges, not tap-to-place anchors.
Blurred, that row renders as colored inline tokens
(`tokenizeChordLine`-based) sitting flush with the lyrics, not
overlapping them. A warm "paper notebook" visual language (parchment
card, red margin rule, rust-brown monospace chords over dark-sepia serif
lyrics) rather than generic Material widgets. Enter creates a new line;
Backspace at the start of lyrics merges with the previous line;
Backspace in an empty chord row on an empty line deletes it; **a line
that runs past a fixed character count automatically splits at the last
word boundary and moves focus to the new line** — this last one is
exactly the "starting the user in another line when they run out of
space" behavior that was flagged as conspicuously missing.

**Architecture decision, addressing "I don't think anchoring chords is
going to work" directly**: the wire-format anchor model
(`docs/WIRE-FORMAT-v2.md` §4, [ChordAnchor]) is kept, but demoted to a
pure storage/serialization detail the user never sees or interacts with.
The editor's in-memory working state (`EditorLine`) is plain
`chords: String, lyrics: String` — literally the padded-string model —
converted to/from anchors only at the load/save boundary via
`anchorsToChordsLine`/`chordsLineToAnchors` (already built in the second
pass, now finally used for what they were actually for). This means
Phase 6/7's sync work still gets the anchor benefits (position survives
transposition, no column-shift bugs) without the user ever having to
think in anchors while writing a song.

**Ported directly from the desktop's own store logic** (`songsStore.js`):
`splitLine`/`mergeLineWithPrevious`'s algorithms — slice both the chords
and lyrics strings at the same character index, realign the shorter
piece via `alignChordsWithLyrics` (already ported, Phase 5) — translated
line-for-line into `splitLineAt`/`mergeWithPrevious`, using a JS-`slice`-style
forgiving `sliceSafe` helper since Kotlin's `substring` throws on
out-of-range indices where JS's `slice` doesn't.

**Two real bugs found and fixed during on-device verification** (not
speculative — both directly observed):

1. **The decorative margin-line `Box` rendered as a giant solid block
   covering most of the card**, not a thin hairline, because
   `Modifier.fillMaxSize().padding(...).width(1.dp)` doesn't constrain
   width the way `Modifier.fillMaxHeight().width(1.dp)` (without the
   preceding `fillMaxSize()`) does. Purely visual, no functional impact,
   but exactly the kind of thing that reads as "lazy" if shipped.
2. **Tapping the chord token row to start editing silently did nothing**,
   reproducible every time. Root cause: the newly-composed chord
   `BasicTextField`'s `onFocusChanged` fires once with `isFocused = false`
   on its very first composition — before the `LaunchedEffect`'s
   `requestFocus()` has actually taken effect — and the naive
   `if (!focusState.isFocused) chordEditMode = false` handler treated
   that spurious initial callback as "the user tapped away," reverting
   `chordEditMode` back to `false` in the same frame it was set `true`.
   Fixed by only reacting to a **real** focus-loss transition (tracked via
   a `chordFieldHasGainedFocus` flag), not the field's initial unfocused
   state.

**Verified on the physical device**, including catching both bugs above
via actual on-screen behavior (not just code review this time — the
first fix attempt for the second bug used unverified reasoning alone in
the earlier session and was wrong until this pass actually clicked
through it):

- Chord row tap → field focuses correctly → typed "G" → IME "Next" →
  focus moves to lyrics correctly.
- Blurring the chord field renders "G" as a bold rust-colored token
  directly above the lyrics, no overlap, no floating badge.
- Force-stopped and relaunched the app: title, lyrics, and the chord
  token all survived, confirming the padded-string ↔ anchor round-trip
  at the storage boundary is correct.
- Typing that pushed a line past the character limit mid-word correctly
  auto-split at the last space boundary and carried the cursor into the
  new line's lyrics field, matching the desktop's own caret-preservation
  behavior — confirmed via an (accidental, mid-typing) real trigger, not
  a synthetic test.
- Zero crashes across this entire pass.

## Fourth pass, same day — real bugs from a real test song + layout redesign (2026-07-30)

Feedback came in against an actual song the user had been writing (not a
synthetic test), which surfaced two concrete problems the third pass
didn't catch, plus direction on where the visual design should go next.

**Bug 1 — the auto-split could break a word in half.** The character-count
threshold's fallback ("no good space found early enough, hard-split at a
fixed position") didn't care whether that position landed inside a word.
In the user's song, typing "...the early rain is pouring..." past the
limit split "rain" into " ra" stranded alone on its own line while a
duplicate " rain is pouring..." appeared on the line after — confirmed by
reading the actual persisted JSON, which is exactly this: `{"lyrics":"
ra"}` as its own line. **Root fix, not a patch**: replaced the fixed
38-character guess with real measurement. [findSplitIndex] uses
[TextMeasurer] to measure the line's actual rendered width against the
real available width (via `Modifier.onGloballyPositioned` on the lines
container) and only ever splits at an actual space — searching *forward*
past the target width if no space exists before it, so a long word always
stays whole even if that makes one line run a little wide, rather than
ever being cut. Re-verified against the exact same overflowing text from
the user's song afterward: "rain" (and every other word) now stays fully
intact, split lands cleanly between "is" and "pouring".

This also directly answers the "not enough space to write longer lines"
complaint from two angles at once: real measurement (rather than a
guessed character count) means a line only splits when it's *actually*
too wide for *this* device/font, not a conservative estimate; and —
paired with the layout changes below — there's meaningfully more usable
width now that the second pass's card-indent padding is gone.

**Font size + zoom control.** Base sizes reduced slightly (chords 14→13sp,
lyrics 16→15sp) and a session-scoped `fontScale` (0.75×–1.4×, "A-"/"A+"
buttons in the toolbar) now scales both — and because splitting is
measurement-based, changing zoom automatically changes how much text
fits per line with no separate retuning needed.

**Layout redesign, moving deliberately away from the desktop's literal
visual metaphor** (explicit user direction: "now move away from it while
preserving certain thematic things"). Both complaints pointed at the same
underlying issue — the desktop's shadowed, rounded-corner "page" card
with a red margin rule works because monitors are wide; on a phone the
~40dp reserved to clear the margin line from the text was pure waste, and
the margin line still visually cut across shorter lines' text anyway.
Removed the floating card, its shadow, its rounded corners, and the
margin rule entirely. Kept the palette (parchment background, rust-brown
chords, dark-sepia lyrics) and added a thin horizontal rule *under* each
line — not a vertical one *through* the text — as the only remaining
"ruled paper" cue, applied directly to a full-bleed background instead of
a boxed insert. This is strictly more width-efficient (every dp saved
from the card's padding/shadow margin is a dp available for actual lyrics)
as well as more "discreet," per the specific ask.

**Header/title placement**, flagged separately as "way too high, looks
unnatural" — the title had been sharing a row with the Done button flush
against the very top of the screen. Restructured to match the rhythm
ordinary notes apps (Samsung Notes, Keep, Apple Notes) use: Done alone on
its own top row with real clearance from the status bar, then the title
as a large field on its own row below with room to breathe, *then* the
toolbar. Not pixel-matched to any specific app, but the same "actions,
then title, then body" vertical structure all of them share.

**Verified on the physical device**, reproducing the user's actual
reported scenario rather than a synthetic one: retyped the same
overflowing lyric that broke "rain" before — this time it split cleanly
at a word boundary with the word fully intact, confirmed both on-screen
and in the persisted JSON. New header layout, zoom controls, and the
full-bleed no-card layout all render correctly with zero crashes. One
self-inflicted testing mistake along the way is worth noting for the
record (not a product bug): a tap intended for the lyrics field landed on
the chord row instead after the header restructure shifted every
element's position, so an entire round of typed text became individual
chord tokens instead of lyrics — caught by reading the actual persisted
JSON rather than trusting the on-screen UI state alone, which is
generally the more reliable way to confirm what a test actually did.

**Addendum — chord/lyrics row ambiguity**, flagged immediately after that
same testing mistake: "make sure its clear where lyrics go and where
chords go." With the card and margin line gone, the two rows had nothing
left distinguishing them but font/color, which isn't enough when one is
empty (an empty chord row rendered as an invisible single space). Added a
muted "Chords…" placeholder — both in the blurred `ChordTokenRow` and via
a `decorationBox` on the chord `BasicTextField` in edit mode — mirroring
the lyrics field's existing "Lyrics…" placeholder exactly. Verified on
device: a fresh line's chord row now reads "Chords…" above "Lyrics…"
even before either has content, the placeholder correctly clears once a
chord is typed, and the field's focus state (confirmed via `uiautomator`
dump, `focused="true"`) behaves the same as before — this was a purely
additive UI change with no risk to the underlying focus-handling logic.

## What's left (deliberately deferred, not bugs)

- **No chord-diagram rendering or `customChords` editing** — Phase 8's
  job; nothing here needs it yet.
- **No line reordering** — lines can be added (always at the end) and
  deleted, not moved. A real drag-to-reorder is more Phase 8 "budget
  real time for typography" territory than a Phase 5.5 MVP concern.
- ~~Chord editing is remove-only~~ / ~~tap-to-place chord chips~~ — both
  fully superseded by the third pass's plain padded-string chord row
  (type/edit the whole line's chords directly, same as the lyrics row).
  No separate per-chord edit affordance needed anymore.
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
- **Backspace-at-start-of-line (merge/delete) is best-effort**, via
  `Modifier.onPreviewKeyEvent` catching `Key.Backspace` — this works on
  the device tested (Gboard on the SM-F956W) but soft-keyboard backspace
  interception is a known Android/Compose soft spot that doesn't behave
  identically across every IME. Not verified on a second keyboard/device.
- **`SongListScreen` still uses generic Material purple**, not the new
  parchment/paper theme — only the editor was rebuilt this pass. Worth a
  matching pass so the transition from list to editor doesn't feel like
  two different apps.
