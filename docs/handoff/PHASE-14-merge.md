# Phase 14 — Merge instead of conflict copies

**Status (2026-10-26): done; unit-tested, Room v3 -> v4 verified on-device.
Live web + Android sync not yet verified.** Behaviour, rules and examples are
in `docs/SYNC-MERGE.md` (shared with the web repo); this note is the Android
specifics.

## What changed

- **Merge engine** (`core/domain/.../merge/`): `SongMerge.kt`, `TokenDiff3.kt`,
  `EditStamps.kt`, `CaretMapping.kt` -- a line-for-line port of the web app's
  `src/sync/`. `SongMergeGoldenFixtureTest` runs the web-generated
  `spec/song-merge.json` (copy it in byte-identical when it changes), including
  symmetry and the tokenizer vectors.
- **Edit stamps**: `SongLine.editedAt`, `Song.fieldEditedAt`,
  `Song.customChordsEditedAt`. `SongRepository` stamps what each save changed
  (`stampEdits`); `SongDocument.kt` reads/writes them (WIRE-FORMAT §4).
- **Room v4** (`MIGRATION_3_4`): `baseDocJson` -- the server-confirmed version an
  unsent change started from, snapshotted on the clean -> pending transition and
  cleared once the push is confirmed -- and `editStampsJson`. Rows already
  pending at the upgrade have no base and merge without one if they conflict.
- **SyncEngine**: push sends `rev = remoteRev + 1`; a lost race merges against
  the base and pushes the merge (WIRE-FORMAT §5.1's table for deletes). Pull
  skips `row.rev <= remoteRev` and never touches a pending row. `rev` on the
  Room row is now only this device's write counter.
  `SyncOutcome.conflictCopies` became `merged` / `restored`.
- **Adoption**: a local song whose id already exists on the account merges with
  it (no base) instead of being re-ided as "(from this phone)"; only an
  unreadable or deleted account row still gets the re-id.
- **`SongWriteLock`**: every read-modify-write of a song row (editor saves, sync's
  local writes) holds it; sync's commit re-reads and keeps an edit saved during
  the push pending (it used to be marked synced unsent).
- **Stale editor**: the editor used to load a song once and overwrite anything a
  sync wrote while it was open. Saves now go through
  `SongRepository.saveEdit(song, basedOn)` (via `SongDraftAutosaver.base`),
  which merges with what's stored; the editor shows the result in place
  (`applyExternal`: focused paragraph and caret kept via `lastCaret` +
  `mapOffsetThroughEdit`, undo/redo cleared). While idle it watches the row
  (`observeStored`) and shows a sync at once; a song deleted elsewhere while
  open and untouched closes the editor.

## Known gaps / follow-ups

- Rough edges 1-2 in `docs/SYNC-MERGE.md` (join/split vs edit) -- planned
  split/join-aware retry, both platforms.
- `lastCaret` is the caret as of the last keystroke; a caret moved by tapping
  without typing is mapped from its previous typing position.
- No instrumented test infra (same as Phase 6/7): the migration and editor
  behaviour are verified on-device, not in CI.
