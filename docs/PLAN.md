# SongNotes Android — native rewrite, Play Store targeted

Committed here (2026-07-29) because the working copy of this plan had
nearly been lost entirely to context compaction across sessions — only
fragments survived into `docs/handoff/PHASE-03.md`'s paraphrasing. This is
the authoritative source; handoff docs summarize what shipped against it,
but if the two ever disagree, this file wins.

## Context

The PWA/TWA port hit a wall of platform compromises (secure-context crypto
failures, hover-only UI, no real audio control, unfixable Android latency
through the web audio stack). Rather than keep patching a web app into a
shape it resists, this is a **ground-up native Android app** — Kotlin +
Jetpack Compose UI, C++ audio engine via NDK/Oboe — built Play Store ready
from the start.

The desktop web app continues independently in its existing repo. The two
share **one thing: a versioned wire format** (Supabase schema + crypto
envelope + song JSON) so a user's songs sync across both. From the user's
side it's one account, two clients. From the developer's side they're
unrelated codebases in different languages.

**The product thesis is unchanged and non-negotiable:** lyrics + chords
with a calm, uncluttered UI. Market research confirms the gap —
songwriting tools are fragmented into chord apps with no lyric workspace,
lyric apps with no theory, and DAWs too complex for quick capture. The
Scratchpad recorder serves the songwriting, not the reverse.

### Decisions locked in

| Decision | Choice |
|---|---|
| Stack | Kotlin + Compose, C++/Oboe engine via NDK |
| minSdk / targetSdk | **30** (Android 11) / **36** |
| Repos | Two independent; shared versioned wire-format spec |
| Build order | **Engine first**, minimal harness UI, product UI after |
| Calibration | Automatic acoustic loopback + first-class manual fallback |
| Encryption | Passphrase decoupled from auth; "recovery-code only" easy path |
| Chord binding | **Per-chord anchors `{i, c}`** — not the padded parallel string |
| `android-port` branch | Archive as a git tag, then delete |

**Play Store timing is a non-event.** Play constrains `targetSdk`, not
`minSdk` — API 30 minimum is entirely our call. The Aug 31 2026 targetSdk-36
deadline applies to *submissions*; nothing is currently published (the
Bubblewrap/TWA steps were documented but never run), so it constrains
nothing. We build against 36 from day one and it never becomes an issue.

---

## Architecture

### Module layout

```
:app                 Compose host, Hilt wiring, navigation
:core:model          plain data classes, no Android deps
:core:domain         PURE JVM — chords, transpose, lyricsImport, clipEngine,
                     dragMath, calibration math. Where the ported tests live.
:core:data           Room + SQLCipher, supabase-kt, crypto, sync engine
:core:audio          Kotlin facade + src/main/cpp (Oboe, mixer, DSP)
:feature:editor      lyrics + chords
:feature:scratchpad  multitrack
:feature:settings    calibration, encryption, account
```

`:core:audio/src/main/cpp` also builds a **host target** (desktop CMake +
GoogleTest). Non-optional — it's what lets the calibration DSP be
developed on a laptop against synthesized signals instead of by ear on a
phone.

Oboe via prefab (`com.google.oboe:oboe:1.9.x`), pinned `ndkVersion`, ABIs
arm64-v8a + armeabi-v7a + x86_64. **Every `.so` must build with
`-Wl,-z,max-page-size=16384`** (16 KB pages are mandatory on Android 15+);
add Google's `check_elf_alignment.sh` to CI on day one — SQLCipher's
prebuilt is the most likely to bite.

### Audio engine

**Output-master duplex**: one output stream with a data callback, plus an
input stream read non-blockingly from inside that callback (Oboe's
`FullDuplexStream` pattern). AAudio has no true duplex stream, and two
independent callbacks would mean correlating two timestamp sources — every
device quirk becomes a positioning bug.

**The load-bearing insight:** any *constant* offset in the input→output
mapping gets measured and cancelled by calibration. The engine must make
the mapping **stable, not absolutely correct.** So the work is eliminating
variance: prime the input FIFO identically at every arm, freeze adaptive
buffer sizing during a take, never drop input frames on overflow
(zero-fill and count them), and use the **same engine path for calibration
and real recording** — two paths means two constants and the calibration
is worthless.

**Fallback ladder**, recording what was actually obtained rather than what
was asked: AAudio → LowLatency + Exclusive + Float +
`InputPreset::Unprocessed`, degrading through Shared, larger buffers, I16,
and `VoiceRecognition`. **Never** `VoiceCommunication`/`VoicePerformance` —
those force AEC on, which is exactly what destroys calibration. Surface an
`EngineCapabilities` struct (including `isMMapUsed()`, the only honest
answer to "did I get the fast path") on a **Diagnostics screen from Phase
0**.

**Threading:** audio callback (RT — no malloc, no locks, no JNI, no
logging, ever), writer thread (ring → `.f32` file), loader thread (asset
decode), UI thread (60 Hz state poll). Three lock-free channels: SPSC
record ring, 64-slot POD command queue, and a state block in a **direct
`ByteBuffer`** read from Kotlin with zero JNI calls on the poll path. Graph
changes via double-buffered immutable `Scene` with generation-based
retirement (poor-man's RCU, ~30 lines). **Never call C++→Java from the
audio thread.**

**Record positioning:** master clock is output frames rendered. Arm
**0.75 s before** the downbeat (the web app's 0.15 s is far too small for
Android, where round trip commonly runs 40–120 ms and can exceed 200 ms).
Head-skip is applied **once at commit**, so the stored file starts exactly
at transport frame 0 — every downstream consumer has zero offset math.

Also mandatory, and the cause of the widely-reported "waveforms but no
sound" bug class: `onErrorAfterClose` → full stream rebuild,
`AudioDeviceCallback` route handling, `RECORD_AUDIO` + mic foreground
service, audio focus.

### Calibration

**Exponential sine sweep + matched filter — not clicks.** 500 Hz→6 kHz
over 120 ms, 9 reps at ~900 ms spacing, FFT cross-correlation against the
inverse filter, first-peak-above-threshold with parabolic interpolation,
MAD outlier rejection, PNR gating. A sweep gives ~20–25 dB processing gain
over a click at the same safe peak amplitude, and Farina's deconvolution
puts harmonic distortion at *negative* time so a cheap phone speaker
driven hard doesn't contaminate the result.

> **Do not port `detectOnsets`/`estimateLatencyFromOnsets` for the
> automatic path.** That 216-line, 24-test module in the web app is dead
> code — written, tested, never wired up. Its RMS-envelope detector is
> precisely the naive approach that fails on a phone speaker. Port the
> tests' *intent*, not the code. Keep the onset detector only for the
> manual tap-along path, where its assumptions hold.

**Per-route calibration** (`route_key` = device type + product-name hash),
swapped live by `AudioDeviceCallback`. Bluetooth: refuse to auto-calibrate
by default with a plain-language explanation, with an "measure anyway"
escape. When hardware AEC eats the signal: explicitly disable via
`SessionId::Allocate` + `AcousticEchoCanceler`/`NoiseSuppressor`/
`AutomaticGainControl`, detect adaptive-AEC convergence by its signature
(PNR high on rep 1, collapsed by rep 5), and **fail loudly with guidance**
rather than returning a bogus number.

**The three reported UX defects are designed out structurally, not by
discipline:**

- **A** — verification playback renders **one pre-mixed buffer** (aligned
  take + reference click summed offline in C++). One source, one start
  time; a flam is arithmetically impossible.
- **B** — verification has **no count-in**. The user is listening, not
  performing.
- **C** — takes are stored already-aligned, so the verify player contains
  no offset math at all.
- **D** — the calibration take never contains click bleed: the auto path
  emits a sweep with no user action; the manual path on built-in speaker
  uses **visual + haptic count only**, no audible click.
- **F/G** — every control that will ever appear is laid out at mount in a
  constant-size slot; state toggles `enabled`, never presence. The tap pad
  is present and tappable from the moment Record is pressed, with the
  count-in number rendered *inside* it.
- **I** — the calibration ViewModel is injected with a `CalibrationAudio`
  interface exposing only `runSweeps()` and `playPreMixed()`. It has **no
  reference to the metronome or transport API**, so it *cannot* schedule a
  competing click. A fake throws on any unexpected call.

### Crypto + sync

**Envelope v2** — `wraps` becomes a list (not two fixed fields), plus a
`dekId` and a `verifier`:

```json
{ "v": 2, "dekId": "...", "alg": "AES-256-GCM",
  "wraps": [ { "id":"pass", "type":"passphrase", "kdf":{...}, "iv":"...", "ct":"..." },
             { "id":"recovery", "type":"recovery-code", "kdf":{...}, "iv":"...", "ct":"..." } ],
  "verifier": { "iv":"...", "ct":"AES-GCM(DEK,'songnotes-dek-check-v2')" } }
```

`verifier` means one AES-GCM open tells you whether a passphrase is right
without touching song data. `dekId` (also stamped on every song row) makes
"encrypted under a previous key" *identifiable* instead of just "decrypt
threw". Device wraps live **locally** in Room, wrapped by an Android
Keystore key with biometric unlock — so the passphrase is typed once per
device, and a stolen server dump reveals nothing about the device fleet.

**Argon2id** (`m=64 MiB, t=3, p=1`) replaces PBKDF2-600k. PBKDF2 is
memory-less, so a consumer GPU manages roughly 8,000 guesses/s against it;
Argon2id at 64 MiB forces real silicon for the same ~1 s user-visible
cost. In a zero-knowledge product the KDF *is* the security claim, and the
NDK toolchain needed to vendor it already exists for the audio engine.
Keep a PBKDF2 *reader* path so the web app can migrate lazily; writers
emit Argon2id only. Store params in the envelope and **rewrap on unlock**
when below current policy.

**Recovery codes get normalized** (uppercase, strip non-alphabet,
re-insert canonical hyphens, *then* derive). Today the hyphens are part of
the KDF input, so retyping without them just says "wrong code" — a nasty
footgun.

**Sync — fix the data-loss bug before the product UI.** Current
`_reconcile` is whole-song last-write-wins, so two devices editing
different verses means one silently vanishes. Tier 0 (~1 day): add `rev`
with optimistic concurrency; on conflict keep **both**, writing the loser
as `"<title> (conflict copy — Pixel 8, 14:22)"`. Inelegant, lossless,
immediately understandable. Tier 1 (per-line 3-way merge, ~1 week) comes
after the product UI and is cheap because **lines already have stable
ids**. CRDTs are not worth it for a two-device single-user app.

Also fix now while free: add `deleted_at` tombstones (**deletes currently
never propagate between devices** — a live bug), add
`index(user_id, updated_at)` for incremental pull, and drop the
always-NULL `songs.title` column that invites someone to "optimize" by
leaking titles server-side.

**Wire format v2** — `bpm`/`capo` become numbers (strings today for no
reason), and chords become **per-chord anchors**:

```json
{ "id":"...", "lyrics":"Amazing grace, how sweet the sound",
  "chords":[ {"i":0,"c":"G"}, {"i":14,"c":"C"} ] }
```

Losslessly convertible to/from plain-text chord sheets, so import/export
is untouched. Anchors survive reflow, font changes, and — critically —
**transposition**, which today shifts every subsequent chord's column
when a token changes width (`F#`→`G`).

### Data layer

Room (`Flow` straight into Compose, real migrations) + **SQLCipher**,
keyed by a random DB key wrapped in Keystore — not by the DEK, so the DB
opens before unlock. `allowBackup=false`. DataStore for settings only.
Audio as `filesDir/takes/<songId>/<takeId>.f32` + JSON sidecar, never in
the DB; exports via `MediaStore.Audio`.

**Offline-first:** the app reads only from Room, never the network.
Writes go to Room + `sync_queue` in one transaction; a WorkManager
`SyncWorker` pushes the outbox with optimistic concurrency, then pulls
`updated_at > last_sync_at`. `supabase-kt` for auth/postgrest — GoTrue's
refresh-token and OIDC handling is exactly what you don't want to
reimplement.

---

## Phases

Every phase ends with **`docs/handoff/PHASE-NN.md`**: what shipped, what's
known-broken, what the next phase assumes, and any device-specific numbers
observed. This is a hard requirement, not a nicety.

| # | Goal | Size | Device? | Status (2026-07-30) |
|---|---|---|---|---|
| **0** | Repo skeleton + "hello Oboe" — installable app that opens a stream and reports what it got. Diagnostics screen with full `EngineCapabilities` incl. `isMMapUsed()`. **Done:** APK installs, sine is clean, zero xruns over 60 s. | S | ✅ | done |
| **1** | Duplex engine core + record-to-file. SPSC ring, writer thread, command queue, Scene double-buffer, direct-ByteBuffer state, `onErrorAfterClose` rebuild, mic foreground service. **Done:** 60 s record→playback matches; unplugging headphones mid-take doesn't kill it; 50 cycles leak nothing; ring buffer clean under TSan over 10⁸ frames. | M | ✅ **hard gate** | done |
| **2** | Transport clock, metronome, sample-accurate placement. C++-rendered metronome, count-in, 0.75 s pre-roll, input priming, overflow accounting. **Done:** across 5 takes, transients sit at a *constant* offset from gridlines with **spread < 3 ms**. Stability is the criterion here — absolute offset is Phase 3's job. | M | ✅ **hard gate** | done |
| **3** | Automatic acoustic loopback calibration. ESS + inverse filter + FFT as a **host-testable C++ lib first**, then JNI-wrapped. MAD rejection, PNR gating, AEC disable, per-route table, BT warning, wizard obeying Rules A–I, manual slider. **Done:** 5 runs agree within ±3 ms; tap test lands within ±5 ms; noisy room **fails loudly**; *and* the manual-only path reaches a good result on a device where auto fails. | L | ✅ **hard gate, ideally 2 devices** | **done** — see `docs/handoff/PHASE-03.md` |
| **4** | Multitrack scratchpad engine — real overdubbing. 4 tracks, gain/mute/solo, punch-in insert, mixdown to WAV. **Done:** exported WAV is **sample-identical to a JVM reference mixer** given the same clip list. | M | ✅ listening test | **done** — see `docs/handoff/PHASE-04.md` |
| **5** | Domain logic port + JVM behavioural spec. `:core:domain` in pure Kotlin. **Done:** golden cross-check — ~2000 chord strings through both JS and Kotlin `normalizeChordName`, byte-identical. | M | — | **done** — see `docs/handoff/PHASE-05.md` |
| **5.5** | **Minimum shippable lyrics+chords editor** — local only, no audio, no sync. **Done:** you can write a real song on it and prefer it to a notes app. | M | — | **done** — see `docs/handoff/PHASE-05.5.md` |
| **6** | Data layer + crypto. Room + SQLCipher, Argon2id, envelope v2, Keystore device wrap + BiometricPrompt. **Done:** **cross-implementation test vectors** — a web-app envelope decrypts in Kotlin and vice versa, committed to *both* repos. | M | instrumented | ✅ **done** — envelope v2 + Argon2id (cross-repo test vectors passing both directions); Room + SQLCipher + Keystore-wrapped DB key, `:app`'s live song list/editor running on it with a verified migration from the old JSON files; account-DEK Keystore device wrap + BiometricPrompt verified on-device with a real fingerprint round trip. See `docs/handoff/PHASE-06.md`. Left for later: Room migrations infra (no schema change yet to migrate), `dekId`-on-row stamping (no DEK-encrypted rows exist yet), 16 KB `libsqlcipher.so` alignment (explicitly Phase 11 scope per the plan) |
| **7** | Auth + Supabase sync. Credential Manager → `signInWithIdToken`, outbox + `SyncWorker`, schema v2, Tier-0 conflict copies, tombstones. **Done:** two clients edit offline, both reconnect, **nothing is lost**; delete on A removes on B. | M | ✅ | **done** — Tier-0 sync fix (rev-based optimistic concurrency, tombstones, conflict copies) shipped in the web app; Android auth (email+password via supabase-kt, matching the web app's own method rather than Credential Manager), Room sync columns + migration, `SyncEngine`/`SongSyncWorker`, sign-in/sign-up UI all built and verified end-to-end on a physical device against the real Supabase project, cross-checked against the web app in both directions (push and delete-tombstone pull). Two real bugs found and fixed by that live verification — see `docs/handoff/PHASE-07.md`. Deferred: periodic/background sync, background DEK unlock, Kotlin v1-envelope compat, full `WIRE-FORMAT-v2.md` alignment (`dekId`, DB rev-bump trigger, `meta`-wrapped content JSON) beyond the chords-as-anchors piece |
| **8** | Editor UI: lyrics + chords. Chord diagrams as Compose `Canvas`, custom voicings, transpose, metadata. **Budget real time for typography** — "calm and uncluttered" *is* the product. | L | ✅ | **done** — full `CHORD_DB`/`lookupChord` ported (verified byte-for-byte against the real web app via golden fixtures), `customChords` added to `Song` and threaded through Room + `SyncEngine`, a Compose `Canvas` `ChordDiagram` pixel-matching the web app's SVG, a tap-to-view/edit voicing popup, and an editable BPM/Key/Tuning/Capo bar. Verified live on-device: tap a chord token, view its diagram, save a custom voicing, confirm it survives a full process restart, reset it back to default. See `docs/handoff/PHASE-08.md` |
| **9** | Import / export / piano. PDF text extraction, share/export, 29 Salamander samples through the **same C++ mixer** so piano is recordable via the same path. | M | ✅ | **done** (one item needs a human ear) — export (text clipboard copy + real PDF export via share sheet) and piano (native, sample-based, additive over every engine mode — not its own `EngineMode`, so it's playable during recording) both verified on a physical device: the C++ voice renderer is bit-exact against its JVM reference, 0 xruns with all 16 voices active plus a chord retrigger fired live during a real recording, no crashes across repeated real UI taps. Still open: a listening check at the sample-boundary stretch extremes (the one thing nothing automated can substitute for) and running `host/test_piano_voice.cpp` as real GoogleTest assertions (no compiler on this machine; the on-device cross-validation already gave direct correctness evidence on real hardware, so this wasn't blocking). PDF import is deliberately out of scope per explicit direction. See `docs/handoff/PHASE-09.md` |
| **10** | Scratchpad product UI. Timeline, touch clip drag/trim, waveform from a C++ peak pyramid. Minimized transport strip, DAW collapsible to tempo/BPM/start-stop, theme moved into settings. | L | ✅ | **done** — timeline with playhead + tap-to-scrub punch-in, touch clip drag/trim (same-track overlaps auto-spliced via the existing `punchIn`, never left layered), waveform rendering from a C++ peak pyramid, a per-project configurable time signature (drives the metronome's actual count-in/click pattern and a new beat/bar timeline grid, not just a display), and a force-phone-mic recording toggle (`AudioEngine.setPreferredInputDevice`, new on this repo — lets the click play out to a connected headset while input stays pinned to the built-in mic) all shipped and verified on a physical device. Minimized transport strip / DAW-collapsible layout / theme-in-settings are UI polish with no engine dependency and were not attempted this pass. See `docs/handoff/PHASE-10.md` |
| **11** | Hardening + Play release. R8, baseline profile, Data Safety form, privacy policy, internal testing track, manual device matrix. | M | ✅ | not started |

**Deliberate slack:** phases 5, 5.5 and 6 have **no engine dependency**.
When blocked on a calibration bug or waiting on a device, go do those.
That's designed-in, not a reordering of engine-first.

---

## Verification

- **JVM unit (the bulk):** everything in `:core:domain`; crypto
  envelope/KDF; the sync conflict matrix against a fake adapter.
- **Host C++ + GoogleTest (highest value in the project):** calibration
  DSP against *synthesized* recordings (`delay(sweep, N) + noise +
  reverb`) — assert recovery within ±1 sample at 20 dB SNR, graceful at
  10 dB, **clean failure** at 0 dB. Ring buffer under TSan. Mixer
  sample-identical to the JVM reference. All in CI via `ctest`.
- **Instrumented:** Room migrations, Keystore, and Compose tests enforcing
  Rule F (tap-pad bounds byte-identical across all wizard states) and
  Rule I (fake `CalibrationAudio` throws on any scheduling call).
- **Real device only:** latency, xruns, MMAP, AEC, route changes,
  backgrounding, thermal. **Emulator audio is a lie** — never trust it for
  any of these.
- **Porting the 231 JS tests — don't hand-translate.** In the *web* repo,
  dump `(inputs → outputs)` fixtures per pure function to `spec/*.json`,
  including a generated corpus (every CHORD_DB entry × ~30 notation
  variants × 12 transpositions). Commit those fixtures to **both** repos;
  Kotlin runs parameterized tests over them (~40 lines per spec file).
  More coverage than 231 assertions for less effort, and any future
  divergence fails a build. Hand-port only the ~30 structural/ordering
  tests — the `maj#7`-before-bare-`maj` rule deserves an explicit named
  test.
- **Buy a 3.5 mm TRRS loopback cable (~£5).** It removes the acoustic path
  entirely and gives the *electrical* round trip as ground truth to
  validate the acoustic measurement against. Enormously clarifying for the
  money.

---

## Honest notes

- **Effort:** phases 0–4 are realistically **6–10 focused weeks** for a
  first real-time audio engine, most of the pain in Phase 3. To a Play
  release, **4–8 months part-time**. The phase table reads tidy; the work
  isn't. "No rush" is the right posture.
- **Plan for 40–80 ms round trip as typical, not 20 ms.** `Exclusive` may
  simply never be obtainable on a given device and there is no fix.
  Everything downstream must be designed for 80 ms.
- **Don't build software input monitoring.** At 60 ms round trip, hearing
  yourself through the phone is unusable — well past the threshold where
  delayed auditory feedback makes people stammer. With wired headphones
  the user hears themselves acoustically anyway. Deciding this now deletes
  a whole subsystem.
- **Hardware AEC is unfixable on some devices.** On those the manual
  slider isn't a fallback, it's *the* path — which is why Phase 3's
  acceptance criteria include it explicitly.
- **Two implementations of one crypto format will drift.** Committed
  cross-implementation vectors in both CI pipelines are the only defense.
  Skip it and the eventual user-visible failure is "all my songs are
  gone."
- **A rewrite re-earns ~9,200 lines of accumulated bug fixes.** The JS
  *comments* are unusually good and encode hard-won knowledge — read them,
  not just the code.
- **Retiring `android-port` (tag, then delete)** leaves two real fixes
  behind on the tag rather than on `main`: the AudioContext lifecycle bugs
  (recording silently dies after a context close — affects desktop too,
  just more rarely) and the secure-context crypto guard (stops the "wrong
  password" lie you hit while testing). Retrievable from the tag whenever
  you want them; noting it so it isn't forgotten.

---

## First steps on approval

1. Create the new repo; tag `android-port` in the existing one and delete
   the branch.
2. Write `docs/WIRE-FORMAT-v2.md` — the shared contract — and commit it to
   **both** repos.
3. Phase 0.
