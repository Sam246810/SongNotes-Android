package com.songnotes.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

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
 * the ONLY signal; `SyncStatusInline` is the persistent one.
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

/**
 * Shown once after local storage had to be discarded because the Android
 * Keystore key protecting it was lost (a restored backup, a device transfer,
 * occasionally an OS update) -- see `LocalDataResetStore` and
 * `SongDatabase.getInstance`.
 *
 * Deliberately states the loss plainly instead of softening it. The app has
 * already recovered by the time this appears -- it resets rather than
 * crash-looping, which is the right trade -- but for a local-first app that
 * expects to hold songs which were never pushed anywhere, a silent reset would
 * mean the user's work vanishing with no explanation and no reason to suspect
 * one exists. The only genuinely actionable thing is stated last: anything that
 * had been synced is still on the account and comes back with a Sync press.
 */
@Composable
fun LocalDataResetDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Songs on this device were reset") },
        text = {
            // Two Text composables rather than one string with embedded
            // newlines: the paragraph break is a layout concern, and Column
            // spacing expresses it without baking hard line breaks into copy
            // that should re-flow to whatever width the device gives it.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This app's encrypted storage is protected by a key held in this device's " +
                        "secure hardware, and that key is gone -- which usually means the app data " +
                        "was restored from a backup or moved from another device. Without it, the " +
                        "songs stored here could no longer be read by anything, so they've been cleared.",
                )
                Text(
                    "If you'd signed in and synced, your songs are safe on your account: sign in " +
                        "and press Sync to bring them back. Anything that was only ever on this " +
                        "device, and never synced, is gone.",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
