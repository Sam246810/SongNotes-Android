# Phase 9 — Import / export / piano

**Status (2026-08-06): Partial.** Export (text + PDF) shipped and verified
on-device. PDF import and piano are explicitly not started — see "What's
left" below for why each was scoped out of this pass.

## Scope decision

The plan's one-line spec bundles three fairly different features. Before
starting, a quick survey found:

- **PDF import**: exists on the web app (`src/utils/pdfImport.js`, via
  `pdfjs-dist` — extracts text per page, then reconstructs line breaks from
  each text item's on-page position). Nothing on Android to build from;
  would need a new PDF text-extraction library ported to Kotlin. **Explicit
  user direction: skip this entirely** — not planned for either client
  going forward as part of this phase.
- **Export**: exists on web but is thin — `exportToText` (plain text) plus
  `exportToPdf()`, which is just `window.print()` relying on `@media print`
  CSS (no real PDF-generation library). Android had nothing. Chosen scope:
  "Export text" copies formatted text to the clipboard; "Export PDF"
  generates a real PDF file and opens the share sheet.
- **Piano**: the "29 Salamander samples" are fetched from a CDN at runtime
  on web (`PianoPanel.jsx`), not bundled assets — confirmed nothing
  Salamander-related exists anywhere in this repo. Android's C++ mixer
  (`core/audio/src/main/cpp/dsp/track_mixer.*`) only mixes already-in-memory
  fixed-rate `float` buffers; there is no audio-file decoder (WAV or
  otherwise) and no pitch-shift/resample capability at all. This is real
  native DSP work — closer in weight to Phase 3's calibration engine than
  to a UI feature — and was explicitly deferred to be tackled after export.

## What shipped

**`formatSongAsText` (`core/domain/.../Export.kt`)** — ported from the web
app's `exportToText` (Ultimate-Guitar-style chord-line-above-lyric-line
plain text), verified byte-for-byte against `spec/export-to-text.json`
(fixtures generated from the real JS function) by `ExportGoldenFixtureTest`.
One porting subtlety: the JS version reads `line.chords` as the app's
in-memory padded-string track directly; the Kotlin `Song` model only has
[ChordAnchor] lists, so each line's chords are rendered back to that padded
form via `anchorsToChordsLine(line.lyrics.length, line.chords)` first. The
fixture generator had to be fixed to align each fixture's chords/lyrics
pair via `alignChordsWithLyrics` before use — real app data always
maintains that invariant (every edit runs it), and an unaligned hand-written
fixture doesn't round-trip through the anchor representation for a reason
that has nothing to do with `exportToText` itself.

**`copySongTextToClipboard` (`app/.../ExportActions.kt`)** — wired to the
new "Export text" button in `SongEditorScreen.kt`'s button row. Copies
`formatSongAsText(song)` via `ClipboardManager`, shows an explicit `Toast`
("Copied to clipboard") for pre-Android-13 devices, which have no built-in
clipboard-copy system feedback (13+ already shows its own).

**`shareSongAsPdf` (`app/.../ExportActions.kt`)** — the "Export PDF" button.
Renders a real paginated PDF via `android.graphics.pdf.PdfDocument`: title
(bold, black), then each line's chords (monospace, the same accent color
`SongEditorScreen.kt` uses on-screen) directly above its lyrics (default
typeface), US Letter page size, paginating when content overflows a page.
This is the mobile equivalent of the web app's `window.print()` "print to
PDF" flow — Android has no OS print-preview dialog to lean on the same way,
so this builds a real file and hands it to Android's share sheet
(`Intent.ACTION_SEND`, `type = "application/pdf"`) via a new `FileProvider`
(`app/src/main/res/xml/file_paths.xml` exposes only `cache/exports/`,
nothing else in app storage). No text wrapping for lines wider than the
page — matches this project's "don't build what nothing needs yet"
discipline; revisit only if a real song's lines are wide enough to hit it.

## A real bug during development, and a false alarm during verification

**Real bug, caught immediately**: `file_paths.xml`'s first draft had a `--`
inside an XML comment (used as a word-separator, matching this codebase's
own Kotlin-comment convention, which has no such restriction) — invalid in
XML comments, same exact class of error as an earlier `AndroidManifest.xml`
incident in Phase 7. Fixed by rewording to `;`.

**False alarm during on-device verification**: pulling the generated PDF
off the device with `adb shell run-as ... cat cache/exports/Demo.pdf >
file` produced a file that opened as a *structurally valid but entirely
blank* PDF — looked exactly like a real rendering bug (right page size,
right fonts referenced, zero visible content). Root cause turned out to be
the pull command, not the app: `adb shell` is not binary-safe (it can
insert `\r` before `\n` bytes, corrupting binary data in transit); the
Flate-compressed PDF content stream came through corrupted and decoded to
nothing. Re-pulled with `adb exec-out run-as ... cat ...` (binary-safe) and
the same file rendered exactly as expected — title, colored chord line,
lyrics, all correctly positioned. No app code changed; this is purely a
verification-methodology note for next time: **always use `adb exec-out`,
never `adb shell`, to pull a binary file off a device.**

## Verified end-to-end on a physical device

Opened the shipped "Demo" song. "Export text" copied the correctly
formatted text to the clipboard (confirmed by pasting into a scratch field
and reading it back — byte-for-byte matched `formatSongAsText`'s expected
output, including the padded chord-line spacing and the per-line
chords-only/lyrics-only/both branches). "Export PDF" opened Android's real
share sheet showing a correctly named `Demo.pdf`; the generated file (pulled
correctly this time) renders exactly as intended.

Both repos' full test suites pass: Android (`:core:domain:test`,
`:core:data:testDebugUnitTest`, `:app:assembleDebug`) and web
(`npx vitest run`, 267/267).

## What's left (deliberately deferred)

- **PDF import** — explicitly out of scope per direction, not planned.
- **Piano** — genuinely not started. Needs, at minimum: (a) an audio-file
  decoder in `core/audio`'s C++ (WAV is the realistic target on NDK; MP3
  decoding is harder, so re-encoding the 29 Salamander samples to WAV as
  bundled build assets is likely the right call rather than decoding MP3 on
  device), (b) variable-pitch/resampled playback of a loaded sample against
  a target MIDI note, layered onto the existing fixed-rate `Clip`/`Track`
  mixing model so it's recordable through the same mixer Scratchpad already
  uses, matching the plan's own criterion.
- **No text wrapping in the PDF export** for a chord/lyric line wider than
  the page.
