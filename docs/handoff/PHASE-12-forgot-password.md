# Phase 12 — Forgot password + recovery-code lifecycle

**Status (2026-08-13): done.** A user who forgets their account password and
still has their recovery code gets back into their account, on either
platform, with every existing song intact and zero re-encryption. A user who's
lost the code too can start fresh instead of being permanently stuck. Several
latent bugs that made the recovery code an unreliable escape hatch — present
on both platforms before this phase, not introduced by it — are fixed as part
of the same pass, because Path A's entire safety argument depends on them
being fixed (see "Why the lifecycle bugs are in scope" below).

This was planned and executed as one cross-repo effort, web-first — the web
repo carries the full design writeup (state-machine ordering, a table of every
failure-injection point and the resulting server state, Supabase-specific
auth-flow research). This document covers the Android-side work only, plus
what changed on both platforms in the crypto/sync layer that Android also
depends on.

## Why the lifecycle bugs are in scope

The recovery-code unlock flow (below) is only as trustworthy as the assumption
that a recovery code, once generated, actually works. Before this phase, on
both platforms, several code paths violated that silently:

- `signIn`'s create-if-missing branch (`SupabaseAuthRepository.kt`, mirrored in
  the web app's `AuthProvider.jsx`) minted a fresh `AccountKeys` — a **brand
  new random DEK** — and wrote it with a blind **upsert**. Any transient reason
  the preceding read found no row (a race with the account's own signup write,
  a stale JWT under RLS) would silently overwrite a live envelope with one
  wrapping a different DEK, permanently orphaning every song already encrypted
  under the old one. This is the same class of bug Phase 7 already found and
  fixed once, on `signUp`'s side (see that phase's own doc, "a sign-up attempt
  landed on an already-registered email") — this pass found the mirror-image
  gap in `signIn` that Phase 7 didn't cover.
- The same branch, on both platforms, **discarded the recovery code it just
  minted** — returning nothing the caller could show the user. An account that
  went through this path had a recovery wrap whose secret no human or machine
  had ever seen: a permanently dead escape hatch, indistinguishable from a
  working one until the day someone actually needed it.
- Neither platform normalized recovery-code input before deriving a KEK from
  it — a code retyped lowercase or without hyphens just failed as "wrong
  code," specified in `docs/WIRE-FORMAT-v2.md` §3.1 since v2 was drafted but
  never actually implemented anywhere until now.

Fixing the reset flow without fixing these first would have meant building a
"non-destructive recovery" feature on top of a recovery code that frequently
doesn't exist or doesn't work. They're fixed together.

## What shipped

**Shared crypto parity** (`core/data/src/main/java/com/songnotes/core/data/`):

- **`RecoveryCode.kt`** — `normalizeRecoveryCode`/`describeRecoveryCodeInput`,
  a straight port of the web app's new `src/crypto/recoveryCode.js`: NFKC →
  `uppercase()` (not the deprecated, Turkish-locale-sensitive `toUpperCase()`)
  → strip everything outside the 32-character alphabet → re-chunk into groups
  of 5 joined by `-`, **no trailing separator** on an exact-multiple-of-5
  input (the committed fixtures are 25 characters specifically to pin this —
  a naive "insert `-` every 5th char" implementation derives a different KEK
  on that input and fails both suites). Wired into `unlockWithRecoveryCode`
  itself, not left to call sites, so every recovery-code unlock benefits.
  Confusable-character detection (`describeRecoveryCodeInput`, UI-only, never
  on the derive path) flags `0`/`1`/`I`/`O` — **not** `L`, which is a valid,
  generatable alphabet character (`...GHJKLMN...`). Several early design notes
  during this phase's own planning assumed `L` was excluded too and were
  wrong; the golden fixtures (below) now pin the correct behavior.
- **`AccountKeys.kt`** gained `rewrapWithNewPassphrase`, `migrateWrapIfNeeded`,
  and `regenerateRecoveryWrap` — all previously web-only. `rewrapWithNewPassphrase`
  replaces only the `pass` wrap (dekId, verifier, and the `recovery` wrap are
  untouched, so zero songs need re-encryption and the original recovery code
  keeps working). `regenerateRecoveryWrap` is the inverse: mints a fresh code,
  replaces only the `recovery` wrap, requires the DEK already in hand.
  `migrateWrapIfNeeded` existed with zero callers before this phase (Phase 6
  built it, nothing invoked it) — now called from `signIn`'s existing-envelope
  branch, best-effort, matching the web app.

**Envelope-write safety** (`SupabaseAuthRepository.kt`, `supabase/schema.sql`):

- **`envelope_rev`** now guards `user_keys`, the same optimistic-concurrency
  shape `songs.rev` already had — it was the one row in the whole schema whose
  loss is unrecoverable-by-construction and the only one written unguarded.
  `insertUserKeys` uses a real INSERT (throws on a `user_id` conflict);
  `updateUserKeysWithRevCheck` requires the caller's last-known rev to still
  match. Mirrored by `user_keys_history`, an append-only table a `BEFORE
  UPDATE` trigger writes to automatically — if any future bug ever does
  clobber a live envelope, the previous one (with its still-valid recovery
  wrap) is sitting right there instead of gone forever. Read-only from the
  client's side; the trigger is `SECURITY DEFINER` so RLS doesn't block it.

**Recovery-code redemption UI** (`app/src/main/java/com/songnotes/android/`):

- **`RecoveryUnlockScreen.kt`** — the Android side of the web app's
  `keyMismatch` flow: a recovery-code field, `recoverWithRecoveryCode` +
  rewrap-for-the-current-password. Reached whenever `EnvelopeKeyMismatchException`
  is thrown (a new exception type distinguishing "auth succeeded, envelope
  doesn't match" from a generic failure, so the UI can route here instead of
  showing a raw error string with no way forward). Only the non-destructive
  path lives on Android — "lost the code too" links out to the web app's
  `/forgot-password` rather than reimplementing the destructive rotate+purge
  flow and Supabase email-link handling before the app has even shipped, per
  the plan's own scoping decision.
- **`LockedAccountScreen.kt`** — at the time of this phase, gated the Songs
  home screen whenever `authRepo.isSignedIn && !KeySession.isUnlocked()`, the
  common case being simply that the process was killed since the last sign-in
  (`KeySession` is memory-only by design). Before this phase, that state had
  **no UI at all** — `SongSyncWorker` no-ops successfully when
  `KeySession.current()` is null, so the app just sat there
  signed-in-but-silently-not-syncing. Also the landing spot right after a
  **web-side** password reset: GoTrue revokes other sessions on a password
  change, so a web reset kills Android's session, and its next sign-in throws
  `EnvelopeKeyMismatchException` — routed to `RecoveryUnlockScreen` from here
  exactly like any other mismatch. Includes a "Forgot your password? Sign out
  instead" escape hatch (mirrors the web app's `PrivacyScreen`) — without one,
  someone who's genuinely forgotten their password would be stuck on this
  screen permanently.
  >
  > **Phase 13 update (2026-08-15):** the "gates the Songs home screen" part
  > is no longer true — Android became local-first, and gating the entire
  > song list behind an account password made no sense once an account is
  > optional. `LockedAccountScreen` is unchanged as a composable but is now
  > reached only from an actual Sync button press, never as a launch gate. See
  > `docs/handoff/PHASE-13-local-first.md`.
- **`AuthScreen.kt`** — "Forgot your password?" opens the web app's
  `/forgot-password` via a plain `ACTION_VIEW` intent (no Custom Tabs
  dependency added for one link). `signIn`'s return type changed from `Unit`
  to `String?` — a freshly minted recovery code (first sign-in for an account
  with no envelope yet) is now shown and acknowledged before proceeding,
  instead of silently discarded as it was before this phase.

**`dek_id` stamping** (`SyncEngine.kt`, `SupabaseSongsAdapter.kt`,
`SongSyncWorker.kt`, `KeySession.kt`) — makes a destructive "lost the code,
start fresh" reset on one device actually stick instead of silently reversing
itself the next time another device syncs:

- `KeySession` now tracks `dekId` alongside the DEK (`establish(dek, dekId)`).
- `SongRow.dek_id` (nullable — existing rows predate the column and can't be
  backfilled retroactively) is stamped from it on every push.
- `SongSyncWorker` fetches the account's **live** `dekId`
  (`SupabaseAuthRepository.fetchCurrentDekId`) before every sync and compares
  it to `KeySession`'s. On a mismatch — a DEK rotation happened elsewhere,
  most likely a recovery-code-lost reset on another device — it clears the
  now-known-stale session and skips the sync entirely, rather than pushing
  local edits re-encrypted under a key that's already dead.
- `SyncEngine.pull` no longer aborts its whole pass on the first row it can't
  decrypt (no `try`/`catch` at all, before this phase) — one bad row (wrong
  `dek_id`, or genuine corruption) is now skipped, matching the resilience the
  web app's `_placeholderSong` fallback already had in `songsRepository.js`.

## Cross-implementation test vectors

`spec/recovery-code-vectors.json` — one of the four fixtures
`docs/WIRE-FORMAT-v2.md` §7 has always marked mandatory, and the first one
actually delivered beyond the envelope-v2 pair. Two sections:

- **`normalize`** (40+ cases): a broad spread of real-world-plausible input
  mangling — case, hyphens, whitespace, full-width characters, confusable/
  excluded characters — run through the real `normalizeRecoveryCode` on the
  web side, output recorded rather than predicted, same "don't hand-translate
  tests" strategy every other fixture in this repo already uses.
- **`kek`** (3 cases): fixed `(input, salt)` pairs run through real Argon2id —
  `hash-wasm` on the web side, Bouncy Castle's `Argon2BytesGenerator` here —
  proving the two implementations produce **byte-identical** key material, not
  just structurally similar JSON. One entry deliberately uses messy
  (lowercase, no-hyphen) input that normalizes to the same string as a clean
  one, proving normalize-then-derive parity end to end, not normalization in
  isolation.

Unlike the envelope-v2 pair, this fixture needs **no Kotlin-writes-a-fixture
reverse direction** — `normalizeRecoveryCode` and the fixed-salt KDF
derivation are both pure functions of their inputs (no random DEK involved),
so there's nothing for a from-Android generation pass to prove that the
from-web copy doesn't already cover. `RecoveryCodeGoldenFixtureTest.kt` only
ever **reads** the committed copy — see that file's own doc comment, which
also names the specific hazard this sidesteps:
`EnvelopeV2GoldenFixtureTest.kt`'s reverse direction writes into the sibling
web repo's working tree as a side effect of a Kotlin test run
(`writeIfParentExists`, now renamed `writeToExistingParent` and fixed to
never `mkdirs()` a stray directory tree on a machine without that sibling
checkout — found while working in this area for this phase, not new).

Both test classes pass: `normalizeRecoveryCode` and Argon2id both agree
byte-for-byte across languages/libraries, not just "looks right."

## Two real bugs found via on-device verification (not this test suite)

A physical device was available for this phase, and both were caught only by
actually running the app against the real Supabase project:

1. **`signUp` threw a raw, unhelpful exception**
   (`"signUpWith(Email) succeeded but no current user"`) whenever the project
   has "Confirm email" enabled — `signUpWith(Email)` then creates the auth
   user but grants no session, so there's nothing to authenticate an envelope
   write with yet. This project turned out to actually have that setting on,
   live — not a hypothetical edge case reasoned about in the abstract, an
   error message that came back on the first real signup attempt. `signUp`'s
   return type changed to `AccountKeys?`; `null` means "check your email,"
   and `AuthScreen.kt` now shows that instead of surfacing the raw exception.
   The envelope + recovery code get created on the first post-confirmation
   sign-in instead (already handled by the fixed `signIn` above).
2. **The first-guess forgot-password placeholder URL was a live, unrelated
   product.** `WEB_FORGOT_PASSWORD_URL` was initially set to
   `https://songnotes.app/forgot-password` — tapping "Forgot your password?"
   on-device opened a real, already-deployed site (Clerk-based auth,
   nothing to do with this app). Replaced with
   `https://example.com/songnotes-forgot-password-placeholder` —
   `example.com` is IANA-reserved for exactly this purpose, guaranteed not to
   collide with anyone's real product. Same "doesn't have to go anywhere yet"
   spirit as the web app's own Play Store placeholder link
   (`MobileAppPromo.jsx`), just without the risk of silently pointing at
   someone else's live site in the meantime. **TODO before release:** point
   this at the real deployed web origin once one exists.

## What's left (deliberately deferred)

- **Android deep-link/App Link password reset.** The reset link itself always
  opens the web app; Android never intercepts a `songnotes://` (or App Link)
  URL and completes the flow in-app. Explicit scope decision, not an
  oversight — building Supabase email-link/deep-link handling before the app
  has even shipped a Play Store listing wasn't worth it this pass.
- **No Kotlin v1-envelope reader.** Unchanged from Phase 7's own decision,
  reaffirmed here: `EnvelopeV2.fromJson` still hard-`require`s `v == 2`. No v1
  envelopes exist for this app to need to read.
- **No server-side recovery-code hashing.** Would let the server verify a
  code without the client deriving a full KEK first (cheaper failed-attempt
  rejection), but weakens the zero-knowledge property — the server would need
  to learn *something* derived from the code — for a marginal gain against an
  already-expensive-to-brute-force Argon2id KEK. Not pursued.
- **`kdf-vectors.json`, `song-vectors.json`, `chord-anchor-vectors.json`** —
  the other three fixtures `docs/WIRE-FORMAT-v2.md` §7 names as mandatory.
  Still not built. `recovery-code-vectors.json` (this phase) and the
  envelope-v2 pair (Phase 6) are the only ones delivered so far.
- **`docs/WIRE-FORMAT-v2.md` itself remains partially aspirational.** This
  phase corrected the specific claims it touched (KDF JSON key names,
  `dekId`'s real size/encoding, the recovery-code normalize algorithm's
  precise edge cases, the `dek_id` nullability this phase actually shipped)
  and added a status note pointing at the larger pre-existing gap (the
  documented DB-trigger-based `rev` bump, the `title`/`is_locked` column
  removal, and the `meta`-wrapped content JSON shape were never implemented
  on either client — an acknowledged Phase 7 deferral, not something this
  phase attempted to close).
