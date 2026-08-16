package com.songnotes.android

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Phase 13: delete confirmation, with copy that depends on whether the song
 * has ever reached the server ([isOnAccount] -- `SongListItem.isOnAccount`,
 * true iff `SongEntity.remoteRev != null`). A song that's never been synced
 * has nothing on the account to warn about; one that has must make clear the
 * delete also reaches the web app on the next sync (`SongRepository.deleteRespectingSync`
 * tombstones it precisely so that propagation can happen).
 */
@Composable
fun DeleteSongDialog(title: String, isOnAccount: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val shownTitle = title.ifBlank { "Untitled" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"$shownTitle\"?") },
        text = {
            Text(
                if (isOnAccount) {
                    "This removes it from this device and from your account -- it will disappear " +
                        "from the web app the next time you sync."
                } else {
                    "This removes it from this device. It was never synced, so nothing on your " +
                        "account changes."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Phase 13: the back-press-from-home warning when sync is enabled and there
 * are unsynced changes -- the backstop for the fact Android gives no reliable
 * hook for a home-button or recents-swipe exit (only Back), so this can't be
 * the ONLY signal; `SyncBanner` is the persistent one.
 */
@Composable
fun UnsyncedExitDialog(unsyncedCount: Int, lastSyncLabel: String, onSyncNow: () -> Unit, onLeaveAnyway: () -> Unit, onDismiss: () -> Unit) {
    val songWord = if (unsyncedCount == 1) "song has" else "songs have"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unsynced changes") },
        text = {
            Text(
                "$unsyncedCount $songWord changes that aren't on your account. They're saved on " +
                    "this phone, but you won't see anything you've changed since $lastSyncLabel in " +
                    "the web app.",
            )
        },
        confirmButton = { TextButton(onClick = onSyncNow) { Text("Sync now") } },
        dismissButton = { TextButton(onClick = onLeaveAnyway) { Text("Leave anyway") } },
    )
}

/**
 * Phase 13: sign-out confirmation. Signing out never touches a single `songs`
 * row (see `SyncPreferences.disableSync`'s doc comment) -- this dialog exists
 * purely so the user isn't surprised that their unsynced edits stay
 * unreachable from the web app until they sign back in and sync.
 */
@Composable
fun SignOutConfirmDialog(unsyncedCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out?") },
        text = {
            Text(
                if (unsyncedCount > 0) {
                    "Your songs stay on this phone. $unsyncedCount ${if (unsyncedCount == 1) "change hasn't" else "changes haven't"} " +
                        "reached your account yet -- they'll stay here until you sign in and sync again."
                } else {
                    "Your songs stay on this phone."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
