package com.songnotes.android

import android.text.format.DateUtils
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.songnotes.core.data.SyncStatus

/**
 * Phase 13's persistent sync-state signal, rendered inline on the "Songs"
 * heading row rather than as its own full-width strip. It used to be a
 * coloured band spanning the window above the list -- a lot of vertical space
 * and a hard visual break, for one short line that is usually "nothing to do".
 * Inline it stays just as visible (it is on the row the eye already lands on)
 * without splitting the screen in two.
 *
 * `UnsyncedExitDialog` (shown on back-press from home) is the backstop for
 * exits Android gives no reliable hook for (home button, recents swipe); this
 * is what is visible the rest of the time. Renders nothing when sync is not
 * enabled -- a local-only user never sees any of it, matching "an account is
 * optional and every feature except account-specific ones works signed out."
 *
 * A signed-out-but-still-enabled state still shows something (a deliberate
 * softening of "only when enabled": GoTrue revokes the Android session on any
 * web-side password change, and hiding this then would make a user's real
 * unsynced work invisible with no explanation).
 */
@Composable
fun SyncStatusInline(
    status: SyncStatus,
    onSyncClick: () -> Unit,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!status.enabled) return

    val isError = status.lastError != null
    // "last synced never" is only ever noise, and appended to "All synced" it
    // reads as a flat contradiction ("All synced · never"). A sync that has
    // never run has no time worth reporting, so these branches drop the tail
    // entirely rather than printing a placeholder where a timestamp goes.
    //
    // Read into a local first: `lastSyncAtMs` is a public property of another
    // module, so Kotlin won't smart-cast it to non-null after the check.
    val lastSyncAtMs = status.lastSyncAtMs
    val neverSynced = lastSyncAtMs == null || lastSyncAtMs <= 0L
    val message = when {
        isError -> status.lastError!!
        !status.signedIn -> "${status.unsyncedTotal} ${changeWord(status.unsyncedTotal)} waiting"
        status.running -> "Syncing…"
        status.unsyncedTotal > 0 && neverSynced ->
            "${status.unsyncedTotal} ${songWord(status.unsyncedTotal)} not synced"
        status.unsyncedTotal > 0 ->
            "${status.unsyncedTotal} ${songWord(status.unsyncedTotal)} not synced · ${lastSyncLabel(lastSyncAtMs)}"
        // Nothing pending and nothing ever pushed -- true of every freshly
        // signed-in account, which is exactly when "All synced" sounds most
        // wrong. Says the actual state instead: there is nothing to send.
        neverSynced -> "Nothing to sync yet"
        else -> "Synced ${lastSyncLabel(lastSyncAtMs)}"
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight(fill = false) so the text takes only what it needs and yields
        // the rest to the action beside it, but still has a ceiling to
        // ellipsise against -- an error message is arbitrary length and would
        // otherwise push the Sync button off the row entirely.
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        when {
            status.running -> CircularProgressIndicator(
                modifier = Modifier.padding(start = 8.dp).height(16.dp).width(16.dp),
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
