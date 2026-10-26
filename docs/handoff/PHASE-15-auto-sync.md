# Phase 15 — Sync when the app opens

**Status (2026-10-26): done; installed on the test device. Live auto-sync not
yet verified (needs a signed-in session).** Supersedes Phase 13's manual-only
rule (see the note at the top of `PHASE-13-local-first.md`).

## Behaviour

`MainActivity` observes its lifecycle; `ON_START` (single-activity app, so app
open or resume) calls `SyncController.requestAutoSync()`, which enqueues a sync
only when all of these hold:

- Supabase is configured, sync is enabled, and the signed-in user is the sync
  account (`SyncPreferences.syncAccountUserId`);
- `SyncPreferences.keepSyncUnlocked` is on (default on; Account screen,
  "Sync when the app opens");
- the account key is in `KeySession`, or `DekVault` can restore it;
- the last automatic sync started at least `AUTO_SYNC_MIN_GAP_MS` (30 s) ago.

Otherwise it does nothing -- it never prompts. The Sync button is unchanged.
Automatic runs (`SongSyncWorker` input `auto`) don't write "offline" or "enter
your password" to the sync banner; the next open retries.

## DekVault

Keeps the account DEK between launches: wrapped by a Keystore AES-GCM key
(`songnotes.sync_dek_vault`) with no user-auth requirement but
`setUnlockedDeviceRequired(true)`, ciphertext in private prefs
(`songnotes_dek_vault`, excluded from backup and device transfer by
`data_extraction_rules.xml`). Separate from `DeviceWrap`, which needs a
fingerprint on every use and so can't serve unattended sync.

- **Saved** after every successful sync while the setting is on.
- **Cleared** on sign-out / disabling sync (`SyncController.disableSync`, which
  every sign-out path calls), when the account key rotated elsewhere (worker's
  dekId check), when the setting is turned off, and when it can't be read
  (e.g. the Keystore key was invalidated).

Tradeoff (also in `docs/SYNC-MERGE.md`): anyone using the unlocked phone can sync
the account's songs without the password -- the same boundary as the existing
Keystore-held SQLCipher key.
