# Phase 7 — Auth + Supabase sync

**Status (2026-07-31): First pass done — Tier 0 sync fix (rev-based optimistic
concurrency, tombstones, conflict copies) shipped in the desktop web app, per
the plan's own explicit priority ("fix the data-loss bug before the product
UI"). Android auth/sync (Credential Manager, supabase-kt, outbox +
`SyncWorker`) NOT started — see "What's left" below.**

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

## What's left (this phase, deliberately deferred)

- **The migration SQL hasn't been run against the live Supabase project
  yet** — the user needs to paste the updated `supabase/schema.sql` into
  their Supabase SQL Editor. Until then, the new `rev`/`deleted_at` columns
  don't exist server-side and this code will fail against the real backend
  (only the fake-adapter test suite has exercised it so far).
- **Tier 1 (per-line 3-way merge)** — explicitly deferred by the plan itself
  ("comes after the product UI"), not attempted this pass.
- **Android auth (Credential Manager → `signInWithIdToken`)** — not started.
  The Android app has zero Supabase/auth integration today (no
  `supabase-kt` dependency, no sign-in screen, nothing) — Phase 6's Keystore
  device wrap was built and verified standalone specifically *because*
  there's no real account flow yet to wire it into.
- **Android `supabase-kt` + outbox + `SyncWorker`** — not started. This is
  the Android-side consumer of the exact `rev`/`deleted_at` sync model this
  pass just established server-side; building it against a schema that
  doesn't match what's live would be premature.
- **`dekId` stamped on rows** — still not done (carried over from Phase 6's
  own "What's left"); this phase's sync work didn't add it either, since
  nothing yet needs to distinguish "encrypted under a previous key" from
  "decrypt threw."
