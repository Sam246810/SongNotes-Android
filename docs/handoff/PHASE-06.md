# Phase 6 — Data layer + crypto

**Status (2026-07-30): First pass done — envelope v2 + Argon2id, with committed
cross-implementation test vectors. Room, SQLCipher, and Android Keystore
integration are NOT started; see "What's left" below.**

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

## What's left (this phase, deliberately deferred)

- **Room + SQLCipher** — not started. The plan's `:core:data` module now exists
  and holds the crypto slice; Room/SQLCipher will need the `android-library`
  plugin (or a further module split) and instrumented tests, neither of which
  this pass added since nothing yet needs them.
- **Android Keystore + `BiometricPrompt` device wrap** — not started. This is
  what envelope v2's `wraps[]` list was specifically shaped to accommodate (a
  device wrap is just another list entry), but no device-wrap code exists yet.
- **`dekId` not yet stamped on any row** — there are no encrypted rows in the
  Android data layer yet to stamp it on. Deferred until Room exists.
- **`migrateWrapIfNeeded` (web) not wired into `AuthProvider.jsx`'s sign-in
  flow** — implemented and tested in isolation, but the live login path doesn't
  call it yet, so an existing PBKDF2 wrap won't actually upgrade to Argon2id on
  a real user's next login until this is wired up.
- **Sync (Tier 0 rev/optimistic-concurrency, tombstones) and schema v2** —
  entirely separate plan items (Phase 7), untouched this pass.
- **Wire format v2 (`bpm`/`capo` as numbers, per-chord anchors)** — already done
  in a much earlier pass (`docs/WIRE-FORMAT-v2.md`, Phase 5), unrelated to this
  phase's envelope v2 despite the similar name.
