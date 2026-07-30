# Phase 5 — Domain logic port + JVM behavioural spec

**Status (2026-07-30): Done criterion met and verified.** The plan's own
stated Done criterion — "golden cross-check: ~2000 chord strings through
both JS and Kotlin `normalizeChordName`, byte-identical" — is satisfied:
1,319 `normalizeChordName` fixtures (plus 1,915 `transposeChordToken` and
49 `transposeChordsLine` fixtures, ported as a natural bonus given how
tightly coupled `transpose.js` is to `chords.js` in the source) all pass,
byte-identical, cross-checked against the *real* JS implementation's
actual output rather than a hand-predicted expectation.

## What shipped

**Fixture generation, in the desktop `SongNotes` repo** (not
`SongNotes-Android` — this is the one part of Phase 5 that had to happen
in the other codebase, since that's where the JS source of truth lives):

- `src/test/generate-golden-fixtures.test.js` (new) — a Vitest test file
  (not a plain `node script.js`) specifically because Vitest resolves
  this project's extensionless relative imports (`chords.js` importing
  `./chords`) the same way the app itself does; plain Node's ESM loader
  doesn't support that without Vite's resolution layer. Generates three
  fixture sets by running the real `normalizeChordName`,
  `transposeChordToken`, and `transposeChordsLine` over broad,
  systematically-constructed input sets — every `CHORD_DB` entry in
  multiple notation variants (case, whitespace, jazz shorthand `-`/`+`/`°`,
  verbose `min`/`minor`/`maj` spellings, slash-bass forms), a full root ×
  accidental × quality combinatorial matrix independent of what's
  literally in `CHORD_DB`, every `ENHARMONIC` table entry, and edge cases
  (empty/whitespace input, unrecognized chords, garbage input).
- `spec/normalize-chord-name.json`, `spec/transpose-chord-token.json`,
  `spec/transpose-chords-line.json` (new) — the generated
  `{input, output}` (or `{input, semitones, output}`) pairs. **Ground
  truth, not hand-predicted** — per the plan's own "don't hand-translate
  tests" strategy: the fixture generator calls the real function and
  records whatever it actually returns, so a bug already present in the
  JS implementation would show up as an expected value in the fixture
  too (a faithful port would then correctly reproduce that same bug,
  which is the point — this cross-check proves the port agrees with the
  *actual* JS behavior, not with some idealized correct behavior neither
  side is obligated to have).
- Committed to **both** repos, per the plan (`docs/PLAN.md`'s
  Verification section: "Commit those fixtures to both repos; Kotlin runs
  parameterized tests over them").

**The Kotlin port, in this repo (`:core:domain`)**:

- **`Chords.kt`** (new): `normalizeChordName()` — a faithful line-by-line
  port of the JS version, including preserving the exact order of its
  `.replace()` chain (the `maj#7`/`maj7` rules must run before the bare
  `maj` rule, which would otherwise strip `maj` first and leave a
  meaningless `#7` — same reasoning as the JS source's own comment). The
  private `ENHARMONIC` map is ported verbatim. `CHORD_DB_KEYS` is ported
  as **just the key set** (a `Set<String>`), not the full voicing/fret
  data (`frets`/`baseFret`/`barre`) — nothing in this phase's scope
  (chord-name normalization and transposition) needs actual fretboard
  shapes; that's chord-diagram rendering, Phase 8 ("Editor UI") territory.
- **`Transpose.kt`** (new): `transposeChordToken()`, `transposeChordsLine()`,
  and the private `transposeNoteName()` helper — same faithful-port
  approach. `transposeChordToken` does NOT call `normalizeChordName` (it
  re-parses the root/accidental itself via a separate lightweight regex,
  matching the JS source's own two-independent-parsers structure, not a
  simplification introduced by the port).
- **`MinimalJson.kt`** (new, test-only): a small hand-written
  recursive-descent JSON parser for reading the fixture files. No
  dependency added for it — `:core:domain` stays genuinely
  zero-dependency, matching its own design goal, the same "hand-write it,
  it's small" call this codebase already made for the WAV encoder in
  `:core:audio` rather than pulling in a library for one file format.
- **`ChordsGoldenFixtureTest.kt`**, **`TransposeGoldenFixtureTest.kt`**
  (new): load the fixture JSON from `src/test/resources/spec/` (copied
  from the desktop repo's generated output) and assert every single case
  matches exactly — not a sampled subset.

## Verified

`./gradlew :core:domain:test` — **all passing, zero failures**:
- `ChordsGoldenFixtureTest`: 1 test, 1,319 `normalizeChordName` cases,
  all byte-identical to the JS fixtures.
- `TransposeGoldenFixtureTest`: 2 tests — 1,915 `transposeChordToken`
  cases and 49 `transposeChordsLine` cases, all byte-identical.
- `ClipMixerTest` (from the earlier JVM reference mixer work): 12 tests,
  unaffected, still passing.

No device needed for any of this — `:core:domain` is pure JVM, so this
runs in about 2 seconds on `./gradlew :core:domain:test`, the same
"first code in the project testable without a phone or NDK cross-compile"
property the JVM reference mixer work established.

## A real bug caught during this pass

Kotlin block comments **nest** (unlike Java/C's `/* */`, which don't).
The first draft of `Chords.kt`'s doc comment contained the literal text
`` `spec/*.json` `` — the `/*` inside that glob pattern was parsed by the
Kotlin compiler as the start of a *nested* comment, requiring an
additional `*/` to close before the outer doc comment's own `*/` could
close it, leaving the whole rest of the file's comment structure
misaligned and producing an "Unclosed comment" compile error pointing at
the *end* of the file (not the actual mistake's location). Same mistake
independently repeated in `MinimalJson.kt`'s doc comment. Fixed by
rephrasing both to avoid ever writing a literal `/*` sequence inside a
Kotlin comment — worth remembering for any future doc comment that wants
to mention a glob pattern like `*.json` or a path containing `/*`.

## What's left

- **Nothing outstanding against Phase 5's own stated Done criterion** —
  it's met. The rest of `docs/PLAN.md`'s `:core:domain` file inventory
  ("chords, transpose, lyricsImport, clipEngine, dragMath, calibration
  math") is only partially covered: `chords`/`transpose` (this phase) and
  `clipEngine` (the Phase 4 JVM reference mixer, `ClipMixer.kt`) are
  done; `lyricsImport`, `dragMath`, and a JVM port of the calibration math
  are not started, and weren't required by this phase's Done criterion —
  they'd be picked up if/when a later phase's own Done criterion needs
  them (matching the "front-load only when a phase's own criterion
  genuinely needs it" judgment call already applied to `:core:domain`
  and `MultitrackProject` earlier).
- **Chord voicing/fret data (`CHORD_DB`'s actual shapes) is not ported.**
  Only the key set is. A chord-diagram feature (Phase 8) will need the
  real `frets`/`baseFret`/`barre` data ported too, not just names.
- **`lookupChord`/`tokenizeChordLine` (JS-side, `chords.js`) are not
  ported.** These build on `normalizeChordName`/`CHORD_DB` but weren't
  needed for this phase's Done criterion; a future editor feature that
  needs "is this token a recognized chord, with what voicing" will need
  them.
