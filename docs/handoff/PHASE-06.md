# Phase 6 — Data layer + crypto

**Status (2026-07-31): Second pass done — Room + SQLCipher + Keystore-wrapped DB
key, verified on a physical device with genuine at-rest encryption confirmed
(not just "the API didn't throw"). Not yet wired into `:app`'s live song list/
editor screens (still on the pre-Phase-6 `SongStorage.kt` JSON files) — see
"What's left" below.**

Scoped deliberately: the plan's own crypto-format decisions (envelope v2's
`wraps` list, `dekId`, `verifier`; Argon2id replacing PBKDF2) are the highest-risk,
hardest-to-change part of this phase — once real accounts have real envelopes
under a format, changing that format again means a migration, not a rewrite. So
this pass designs and ports that format once, with cross-repo proof it's actually
compatible, before spending any time on the Android-only plumbing (Room,
SQLCipher, Keystore) that consumes it. Those don't need the crypto format to be
final on day one the way the format itself needs to be right before either repo
starts writing real envelopes with it.

## What shipped

**Envelope v2, desktop web app** (`src/crypto/kdf.js`, `envelope.js`,
`accountKeys.js`): the account-key envelope — what's stored server-side in
`user_keys.envelope`, wrapping a single random DEK once per unlock method — moved
from `{v:1, passphrase:{kdf,wrapped}, recovery:{kdf,wrapped}}` (fixed fields) to
`{v:2, dekId, alg, wraps:[{id,type,kdf,iv,ct}, ...], verifier:{iv,ct}}` (a list),
exactly matching `docs/PLAN.md`'s own schema example. `wraps` being a list rather
than fixed fields is what lets a future per-device Keystore wrap (the Android
client's own unlock method, once Phase 6's later Room/Keystore work lands) become
just another list entry, not another schema version.

- **Argon2id (m=64 MiB, t=3, p=1) via `hash-wasm`** is now the writer path for
  every new wrap; PBKDF2-HMAC-SHA256 at 600k iterations is kept as a **reader
  path only** (`kdf.js`'s `deriveKEK` dispatches on `params.name`), so envelopes
  written before this change keep unlocking. Nothing writes PBKDF2 anymore.
- **`verifier`**: `AES-GCM(DEK, 'songnotes-dek-check-v2')`, computed once per
  envelope and checked after every unwrap (`envelope.js`'s
  `computeDekVerifier`/`checkDekVerifier`) — turns "wrong password" into a clean,
  explicit failure independent of which wrap wrapped it, per the plan's own
  reasoning ("one AES-GCM open tells you whether a passphrase is right without
  touching song data").
- **`dekId`**: a short random hex id stamped on the envelope now; stamping it on
  encrypted song rows too (so "encrypted under a previous key" is identifiable
  instead of just "decrypt threw") is deferred until the data layer actually has
  rows to stamp it on — no schema to touch yet.
- **Backward compatibility**: `unlockWithPassphrase`/`unlockWithRecoveryCode`
  transparently read both v1 (fixed fields) and v2 (`wraps[]`) shapes — real
  accounts already have v1 envelopes via `AuthProvider.jsx`'s live Supabase-backed
  sign-up/sign-in flow, and this change must not orphan them. Nothing new writes
  v1. A v1 envelope upgrades to v2 automatically the next time its owner changes
  their password or recovers via code (`rewrapWithNewPassphrase`, already called
  from both of those existing flows) — not silently on every login.
- **`migrateWrapIfNeeded`**: rewraps a single v2 wrap that's still PBKDF2 onto
  Argon2id, given the secret + DEK already in hand from a successful unlock (the
  plan's "rewrap on unlock when below current policy"). Not yet wired into
  `AuthProvider.jsx`'s actual sign-in flow — the function exists and is tested,
  calling it from the login path is a small follow-up, not done this pass.

**Envelope v2, Android (`:core:data`, new pure-JVM module)**: a from-scratch
Kotlin port of the same three files — `Kdf.kt` (Argon2id via Bouncy Castle's
`Argon2BytesGenerator`, since the JDK has no native Argon2id; PBKDF2 via the
JDK's own `SecretKeyFactory`), `Envelope.kt` (`EnvelopeV2`/`WrapEntry`/
`DekVerifier` data classes, JSON via `org.json:json` — the real Maven Central
artifact, not Android's stub-only bundled copy, so this stays plain-JVM
unit-testable like `:core:domain`), `AccountKeys.kt` (`createAccountKeys`/
`unlockWithPassphrase`/`unlockWithRecoveryCode`). Deliberately **v2-only, no v1
reader** — unlike the web app, Android has never written or stored a v1
envelope, so there's no legacy shape to stay compatible with here.

New module rather than folding into `:core:domain`: the plan's own module layout
already names `:core:data` for "Room + SQLCipher, supabase-kt, crypto, sync
engine" — this pass only builds the crypto slice of that, pure-JVM (`kotlin-jvm`
plugin, same as `:core:domain`, not `android-library`) since Argon2id/AES-GCM/JSON
need no Android framework or device. Per the plan's own "deliberate slack" note
("phases 5, 5.5 and 6 have no engine dependency"), keeping this piece
device-independent was a deliberate choice, not an oversight — Room/SQLCipher/
Keystore, when they're added to this same module later, will need instrumented
tests (the phase table's own "Device? instrumented" marker), and that's expected
to land as a separate addition, not implied by anything built this pass.

## Cross-implementation test vectors (Phase 6's own "Done" criterion)

> "cross-implementation test vectors — a web-app envelope decrypts in Kotlin and
> vice versa, committed to both repos"

Both directions verified and committed:

1. **`spec/envelope-v2.json`** — a REAL envelope (real random salts/IVs, not
   synthetic), built by the desktop repo's
   `src/test/generate-golden-fixtures.test.js` calling its own `createAccountKeys`
   with a fixed passphrase/recovery code, self-checked to round-trip in JS before
   being written, then committed to both repos (`SongNotes/spec/` and
   `SongNotes-Android/core/data/src/test/resources/spec/`). `:core:data`'s
   `EnvelopeV2GoldenFixtureTest.kt` reads it, unlocks via both the passphrase and
   the recovery code, and asserts the recovered DEK bytes are **byte-identical**
   to the JS side's exported raw DEK — not just "decryption didn't throw."
2. **`spec/envelope-v2-from-android.json`** — the reverse direction: the same
   Kotlin test builds an equally-real envelope with `:core:data`'s own
   `createAccountKeys`, sanity-checks it in Kotlin, then writes it to both
   repos' `spec/` directories as a side effect of the test run (same "write a
   fixture as a side effect, then commit it" convention the JS-side chord/lyrics
   fixtures already established). `generate-golden-fixtures.test.js`'s own
   `it('reads spec/envelope-v2-from-android.json ...')` picks it up and confirms
   `unlockWithPassphrase`/`unlockWithRecoveryCode` in JS recover the exact DEK
   bytes Kotlin committed.

Both ran clean on the first real attempt after one bug fix (see below) — 23/23
JS crypto tests, 11/11 JS fixture-generation tests (247/247 across the whole JS
suite), 2/2 new Kotlin tests, full `:core:domain:test` + `:core:data:test` +
`:app:assembleDebug` all green.

**One real bug found and fixed during this pass**: `unlockWithPassphrase`'s v1
fallback path looked up the legacy envelope field as `envelope['pass']` instead
of `envelope['passphrase']` — a copy-paste mismatch against the v2 wrap `id`
(`"pass"`, a short identifier) versus the v1 envelope's actual fixed field name
(`"passphrase"`). Caught immediately by the new `reads a legacy v1 envelope` test
failing with a clear "No 'pass' entry in this v1 envelope" error — exactly the
kind of bug a real test (not just code review) exists to catch.

## Fixture nondeterminism bug (found right before the second pass)

Before starting Room/SQLCipher work, running the full JS suite again showed
`spec/envelope-v2.json` as modified with no source change of mine — `git diff`
confirmed a brand new random envelope (different `dekId`, salts, ciphertext,
DEK) had been silently written. Unlike every other golden fixture in this
repo, `createAccountKeys` isn't a pure function of its inputs — it generates
real random salts/IVs/DEK, same as production — so the original
always-regenerate-and-overwrite generator would have drifted the committed
fixture out of sync with the frozen copy in `:core:data` on **every single**
`npm test` / `gradlew test` run, on both sides, without either suite's own
tests failing to notice (each side only checked its own current copy still
round-tripped, never that the two committed copies still matched each other).
Fixed on both sides (JS: `generate-golden-fixtures.test.js`; Kotlin:
`EnvelopeV2GoldenFixtureTest.kt`) to bootstrap the fixture once if missing,
then re-verify the *existing* committed file round-trips on every subsequent
run instead of overwriting it. Confirmed idempotent by re-running each suite
twice and checking `git status` showed no diff the second time.

Also wired `migrateWrapIfNeeded` into `AuthProvider.jsx`'s actual `signIn`
flow (previously implemented and tested in isolation, but nothing called it)
— a stale PBKDF2 passphrase wrap now rewraps onto Argon2id automatically on
the next successful login, best-effort or effect on the sign-in outcome
itself (the DEK is already established before the migration attempt runs).

## Second pass — Room + SQLCipher + Keystore-wrapped DB key (2026-07-31)

**`:core:data` converted from pure-JVM to an Android library module**
(`android-library` + `kotlin-android` plugins, replacing `kotlin-jvm`) — Room,
SQLCipher, and Android Keystore all need the Android framework, unlike the
first pass's crypto-only content (`Kdf.kt`/`Envelope.kt`/`AccountKeys.kt`),
which has zero `android.*` imports and still runs as plain local JVM unit
tests under the new setup, unchanged. Matches the plan's own module layout,
which names `:core:data` for "Room + SQLCipher, supabase-kt, crypto, sync
engine" together, not split across modules. Main sources moved from
`src/main/kotlin` to `src/main/java` to match `:core:audio`'s own established
convention for `android-library` modules in this repo (test sources stay
under `src/test/kotlin`, also matching precedent).

**What shipped**:

- **`SongEntity`/`SongDao`/`SongDatabase`** — one row per song (mirroring
  `:app`'s pre-Phase-6 `SongStorage.kt`'s one-JSON-file-per-song granularity),
  `meta` fields flattened into real columns, `lines` (with nested per-chord
  anchors) stored as a single JSON blob via `org.json` — lines are always
  read/written as a whole song, never queried independently, so normalizing
  them into a child table would add join complexity with no query benefit.
  `SongEntity.toDomain()`/`fromDomain()` map directly to/from `:core:domain`'s
  existing `Song`/`SongLine`/`ChordAnchor` — `:core:data` now depends on
  `:core:domain` — rather than duplicating those data classes.
- **`KeystoreDbKeyProvider`** — generates a random 256-bit DB key on first run,
  wraps it (AES-GCM) with an `AndroidKeyStore`-resident AES key (alias
  `songnotes.db_key_wrap`, no biometric gating), and persists only the wrapped
  bytes to a small file under `filesDir`. Deliberately **not** the account
  DEK, and deliberately **not** biometric-gated — per the plan, "keyed by a
  random DB key wrapped in Keystore, not by the DEK, so the DB opens before
  unlock": a locked-out or not-yet-signed-in user can still open the app and
  see their (DEK-encrypted, so still individually unreadable) song list,
  rather than the whole local database being inaccessible until a passphrase
  is typed. This is a separate, unrelated key from the account-key envelope's
  `wraps[]` list from the first pass — the plan's *other* "Keystore device
  wrap + BiometricPrompt" item is a future `wraps[]` entry unlocking the
  *account DEK* via biometrics, not this DB-at-rest key.
- **`SongDatabase.open(context, dbKey)`** — swaps Room's default SQLite driver
  for SQLCipher's via `net.sqlcipher.database.SupportFactory`, same
  `@Entity`/`@Dao`/`@Database` API surface, transparently encrypted storage
  underneath rather than a bolted-on encryption layer.
- **`SongRepository`** — thin facade tying the above together
  (`observeAll(): Flow<List<Song>>`, `getById`, `upsert`, `delete`). Not yet
  wired into `:app`'s live `SongListScreen`/`SongEditorScreen` — see "What's
  left" — exists so the encrypted database could be built and verified as a
  standalone piece first, without risking the live app's already-working
  editor mid-swap.
- **A resource-merge conflict** (`bcprov-jdk18on` and `jspecify` both ship an
  identical stub `META-INF/versions/9/OSGI-INF/MANIFEST.MF` path) broke
  `:app:installDebug` the first time `:core:data` was wired into `:app` as a
  real dependency — fixed with a `packaging { resources { excludes += ... } }`
  block in `app/build.gradle.kts`, safe since both are functionally-identical
  no-op stub manifests.

**Verified on the physical device (SM-F956W, 2026-07-31)** via a new
`EncryptedDbSmokeTestSection` in `DiagnosticsScreen.kt` (same pattern as every
other on-device smoke test in this project): opens/creates the encrypted DB
(exercising real Keystore key generation + wrap on first run), upserts a test
`Song` with a distinctive plaintext marker string as its title/lyrics,
confirms the read-back matches exactly, **then reads the raw `.db` file bytes
directly off disk and confirms the plaintext marker is NOT found anywhere in
them** — proving genuine encryption at rest, not just that the Room API
didn't throw. Ran twice in a row (fresh key creation, then persisted-key
reuse) — both PASS, confirming the wrapped DB key file round-trips through
the Keystore correctly across app restarts, not just within a single process.
Test song deleted in a `finally` block after each run either way.

**One real, already-anticipated gap surfaced by this pass, not fixed**: the
device's own "Android App Compatibility" debug-build warning dialog now also
flags `lib/arm64-v8a/libsqlcipher.so` as not 16 KB-page-aligned (`LOAD segment
not aligned`), alongside the pre-existing warnings for `liboboe.so`/
`libc++_shared.so`/`libsongnotes_audio.so`. Checked Maven Central: 4.5.4 (the
version pinned here) is already `net.zetetic:android-database-sqlcipher`'s
latest release, so this isn't a version bump away — it's a genuine upstream
gap in the current SQLCipher prebuilt. Exactly the risk `docs/PLAN.md`'s own
"Module layout" section predicted by name before any of this phase started
("Every `.so` must build with `-Wl,-z,max-page-size=16384`... SQLCipher's
prebuilt is the most likely to bite") and explicitly assigns to **Phase 11**
("add Google's `check_elf_alignment.sh` to CI on day one"), not this one — the
app still installs, runs, and encrypts correctly on this device today; this
only becomes a hard blocker on an Android 15+ device with strict 16 KB page
enforcement, which is Phase 11's problem to solve before a real release.

**One minor testability gap, not addressed**: `:core:data` has no
`android-library`-style instrumented (`androidTest`) test suite — this
project has never had one anywhere, and verification instead used the same
"add a `DiagnosticsScreen` smoke-test section, drive it via `adb`" pattern
already established for every other Phase 0–5 on-device feature. That
precedent was deliberately followed rather than introducing a new test
methodology (Espresso/`AndroidJUnitRunner`) as a tangent — worth reconsidering
once there's enough device-dependent surface area (Room migrations, Keystore
edge cases) that manual `adb`-driven smoke tests stop scaling.

## What's left (this phase, deliberately deferred)

- **`:app`'s `SongListScreen`/`SongEditorScreen` still use `SongStorage.kt`**
  (plain JSON files), not the new `SongRepository`/Room+SQLCipher. The
  encrypted data layer exists and is verified standalone but the live app
  hasn't been switched over — a deliberate, separate follow-up rather than a
  risky big-bang swap in the same pass that built the DB itself.
- **Android Keystore + `BiometricPrompt` *account DEK* device wrap** — not
  started. This is what envelope v2's `wraps[]` list was specifically shaped
  to accommodate (a device wrap is just another list entry), distinct from
  this pass's DB-at-rest key (see "What shipped" above) — no device-wrap code
  for the account DEK exists yet.
- **`dekId` not yet stamped on any row** — `SongEntity` has no `dekId` column
  yet since nothing in the Android app encrypts song content with the account
  DEK yet (only local SQLCipher-at-rest encryption exists so far). Deferred
  until the app actually writes DEK-encrypted content.
- **No Room migrations yet** — only ever been version 1, `exportSchema =
  false`. Add real schema export + a migration test once the schema changes
  for the first time.
- **16 KB page alignment for `libsqlcipher.so`** — confirmed gap, explicitly
  Phase 11 scope per the plan (see above). Not fixed this pass.
- **Sync (Tier 0 rev/optimistic-concurrency, tombstones) and schema v2** —
  entirely separate plan items (Phase 7), untouched this pass.
- **Wire format v2 (`bpm`/`capo` as numbers, per-chord anchors)** — already done
  in a much earlier pass (`docs/WIRE-FORMAT-v2.md`, Phase 5), unrelated to this
  phase's envelope v2 despite the similar name.
