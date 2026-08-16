# Phase 13 — Local-first Android, opt-in manual sync

**Status (2026-08-15): done, verified on-device.** Android changes direction: an
account is now entirely optional, and nothing ever leaves the phone except when
the user explicitly presses Sync. This is a deliberate divergence from the
desktop web app, not a bug or a regression — see "Why Android diverges from the
web app" below.

## Why

Every phase through Phase 12 built Android as account-first: every local edit
flagged `pendingSync = true` on the assumption sync would eventually run
automatically, `AuthScreen` pushed on sign-in, and `LockedAccountScreen` blocked
the entire Songs home screen whenever the app was signed in without a DEK in
memory — the normal state after every process death, since `KeySession` is
memory-only by design. The result was the worst of both models: sync that was
always implied but never actually automatic (no periodic `WorkRequest` was ever
built), plus an account that gated local work it had no business gating (the
SQLCipher DB key has nothing to do with the account DEK).

The new direction: Android is a **local-first songwriting app**. Songs live on
the phone, encrypted at rest via a Keystore-wrapped SQLCipher key that requires
no account, no password, and no network. An account is an opt-in upgrade —
"sync my songs so I can also edit them from the web app" — not a precondition
for using the app at all.

## Why Android diverges from the web app

The web app is necessarily account-first (there's no "local-only" browser
storage story that survives a cache clear the way an encrypted on-device
database does), and this phase makes no change to it, to the wire format, or to
the Supabase schema. Android's local storage was always independently viable —
Phase 6 built Room + SQLCipher specifically so "the DB opens before unlock" —
this phase is the first time the app's *behavior* actually reflects that
independence instead of routing every user through an account-shaped funnel
regardless of whether they want one.

## Five pre-existing defects fixed first

Manual-only sync has no automatic retry to paper over a bug — every one of
these was survivable under Phase 7's always-on model and became severe once
sync is strictly a user-initiated action:

1. **`SongRepository.upsert` silently reset `remoteRev` to `null` on every
   edit** (`SongEntity.fromDomain` never carried it, and the `.copy()` never
   restored it from the existing row). Editing an already-synced song made
   `SyncEngine.pushPending` attempt an `insert` on an id that already existed
   remotely — a Postgres unique-violation that left the row permanently
   unsynced. Fixed in `SongRepository.upsert`.
2. Same method forced `deletedAt = null` unconditionally, resurrecting any
   tombstoned row it touched (e.g. via the legacy-JSON import re-running).
3. Same method wrote whatever `createdAt` the caller's domain object carried,
   which could be `0L` from the old `emptySong()` placeholder — now preserved
   from the existing row instead.
4. `SongEditorScreen`'s old `persist()` was labelled a debounce but had no
   `Job` handle (so N keystrokes produced N writes, `rev += N`) and ran on
   `rememberCoroutineScope()` (so leaving the composition mid-debounce silently
   dropped the last edit). Replaced by `SongDraftAutosaver`, a real
   `Job`-cancelling debounce on a process-lifetime scope.
5. No `SongDatabase` singleton — four independent `Room.databaseBuilder` calls
   over one physical file, none closed. Harmless as a leak before this phase;
   would have made the new unsynced-count banner silently stale (a `Flow` from
   one `RoomDatabase` instance never sees a write made through another).

## What shipped

**`:core:data`** — `SongRepository` gains `observeAllWithSyncState()` (feeds
the list's delete-confirmation copy) and `deleteRespectingSync()` (hard-deletes
a never-synced song, tombstones one that's reached the server). `SyncEngine`
gains an `adopt` pass (`reconcileForAdoption`, below), per-row error isolation
in `pushPending` (one bad row no longer blocks the whole batch), a fixed
conflict branch, and a bounded second push pass so a conflict copy created
mid-sync is pushed within the same `sync()` call rather than waiting for a
"next automatic pass" that no longer exists. New `SyncPreferences` (device-local
opt-in state, plain SharedPreferences — matches the existing
`CalibrationStore`/`RecordingInputPreference` idiom, no new dependency),
`SyncStatusRepository` (one reactive `SyncStatus` combining Room pending-counts,
`SyncPreferences`, live `WorkInfo`, and auth state), and `SyncController` (the
one place that decides whether a Sync press can proceed — `gate()` — and the
only caller of `SongSyncWorker.enqueueOneTime`). `SongSyncWorker` no longer
retries (`Result.retry()` under manual-only sync IS an automatic sync — replaced
with `Result.failure` written to `SyncPreferences.lastSyncError`) and uses
`ExistingWorkPolicy.KEEP` instead of `REPLACE` (a double-tap on Sync must not
abort a half-pushed batch).

**`:app`** — `MainActivity`'s `Screen` becomes a sealed interface
(`SongEditor(songId)` instead of a sibling nullable `editingSongId`, so "in the
editor with no id" is no longer representable). The `LockedAccountScreen` gate
around the whole Songs branch is gone — local songs are always usable, locked
or not; the password is demanded only at the moment of an actual Sync press.
`AccountRow` becomes `SyncHeader` (sign-in/out only). New `SyncBanner`
(persistent unsynced-state strip on the song list), `SongDialogs`
(`DeleteSongDialog`, `UnsyncedExitDialog`, `SignOutConfirmDialog`),
`SyncOptInScreen` (the explainer before sign-up, and a post-signup "manual
only" notice), `SongDraftAutosaver` + `EditorSessionStore` + `AppScope` (see
"Never losing local work" below). `BackHandler` is now used throughout — there
was previously **zero** back-press handling anywhere in the app (system Back
from any screen just finished the Activity); it's added to every screen, most
importantly the home screen's unsynced-changes exit dialog.

## Adoption — enabling sync without damaging anything

`SyncEngine.reconcileForAdoption`, run once per account before the first push
whenever `SyncPreferences.adoptionCompletedForUserId != userId`:

```
for each local row:
  tombstoned AND never pushed  -> hard-delete (nothing on the account to tell)
  already has a remoteRev      -> skip (already has a proven remote lineage)
  remote = adapter.getById(id)
  remote == null                -> skip; the ordinary push inserts it cleanly
  remote exists:
    content identical (ignoring updatedAt)
      -> adopt the remote lineage (rev/remoteRev = remote's, pendingSync = false)
         — this device synced before (a reinstall, or defect #1 above)
    content differs
      -> re-id the local copy to a fresh UUID and push it as new
         — the REMOTE ROW IS NEVER TOUCHED
```

The re-id branch is the answer to a real conflict in the requirements: "push
every existing local song" and "never damage an existing remote song" are
mutually exclusive when a local id collides with a remote id under different
content. Giving the local copy a new identity satisfies both — both songs end
up on the account, nothing is overwritten. `SupabaseSongsAdapter.getById`
(zero production callers before this phase) is what makes the precheck
possible; because it has no `user_id` filter, RLS can still hide a collision
from it, so `pushPending` also gained an insert-collision fallback (catch, ask
`getById` whether the id exists, re-id and retry once) as the real backstop.
Verified with a from-scratch JVM test suite (`SyncEngineAdoptionTest.kt`) —
clean adoption, identical-content adoption with zero network writes,
different-content re-id with the remote row provably untouched, the
RLS-invisible-collision fallback, tombstone reclamation, and idempotence
(running adoption twice makes zero remote writes the second time).

**Account switch** (signing into a different account than sync was last enabled
for): every local `remoteRev` points into the old account's namespace, which
the new account can't see under RLS — pushing without handling this would
produce a conflict copy for every song. `SyncController.enableSyncFor` detects
the switch and calls the new `SongDao.detachFromRemote()` (`remoteRev = NULL`,
`pendingSync = 1` for every row) before the next sync, forcing a fresh adoption
pass instead.

## Never losing local work

Room already *is* the durable draft store — SQLCipher-encrypted, transactional,
survives process death — so the fix is entirely about *when* it's written, not
a second persistence layer. `SongDraftAutosaver` holds a cancellable `Job`
(`schedule()` cancels any prior pending write before starting a new one — a
real debounce, collapsing a typing burst into one write) running on
`AppScope.io`, a process-lifetime `CoroutineScope`, not
`rememberCoroutineScope()` — leaving the editor's composition no longer cancels
an in-flight write. A `Mutex` prevents a lifecycle-triggered flush from racing
the debounce timer into two separate writes. `SongEditorScreen` flushes on
`Lifecycle.Event.ON_STOP` (the last callback guaranteed before the process
becomes a kill candidate) and in `onDispose`. `EditorSessionStore` records the
open song id so `MainActivity` can reopen straight into that editor at launch
(guarded by `repo.getById(id) != null` so a tombstoned/deleted song can't
resurrect a ghost editor).

**Verified on the physical test device (Samsung Galaxy Z Fold)**, not just in
theory: typed a title and a lyric line, then `adb shell am force-stop` with
**no** backgrounding first (skips every lifecycle callback entirely — the
worst case, harder than the debounce's own 350&nbsp;ms window would suggest).
Relaunching landed directly back in that song's editor with both the title and
the lyric line intact.

## Delete confirmation

`SongRepository.deleteRespectingSync` replaces the always-tombstone `delete`
for the UI path: `remoteRev == null` (never reached the server) hard-deletes,
`remoteRev != null` tombstones so the next sync can propagate it. Copy in
`DeleteSongDialog` matches — verified on-device: deleting a never-synced song
shows *"This removes it from this device. It was never synced, so nothing on
your account changes."*

## Verification

**JVM unit tests** (`core/data/src/test/kotlin/...`): `SongRepositoryTest.kt`
(new — the `remoteRev`/`deletedAt`/`createdAt` regressions, `deleteRespectingSync`
branching), `SyncEngineAdoptionTest.kt` (new — the adoption algorithm above),
`SyncPreferencesTest.kt` (new), `SyncEngineTest.kt` (extended — the
edit-then-sync round trip that regresses defect #1, the remote-row-gone vs.
genuine-conflict split, conflict copies pushed within the same `sync()` call,
the insert-collision re-id fallback). `FakeSongDao`/`FakeSongsAdapter` moved
into a shared `TestFakes.kt`. All 58 tests in `:core:data` pass; `./gradlew
test` is green across every module (`:core:domain`, `:core:audio`,
`:core:data`, `:app`, debug and release).

**On-device** (Galaxy Z Fold, debug build): fresh app, no account — home screen
shows no sync banner and no account gate; created a song, confirmed the
force-stop/relaunch/autosave story above; returned to the list and confirmed
the delete dialog's never-synced copy. Full live round-trip against the real
Supabase project: sign-in (no auto-pull, confirmed), manual push and pull,
the unsynced-exit dialog with real relative-time labels, the sanitized error
banner, and — critically — **switching between two real accounts** (a
disposable test account and the primary test account), which is what
surfaced the two bugs below. Not exercised live: a brand-new sign-up (the
`SyncOptInExplainer` screen) and DEK-rotation recovery — both are covered by
the JVM suite only for this pass.

## Debugging a sync failure

`SongSyncWorker`'s banner-facing failure messages are deliberately generic
("Sync failed. Try again.", "Couldn't reach the network...") — the raw
exception is never shown in the UI. This was tightened *during* this phase's
own live verification: a real failure (see "A real schema-drift bug found by
live testing" below) initially surfaced the full Postgrest/Ktor exception
text directly in the banner, which includes the request URL and the
`Authorization: Bearer <JWT>` header. Nothing sensitive was ever transmitted
anywhere by this — it only ever rendered on the device's own screen — but
it's not something a real user should see and not a habit worth keeping.

The real exception (message + full stack trace) still goes to Logcat, always,
via `Log.e(TAG, "sync failed", e)` in `SongSyncWorker.doWork()`'s catch
blocks. To see what actually failed:

```
adb logcat -s SongSyncWorker:*
```

## A real schema-drift bug found by live testing

Live-testing this phase's push path against the real Supabase project (not
the fake in-memory adapter the JVM suite uses) surfaced a genuine,
pre-existing bug unrelated to anything in Phase 13: the live `songs` table
was missing the `dek_id` column Phase 12 already stamps on every push
(`SyncEngine.buildRow`, `SupabaseSongsAdapter.kt`) — a deployment gap between
that column's presence in `supabase/schema.sql` (the web app's repo) and what
was actually migrated onto the live database. The push failed with `Could not
find the 'dek_id' column of 'songs' in the schema cache` until the column was
added directly in Supabase. Nothing here needed an Android code change; it's
recorded because it's exactly the class of thing a fake-adapter unit-test
suite structurally cannot catch, and it's worth remembering the next time a
push mysteriously fails against a real project that a JVM test run says is fine.

## Two real account-switch bugs found by live testing (both fixed)

Switching a device between two real accounts (sign out of A, sign in to B,
sign back in to A) surfaced two genuine bugs in the account-switch path that
the JVM suite's fakes had not caught — both are fixed as of this writing.

**1. `SyncPreferences.disableSync()` cleared `syncAccountUserId`, silently
breaking switch detection.** `SyncController.enableSyncFor` decides whether
to detach every local `remoteRev` (see "Adoption" above) by comparing
`prefs.syncAccountUserId` (the account these `remoteRev`s currently belong
to) against the account being signed into. Sign-out used to null that field
out — meaning by the time the user signed into a *different* account,
`enableSyncFor` saw no previous account on record and skipped the detach
entirely. A song already pushed to account A stayed marked `pendingSync =
false` under account B forever, having never actually reached B's `songs`
table — the UI confidently showed "All synced" for a song that was nowhere
on the currently-signed-in account. Caught live: a song pushed to a real test
account, then synced again after switching to a disposable Supabase test
account, silently never went anywhere. Fixed by leaving `syncAccountUserId`
alone in `disableSync()` — it's local-data state (which account these rows
belong to), not sync-enabled state, and doesn't become false just because the
user signed out. See `SyncPreferences.disableSync`'s own doc comment.

**2. The insert-collision fallback's `getById` gate was backwards, and
unreachable for the exact case it existed for.** `SyncEngine.pushPending`'s
plan for "adoption's precheck missed a cross-account id collision" was: catch
the insert failure, ask `getById`, and re-id-and-retry only `if (getById() !=
null)`. But a cross-account collision is hidden from `getById()` by RLS *by
definition* — the whole reason the fallback exists. That condition could only
be true in a same-account race, never in the RLS-hidden case it was written
to handle, so the retry path was provably unreachable for its own purpose. Confirmed live: `getById()` cleanly returned `null` twice in a row
(once from adoption's own precheck, once from this fallback) for an id whose
own `insert()` immediately 23505'd — undeniable proof the row existed, and
undeniable proof `getById` couldn't see it either time. Fixed by dropping the
gate: any insert failure now gets exactly one re-id-and-retry unconditionally
(a fresh random UUID essentially never collides again, so this is safe
regardless of the failure's real cause; if the retry also fails, that failure
surfaces normally, no worse off than before). `FakeSongsAdapter` gained a
`simulatedCurrentUserId` property so a JVM test can actually model RLS
scoping for `getById` — its absence was itself part of the problem: the
original unit test let `getById` see every row unconditionally, which made
the *backwards* fallback logic pass.

## A real schema-drift bug found by live testing

- **Account deletion** — the standing Play Store blocker from Phase 11 is
  unchanged by this work (it still needs a real in-app/web deletion path
  before submission), though its practical blast radius shrinks now that most
  users are expected to never create an account at all.
- **Periodic/background sync** — not deferred, actively rejected by this
  phase's own design. If a future phase wants it, that's a reversal of Phase
  13's core decision, not a follow-up to it.
- **Per-line 3-way merge** (`docs/PLAN.md`'s "Tier 1") — unaffected, still
  future work if it happens at all.
- No change to the wire format, the Supabase schema, or the web app.
