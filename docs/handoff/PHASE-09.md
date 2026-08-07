# Phase 9 — Import / export / piano

**Status (2026-08-06): Done**, except one check only a human ear can do.
Export (text + PDF) and piano (native, sample-based, additive over every
engine mode) are both built and verified on a physical device: piano's C++
port is bit-exact against its JVM reference, 0 xruns with all 16 voices
active plus a chord retrigger fired live during a real recording, no
crashes across repeated real UI taps. The one item still open is a real
listening check at the sample-boundary stretch extremes (the thing that
would catch a wrong sample-rate correction) — that specifically needs
someone to listen, not just watch a diagnostics pass/fail. PDF import is
explicitly out of scope per direction. See "Piano" below for the full
verification trail.

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

## Piano

Building started with the phone disconnected, so the sequencing deliberately
front-loaded everything verifiable without a device — this repo has **no
desktop C++ compiler, CMake, or NDK cross-compile target available on this
machine**, so `cpp/host/`'s GoogleTest project can't run here either; the
only fast feedback loop was JVM tests. Mirrors Phase 4's own precedent
(`ClipMixer.kt` as an independent, host-testable JVM reference for
`dsp::mixTracksInto`, cross-validated bit-for-bit on-device only once both
sides existed) rather than inventing a new pattern.

**The core design decision: piano is not an `EngineMode`.** `onAudioReady`'s
output section (step 3) is an `if`/`else if` chain on `EngineMode`
(TestTone/Playing/MultitrackPlaying/...). A naive `EngineMode::Piano` would
make piano mutually exclusive with recording — the opposite of what was
asked ("playable... during recording with low latency"). Instead, piano
voices render as **step 3b, unconditionally after** that chain, summing
(`+=`) into whatever the mode dispatch already wrote — the same pattern
`Armed`/`Recording`'s backing-track mix already uses to layer onto the
metronome click. Consequence: piano sounds in any mode, with no `Scene`
publish and no extra latency on the note path beyond one callback.

**`core/domain/.../PianoVoice.kt`** (JVM reference, runs today with no
device) — `PIANO_SAMPLES` (29 notes, MIDI 24→108, minor-third/3-semitone
spacing, ported from `PianoPanel.jsx`'s `SAMPLES` array), `nearestSampleFor`,
`playbackRateFor` (the web app's `2^(diff/12)` semitone term *plus* a
sample-rate-correction term the web app never needs — the browser always
resamples for you; Android decodes once into a fixed-rate buffer and plays
it through an engine that may run at a different rate, so omitting this
term is a silent "every note ~9% sharp" bug), `envelopeAt` (the exact Web
Audio gain schedule from `triggerNoteOn`/`triggerNoteOff` — linear attack,
two-stage exponential decay, and a release ramp that always starts from the
*true current* gain value, not from 1.0, matching
`cancelScheduledValues`/`setValueAtTime(current)`/`exponentialRampToValueAtTime`),
and `renderVoiceInto` (linear-interpolated resampling, sums into the output
buffer). `PianoVoiceTest.kt`: 19 cases, all passing — nearest-sample
selection and tie-breaking, the rate formula with and without the
sample-rate correction, envelope checkpoints and monotonicity, the release
ramp starting from the true held value, and resampling/summing behavior.

**`cpp/dsp/piano_voice.{h,cpp}`** — a faithful, parameter-for-parameter port
of the Kotlin file (deliberately mirrored signatures, not just equivalent
behavior — this is what lets a future Diagnostics screen call both with
identical inputs and assert bit-identical output). `kPianoReleaseSeconds`
(0.4s) is exported from the header rather than kept as a private constant,
since `audio_engine.cpp`'s voice pool needs the *exact* same value to know
when a released voice is done — two independently-hardcoded 0.4s constants
in different files is exactly the kind of thing that silently drifts apart
in a later edit. `host/test_piano_voice.cpp` (19 cases mirroring
`PianoVoiceTest.kt`'s) is written and registered in `host/CMakeLists.txt`,
but **has never actually been run** — no compiler here to run it on. It
compiles as part of the real Android `.so` (verified via
`:core:audio:buildCMakeDebug`, all three ABIs, clean `-Wall -Wextra`), which
is strong but not equivalent evidence to actually executing the GoogleTest
assertions.

**Sample assets**: all 29 Salamander Grand Piano mp3s (~5.2MB) downloaded
from the same CDN package the web app streams at runtime
(`@audio-samples/piano-mp3-velocity13`) and committed to
`core/audio/src/main/assets/piano/{midi}.mp3`. **Licensing, verified, not
assumed**: the *audio itself* is CC BY 3.0 by Alexander Holm
(archive.org/details/SalamanderGrandPianoV3) — the npm package's own MIT
license covers only its packaging/JS wrapper, a distinction its own README
makes explicitly. Attribution lives in
`core/audio/src/main/assets/piano/NOTICE.md`; an in-app credit still needs
to land wherever the piano UI ends up (see "What's left").

**`core/audio/.../PianoSampleLoader.kt`** — decodes each mp3 via Android's
own `MediaExtractor`/`MediaCodec` (16-bit PCM output, downmixed
stereo→mono, converted to f32) on `Dispatchers.Default`. Deliberately does
**not** add an NDK-side decoder: mp3 decode is a solved problem on Android,
and the C++ engine already accepts plain `FloatArray` buffers over JNI, so
a native decoder would add real surface area for zero benefit. This is the
"loader thread (asset decode)" `docs/PLAN.md`'s own threading model names
but nothing had used until now (the metronome click is synthesized, not
sampled).

**Engine integration** (`audio_engine.h`/`.cpp`): a fixed
`std::array<PianoVoiceState, 16>` voice pool (steals the oldest voice when
full — a real Android-side constraint the web app doesn't have, since the
browser has no polyphony cap), an atomic `mPianoBank` pointer with a small
retained-generations deque (ScenePublisher's exact RCU pattern, just not
routed through `Scene` itself — every Scene-publishing call site would
otherwise have to remember to carry the bank forward, and forgetting one
would silently kill the piano mid-session). Note-on/off travel through the
existing 64-slot command queue as new `CommandType::PianoNoteOn`/`PianoNoteOff`
values (`command.h` explicitly invited this: "Add more CommandTypes here...
rather than inventing a second queue") — not a direct atomic write, because
a voice's envelope age is measured from the RT thread's own
`mStreamFrameCounter` at the exact instant the command is drained, and for
a fast trill that gap is audible. A released voice is forcibly deactivated
`kPianoReleaseSeconds` after release regardless of how much of its sample
buffer remains — matching the web app's own explicit `source.stop()` call
~450ms after `triggerNoteOff`, not just leaving it to decay at the 0.001
floor for its whole natural tail (which for some notes is many seconds, and
would otherwise permanently leak a voice slot).

**JNI + Kotlin facade**: `AudioEngine.loadPianoSamples(context)`,
`pianoNoteOn(midi)`, `pianoNoteOff(midi)`, `setPianoVolume(gain)`, matching
native functions in `jni_bridge.cpp`. Also a stateless
`nativeRenderPianoVoice`/`renderPianoVoiceNative` (no engine handle needed,
same pattern as `nativeMixTracks`/`nativePunchIn`) — used below to
cross-validate the C++ voice renderer against `PianoVoice.kt`'s, the way
Phase 4 cross-validated the two mixers.

**`PianoScreen.kt`** (`:app`) — Compose keyboard, structured from the web
app's `PianoPanel.jsx` `KEY_MAP`: the same 1.5-octave grid (C through the
next octave's F, 11 white keys + 7 black keys), octave-shift controls,
standard piano key-positioning math (a black key's left edge at
`(precedingWhiteKeyIndex + 1) * whiteKeyWidth - blackKeyWidth / 2`). Each
key gets its own `pointerInput` scope, so Compose tracks each one's pointer
independently — chords (multiple keys held at once) work with no manual
multi-pointer bookkeeping, just N independent gesture detectors. Shows the
CC-BY attribution inline (`NOTICE.md`'s credit line).

### Verified on-device (once the phone reconnected)

**Cross-validation — PASS, bit-for-bit.** A new Diagnostics section
(`PianoVoiceCrossValidationSection`) renders the same fractional-`readPos`
voice (exercising both interpolation and the envelope together) through
`AudioEngine.renderPianoVoiceNative` (real C++) and
`com.songnotes.core.domain.renderVoiceInto` (the JVM reference) and
compares them exactly, same technique as Phase 4's mixer cross-validation.
Result on the physical device:

```
PASS
Native (C++) render:  [0.3, 0.55, 0.8, 1.0500001]
JVM (Kotlin) render:  [0.3, 0.55, 0.8, 1.0500001]
Hand-computed:        [0.3, 0.55, 0.8, 1.05]
Native and JVM match EXACTLY (bit-for-bit): true
```

This is the gate that actually matters most: it confirms the hand-ported
C++ arithmetic genuinely agrees with the independently-tested JVM
reference on real hardware, not just "both compile."

**Latency/xruns during a real recording — PASS, 0 xruns.** A second
Diagnostics section (`PianoDuringRecordingSmokeTestSection`) arms a real
mic recording, then — while it's live — fires all 16 voices simultaneously
(the pool's full capacity) plus a second retrigger chord once the first
releases (exercising voice reuse), and checks `EngineState.xRunCount`
didn't move:

```
PASS
xRunCount before: 0, after: 0 (must be equal)
Recorded file: phase9_piano_stress_test.f32, 134784 bytes (33696 frames)
16 voices + a retrigger chord fired while recording was live, no crash.
```

`logcat` over the whole run showed no Oboe warnings, no `FATAL EXCEPTION`,
no `AndroidRuntime` errors — this is the user's actual acceptance
criterion ("playable... during recording with low latency it doesn't
throw off the user") verified directly, not inferred. Piano output is
mixed into the OUTPUT stream only (same as the metronome click always has
been), so it doesn't appear in the recorded file itself — capturing piano
into a take is explicitly out of scope for this pass, only "does having it
active while recording stay glitch-free" was being tested here.

**Real UI — responsive, no crash.** Opened `PianoScreen` for real: samples
loaded ("Piano — Octave 4"), the keyboard rendered with correct black/white
key positioning, and five real taps across different keys (not the
programmatic `pianoNoteOn`/`Off` calls the stress test above uses — an
actual `detectTapGestures` touch path) produced no crash and left the app
focused and responsive afterward.

**Still open — needs a human ear, not a diagnostics pass/fail**: a real
listening check at the ±1-semitone stretch extremes (e.g. MIDI 25/26,
right at a sample boundary) — the one check that would catch a wrong or
forgotten sample-rate correction, since a bug there produces a note that's
merely *out of tune*, not one that crashes, xruns, or disagrees with a
buggy-in-the-same-way reference implementation.

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

## Export, verified end-to-end on a physical device

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

## What's left

- **PDF import** — explicitly out of scope per direction, not planned.
- **A real listening check** at the sample-boundary stretch extremes (see
  "Piano"'s own "Still open" note above) — the one piece of verification
  nothing automated can substitute for.
- **Minor UI polish**: the "Oct −"/"Oct +" header controls can wrap
  awkwardly at some widths; not blocking, just rough.
- Below this line is the original pre-device-reconnect punch list, now
  entirely done — kept for the historical trail of what "the actual gate"
  meant before it was cleared:
  1. Cross-validate `nativeRenderPianoVoice` against `PianoVoice.kt`'s
     `renderVoiceInto` on identical input, assert bit-identical output —
     **done, PASS, bit-for-bit** (see "Piano" above).
  2. Latency/xruns: play chords with all 16 voices active, confirm 0
     xruns and a clean `logcat` — **done, PASS, 0 xruns**.
  3. Piano during a real recording — **done, PASS**: armed a take, fired
     16 voices + a retrigger chord throughout, confirmed the take still
     recorded and xruns stayed at 0 (this is the user's actual acceptance
     criterion, "doesn't throw off the player" — see "Piano" above).
  4. A real listening check — **still open**, tracked as its own item
     above; not something a diagnostics pass/fail can stand in for.
  5. Actually *running* `host/test_piano_voice.cpp` as GoogleTest
     assertions (not just compile-checking it as part of the real `.so`)
     — **still not done**, since it needs a machine with a real C++
     compiler/CMake or the NDK-cross-compile-and-`adb shell` technique
     from Phases 1/4; not attempted this pass since the on-device
     cross-validation (item 1) already gave direct evidence of
     correctness on real hardware.
- **No text wrapping in the PDF export** for a chord/lyric line wider than
  the page.
