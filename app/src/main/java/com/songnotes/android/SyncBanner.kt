package com.songnotes.android

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.songnotes.core.data.SyncStatus

/**
 * Phase 13's persistent sync-state strip on the song list -- the primary,
 * always-visible signal for unsynced work. `UnsyncedExitDialog` (shown on
 * back-press from home) is the backstop for exits Android gives no reliable
 * hook for (home button, recents swipe); this banner is what's visible the
 * rest of the time the user is actually using the app. Renders nothing when
 * sync isn't enabled -- a local-only user never sees any of this, matching
 * "an account is optional and every feature except account-specific ones
 * works signed out."
 *
 * A signed-out-but-still-enabled state still shows a banner (a deliberate
 * softening of "only when enabled": GoTrue revokes the Android session on any
 * web-side password change, and hiding the banner then would make a user's
 * real unsynced work invisible with no explanation).
 */
@Composable
fun SyncBanner(status: SyncStatus, onSyncClick: () -> Unit, onSignInClick: () -> Unit) {
    if (!status.enabled) return

    val isError = status.lastError != null
    val message = when {
        isError -> status.lastError!!
        !status.signedIn -> "Sign in to sync -- ${status.unsyncedTotal} ${changeWord(status.unsyncedTotal)} waiting"
        status.running -> "Syncing…"
        status.unsyncedTotal > 0 -> "${status.unsyncedTotal} ${songWord(status.unsyncedTotal)} not synced · last synced ${lastSyncLabel(status.lastSyncAtMs)}"
        else -> "All synced · ${lastSyncLabel(status.lastSyncAtMs)}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        when {
            status.running -> CircularProgressIndicator(
                modifier = Modifier.padding(start = 8.dp).height(20.dp).width(20.dp),
                strokeWidth = 2.dp,
            )
            !status.signedIn -> TextButton(onClick = onSignInClick) { Text("Sign in") }
            else -> TextButton(onClick = onSyncClick) { Text(if (isError) "Try again" else "Sync") }
        }
    }
}

private fun songWord(count: Int) = if (count == 1) "song" else "songs"
private fun changeWord(count: Int) = if (count == 1) "change" else "changes"

/** "never" for null/0, otherwise a short relative label ("2 min ago", "yesterday", …) via the platform's own [DateUtils] -- also used by `UnsyncedExitDialog`'s copy. */
fun lastSyncLabel(lastSyncAtMs: Long?): String {
    if (lastSyncAtMs == null || lastSyncAtMs <= 0L) return "never"
    return DateUtils.getRelativeTimeSpanString(lastSyncAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}
