# Phase 7 — Auth + Supabase sync

**Status (2026-08-06): Done — Tier 0 sync fix shipped in the desktop web app
(2026-07-31 pass), followed by full Android auth/sync (email+password via
supabase-kt, Room sync columns + migration, `SyncEngine`/`SyncWorker`,
sign-in/sign-up UI) built and verified end-to-end against the real Supabase
project on a physical device, cross-checked against the web app. See "Android
auth + sync" below for what shipped, two real bugs found by that live
verification, and what's still deliberately deferred.**

Scoped deliberately, same reasoning as every prior phase's first pass: the
plan calls out the Tier 0 sync fix as the thing to do *first* in this phase,
ahead of any Android-side auth work, because it fixes real, currently-live
bugs (deletes silently never propagate; concurrent edits on two devices
silently drop one side) rather than adding a new feature — and because the
schema/reconciliation model it establishes is what any future Android sync
client needs to speak, so getting it right once (web app) before a second
implementation (Android) needs to match it is the same "design once, port"
sequencing Phase 6's envelope v2 followed.

## What shipped

**Schema (`supabase/schema.sql`)** — the whole file is written to be
re-runnable against an already-applied database (`if not exists`/`if exists`
guards throughout), so applying this update is "paste the same file into the
Supabase SQL Editor and run it again," not a separate migration file to
track:

- **`rev integer not null default 1`** — bumped on every write, checked via
  `WHERE id = ? AND rev = ?` on updates (optimistic concurrency). The
  `default 1` backfills every pre-existing row automatically when the column
  is added.
- **`deleted_at timestamptz`** (nullable) — a tombstone column. Deletes are
  now an `UPDATE ... SET deleted_at = now()`, not a real `DELETE`, so a
  delete on one device is just another row version other devices' sync can
  see and reconcile against, instead of silently never propagating (the live
  bug the plan calls out by name).
- **`title` column dropped** — every write has been fully encrypted for a
  while now (nothing sets `encrypted: false` anymore), so this plaintext
  column was always `NULL` in practice and existed only as an invitation to
  leak titles server-side later "for speed." Safe to drop: the encrypted
  path never read it, and the legacy unencrypted-row read path gets the
  title from `content.title` (the JSON blob), not the column.
- **Index swapped** from `(user_id)` to `(user_id, updated_at)` — "fix now
  while free" per the plan, ahead of any Android `SyncWorker` actually doing
  incremental `updated_at > last_sync_at` pulls against it.

**`src/store/songsRepository.js`** (`CloudSongsRepository`/
`SupabaseSongsAdapter`):

- **`_reconcile` now compares by `rev`, not wall-clock `updated_at`** — `rev`
  is coordinated via the optimistic-concurrency check itself, so it's immune
  to clock skew between two devices' system clocks the way comparing
  timestamps directly isn't.
- **`SupabaseSongsAdapter.update` replaced with `updateWithRevCheck(id, row,
  expectedRev)`** — issues `.eq('rev', expectedRev)` alongside `.eq('id',
  id)` and returns `{ conflict: true }` (not an error) when nothing matched,
  since a lost race is an expected, handled outcome, not a failure.
  `SupabaseSongsAdapter.remove` is gone entirely — nothing calls a real
  delete anymore, only the tombstone-via-update path.
- **`remove(id)` writes a tombstone** (`deleted_at` + bumped `rev`) via
  `updateWithRevCheck` instead of calling a delete method. Tombstoned rows
  stay in both the server table and the local cache (so reconciliation on
  other devices/sessions can see them) but are filtered out of everything
  `list()`/`get()` actually return to callers.
- **A lost optimistic-concurrency race writes a conflict copy, never drops
  the edit.** `_pushOne` detects `{ conflict: true }` and calls
  `_writeConflictCopy(song)`, which inserts the losing edit as a *new* song
  (`crypto.randomUUID()`, fresh `rev: 1`) with `"<title> (conflict copy —
  <device>, <time>)"` baked into the plaintext title *before* encryption —
  the `title` column being gone means there's no server-side place left to
  stamp a marker, so it has to live inside the ciphertext like everything
  else. `getDeviceLabel()` (new, local to this file) produces something like
  `"Chrome on Windows"` from `navigator.userAgent`, matching this codebase's
  existing OS-sniffing convention in `DAWPanel.jsx`'s
  `getDefaultPipelineOverheadMs`.
- **`expectedRev` tracking, not re-reading the cache's own optimistic
  bump** — both `update()` and `remove()` capture the rev they believe the
  *server* currently has (from an in-flight debounce entry if one exists,
  otherwise from the cache) *before* writing their own optimistic bump to
  the cache. Re-reading `rev` from the cache after that bump would compare
  the optimistic-concurrency check against a rev the client itself just
  invented, not the server's actual last-confirmed state — this exact bug
  was caught by a failing test during this pass (see below), not by
  inspection.
- **Delete vs. concurrent edit**: if a delete's tombstone write loses its
  own optimistic-concurrency race (someone edited the song at the same
  moment), Tier 0's answer is simply to let that edit stand — the delete
  doesn't force itself through, and it doesn't get a conflict-copy either.
  A full delete-vs-edit 3-way resolution is explicitly Tier 1 scope per the
  plan ("per-line 3-way merge... comes after the product UI").

**Tests** (`src/test/songsRepository.test.js`): `FakeRemoteAdapter` updated
to the new `updateWithRevCheck` interface (mirrors the real
`WHERE id = ? AND rev = ?` semantics exactly, including returning
`{conflict: true}` on a rev mismatch). Existing tests updated for the new
shape (no `title` column, rev instead of timestamp-based reconciliation,
tombstone instead of hard delete). Two new tests added, each directly
targeting one of the two bugs the plan calls out by name:

1. *"a delete on another device propagates: `list()` hides the song once its
   remote row is tombstoned"* — simulates a second device's delete by
   writing a tombstoned row directly into the fake remote (bypassing this
   repository instance entirely, the same way a real second client's write
   would only ever be visible through the remote), then asserts `list()`
   no longer returns it.
2. *"a losing optimistic-concurrency race keeps the edit as a new
   conflict-copy song instead of dropping it"* — simulates two devices
   racing on the same song id, asserts the loser's edit survives as a
   second row with the conflict-copy title pattern, not silently discarded.

**One real bug found and fixed during this pass** (exactly the kind of thing
a real test — not code review — exists to catch): `remove()`'s
`expectedRev` was read directly from the cache's `existingRow.rev`, which
can already reflect an *unconfirmed* optimistic bump from a just-cancelled
pending edit (e.g. `update()` then immediately `remove()` before the edit's
debounced push ever fired) — comparing against a rev the client itself just
invented, not the server's actual state, made every such delete falsely
report a conflict. Fixed by capturing `expectedRev` from the in-flight
debounce entry (if one exists) before clearing it, mirroring exactly the
logic `update()` already used for the same reason. Caught by the first new
test failing with a spurious "conflicted with a concurrent edit" log line.

**Verification**: 249/249 JS tests pass (18 files), lint clean on the two
touched files, `npm run build` succeeds. **Not verified against a live
Supabase project** — the schema change requires the user to run the updated
`supabase/schema.sql` themselves (the app has never run migrations itself;
this repo has no Supabase credentials to do it on their behalf, and
modifying a live project's schema isn't something to do without their own
hand on it regardless). Real-world verification (two actual browser sessions
racing a conflict, or a real cross-device delete) is still open until that
SQL has been applied and someone drives it end-to-end.

## Android auth + sync (2026-08-06 pass)

**Auth**: `core/data/.../SupabaseAuthRepository.kt` — plain email+password via
supabase-kt's `Auth`/`Postgrest` plugins, deliberately matching the web app's
own `AuthProvider.jsx` flow exactly (same Supabase project, same `user_keys`
table, same envelope v2 format) rather than Google Sign-In/Credential
Manager, since the web app has no OAuth provider configured and matching its
existing method is what actually keeps both clients on the same backend.
supabase-kt pinned to **3.0.0** (not latest) — newer releases need a Kotlin
compiler version incompatible with this project's Kotlin 2.0.21 pin; verified
by decompiling the actual `.aar`/`.jar` via `javap` to confirm the real
package is `io.github.jan.supabase`, not the commonly-guessed
`io.github.jan-tennert.supabase`.

**Sync**: `core/data/.../SyncEngine.kt` (push pending + incremental pull,
plain suspend functions against a `SongsRemoteAdapter` interface so it's
testable with `FakeSongsAdapter`/`FakeSongDao`, no real network needed) +
`SongSyncWorker.kt` (thin `CoroutineWorker` wrapper). `SongEntity` gained
`rev`/`deletedAt`/`pendingSync`/`remoteRev` columns via Room migration 1→2
(raw `ALTER TABLE`, `exportSchema = false` — Room's KSP schema-bundle export
hits an `AbstractMethodError` against this project's pinned
kotlinx-serialization version, the same class of "too new for Kotlin 2.0.21"
conflict as the supabase-kt pin above; verified live on-device instead).
Chords are pushed/pulled as `:core:domain`'s native anchor shape directly —
no conversion needed on the Android side, unlike the web app (see "Wire
format" below).

**Manual "Sync now" button** (`MainActivity.kt`, Diagnostics screen, visible
when signed in): there is currently **no periodic background sync and
nothing triggers a sync after a local edit** — `enqueueOneTime()` is only
ever called right after sign-in/sign-up. Without a manual trigger, verifying
a push or pull required signing out and back in for every single change,
which is both slow and defeats the point of testing incremental sync. This
button is a real, permanent affordance (not just a test hook) until periodic
sync exists.

### Two real bugs found by live device verification (not code review)

Both were caught only because this pass insisted on testing against the real
Supabase project on a physical device and cross-checking the live web app,
rather than stopping at the fake-adapter unit tests.

1. **`signUp()` silently overwrote a real account's encryption envelope.**
   `client.auth.signUpWith(Email)` can resolve `currentUserId` successfully
   even for an *already-registered* email, if a Supabase Auth session for
   that user was already active (e.g. a `signIn` had just succeeded moments
   earlier before throwing on an unrelated envelope-parse error, and the
   user then retried via the Sign Up tab). The original code treated any
   resolved `currentUserId` as license to mint a fresh DEK and overwrite
   `user_keys.envelope` — no check for whether a row already existed. This
   happened for real during this pass's own on-device testing and
   permanently orphaned the test account's existing songs. **Fixed**:
   `signUp()` now throws (`check(!hasStoredUserKeys(userId))`) before
   creating any keys if a `user_keys` row already exists; `signIn()` fetches
   the raw row first and only attempts `EnvelopeV2.fromJson` inside the
   definitely-non-null branch, so a parse failure can never be
   misinterpreted as "no envelope, safe to create one." No unit test covers
   this specific fix — `SupabaseAuthRepository` isn't structured for
   fake-based testing the way `SyncEngine` is (would need mocking the whole
   `Auth` plugin); a real, acknowledged gap.
2. **`SyncEngine.parseIso()` couldn't parse Postgrest's own timestamp
   format.** It used `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")` with
   a hardcoded literal `'Z'`, but Postgrest returns `timestamptz` columns
   as `+00:00`-offset text (e.g. `"2026-08-06T22:43:22.991+00:00"`), which
   that pattern rejects outright. Every `pull()` that touched a
   `deleted_at`/`created_at`/`updated_at` value threw and the whole worker
   silently retried forever (`Result.retry()` with no logging, so this was
   invisible until `Log.e` was added specifically to chase it down) —
   concretely, a delete made on the web app never actually reached the
   Android device. **Fixed**: switched to `java.time`
   (`OffsetDateTime.parse(iso).toInstant()`), which accepts both `Z` and
   `+00:00` — safe since `minSdk = 30` needs no desugaring. `SongSyncWorker`
   keeps the `Log.e` on sync failure permanently; a silent `Result.retry()`
   with no logging is exactly how this bug went undetected in the first
   place.

**Verified end-to-end on a physical device** against the real Supabase
project (`cryokinetic2468@gmail.com`, a dedicated test account): sign-in →
create a song on Android → "Sync now" → confirmed the song appears correctly
on the web app (same title, decrypts correctly) → deleted it on the web app →
"Sync now" on Android → confirmed the tombstone pulled and the song
disappeared from the Android list. Both push and pull, both directions,
against the real backend, not just the fake-adapter test suite.

**A separate, unrelated incident during this same verification pass**: the
Supabase free-tier project had auto-paused from inactivity, which produced a
different, easily-confused error ("`signUpWith(Email)` succeeded but no
current user") — initially misdiagnosed as an email-confirmation-gating
issue before being correctly identified as the project simply being paused.
Unpausing it via the Supabase dashboard resolved it; no code was at fault.

## What's left (deliberately deferred)

- **Periodic/automatic background sync** — there is only the manual "Sync
  now" button and the one-shot sync on sign-in/sign-up. No `PeriodicWorkRequest`,
  no sync-after-local-edit trigger.
- **No background DEK unlock** — `SongSyncWorker` no-ops successfully if
  `KeySession` has no DEK established (i.e. the app process was killed since
  the user last signed in). A true background sync would need Phase 6's
  Keystore device-wrap wired into a background-safe unlock flow, which
  doesn't exist yet.
- **Kotlin has no v1-envelope reader** — `EnvelopeV2.fromJson` throws on
  anything but a v2 envelope, by design (Phase 6's own scoping decision,
  reaffirmed explicitly during this pass rather than building v1 compat:
  "no need to protect legacy accounts as nothing is live"). Any account
  whose envelope predates v2 cannot sign in from Android.
- **Full `WIRE-FORMAT-v2.md` alignment is partial.** This pass aligned only
  the chords representation (anchors, not the web app's old padded-string
  form — see "Wire format" below); the doc's `dekId` column, DB-side rev-bump
  trigger, and `v`/`id`/`meta`-wrapped content-JSON shape were not
  implemented on either client. Both clients still use the simpler
  client-computed-rev scheme and flat content-JSON shape this Tier 0 pass
  originally built.
- **Tier 1 (per-line 3-way merge)** — still explicitly deferred by the plan
  itself.
- **`dekId` stamped on rows** — still not done (carried over from Phase 6).

## Wire format: chords as anchors, not the padded editing string

Discovered while building the Android sync path: `:core:domain`'s `Song`
model stores each line's chords as `ChordAnchor(i, c)` list (the shape
`WIRE-FORMAT-v2.md` specifies), but the web app's `songsRepository.js` was
still storing `line.chords` as the same fixed-width padded string its editor
uses internally — a representation with no defined behavior once lyrics
differ in length from what the chords were originally padded against (e.g.
edited on a different device/font). Rather than have Android either read a
format it doesn't understand or duplicate the web app's padding logic, the
web app was converted to store anchors too:
`src/utils/chordAnchors.js` (`chordsLineToAnchors`/`anchorsToChordsLine`,
line-for-line port of `ChordAnchors.kt`) plugs into
`songsRepository.js`'s `_buildRow`/read path, with a guard
(`Array.isArray(l.chords) ? anchorsToChordsLine(...) : l.chords`) to keep
reading pre-existing encrypted rows still in the old padded-string shape.
Both clients now read/write the exact same anchor shape with zero
conversion needed on the Android side.
