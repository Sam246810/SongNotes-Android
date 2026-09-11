package com.songnotes.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.songnotes.core.data.LocalDataResetStore
import com.songnotes.core.data.SongListItem
import com.songnotes.core.data.SongRepository
import com.songnotes.core.data.SyncPreferences
import com.songnotes.core.data.SyncStatus
import com.songnotes.core.domain.Song
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Phase 5.5's entry screen: every locally-saved [Song], newest-edited
 * first. "New song" creates an empty one and hands off to the editor
 * immediately — no separate "create" dialog, since an empty title/lyrics
 * is a perfectly valid starting state the editor already handles (the
 * user names it by just typing a title, same as any notes app).
 *
 * Phase 13: local-first, opt-in manual sync -- home screen for a local-only
 * user works identically to before (no account, no network, ever). When
 * sync is enabled, [SyncBanner] shows unsynced state and delete goes through
 * [DeleteSongDialog] with copy that depends on whether a song has ever
 * reached the account ([SongListItem.isOnAccount]).
 */
@Composable
fun SongListScreen(
    status: SyncStatus,
    onSyncClick: () -> Unit,
    onSignInClick: () -> Unit,
    onOpenSong: (songId: String) -> Unit,
    onOpenScratchpad: () -> Unit,
    onOpenPiano: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember { SongRepository(context) }
    val syncPrefs = remember { SyncPreferences(context) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SongListItem>>(emptyList()) }
    var deleteTarget by remember { mutableStateOf<SongListItem?>(null) }
    // Set when local storage had to be discarded after the Android Keystore key
    // protecting it was lost -- see LocalDataResetStore. Read once on entry
    // rather than observed: the reset happens during database construction, so
    // it can only ever become true before this screen composes, never while
    // it's on screen.
    val resetStore = remember { LocalDataResetStore(context) }
    var showLocalDataResetNotice by remember { mutableStateOf(resetStore.wasReset) }

    LaunchedEffect(Unit) {
        migrateFromSongStorageIfNeeded(context, repo, syncPrefs)
        sweepMigratedLegacyJson(context, repo)
        repo.observeAllWithSyncState().collect { items = it }
    }

    fun createSong() {
        scope.launch {
            val now = System.currentTimeMillis()
            val song = Song(id = UUID.randomUUID().toString(), title = "", createdAt = now, updatedAt = now)
            repo.upsert(song)
            onOpenSong(song.id)
        }
    }

    deleteTarget?.let { target ->
        DeleteSongDialog(
            title = target.song.title,
            isOnAccount = target.isOnAccount,
            onConfirm = {
                scope.launch { repo.deleteRespectingSync(target.song) }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (showLocalDataResetNotice) {
        LocalDataResetDialog(
            onDismiss = {
                resetStore.acknowledge()
                showLocalDataResetNotice = false
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        SyncBanner(status = status, onSyncClick = onSyncClick, onSignInClick = onSignInClick)
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Songs", style = MaterialTheme.typography.headlineSmall)
                Row {
                    IconButton(onClick = onOpenScratchpad) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = "Scratchpad")
                    }
                    IconButton(onClick = onOpenPiano) {
                        Icon(Icons.Filled.Piano, contentDescription = "Piano")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = ::createSong, modifier = Modifier.fillMaxWidth()) {
                Text("New song")
            }
            Spacer(Modifier.height(16.dp))

            if (items.isEmpty()) {
                Text(
                    "No songs yet — tap \"New song\" to write your first one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn {
                    items(items, key = { it.song.id }) { item ->
                        SongRow(
                            item = item,
                            onOpen = { onOpenSong(item.song.id) },
                            onDelete = { deleteTarget = item },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRow(item: SongListItem, onOpen: () -> Unit, onDelete: () -> Unit) {
    val song = item.song
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                )
                val preview = song.lines.firstOrNull { it.lyrics.isNotBlank() }?.lyrics
                if (preview != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            TextButton(onClick = onDelete) {
                Text("Delete")
            }
        }
    }
}

/**
 * One-time (per device, tracked via [SyncPreferences.legacyJsonImportDone] --
 * Phase 13) import of any songs still sitting in the pre-Phase-6 [SongStorage]
 * (plain JSON files under `filesDir/songs/`) into the new [SongRepository]
 * (Room + SQLCipher). Before Phase 13 this ran on EVERY entry to the list
 * screen with no run-once guard -- harmless in isolation, but combined with
 * the pre-Phase-13 `SongRepository.upsert` defects (unconditional
 * `deletedAt = null`, dropped `remoteRev`) it would resurrect a song deleted
 * since the last import and re-flag it `pendingSync` on every single visit to
 * the list.
 *
 * Each plaintext original is deleted once it has migrated, by
 * [sweepMigratedLegacyJson] below. This used to leave them in place forever on
 * the reasoning that they're inert once migrated and that keeping them beat "a
 * delete bug quietly destroying the only copy of a song" -- a fair concern, and
 * the reason the delete is gated on reading the song back out of the encrypted
 * database rather than fired optimistically. But inert is not the same as
 * harmless: every `filesDir/songs/<id>.json` is the user's lyrics in cleartext,
 * sitting
 * beside a SQLCipher database whose entire purpose is that they aren't. Any
 * device that upgraded through Phase 6 has been carrying a full plaintext copy
 * of every pre-Phase-6 song ever since, which quietly defeats
 * encryption-at-rest for exactly the users who have been here longest.
 */
private suspend fun migrateFromSongStorageIfNeeded(context: android.content.Context, repo: SongRepository, syncPrefs: SyncPreferences) {
    if (syncPrefs.legacyJsonImportDone) return
    val legacySongs = SongStorage(context).list()
    for (song in legacySongs) repo.upsert(song)
    syncPrefs.legacyJsonImportDone = true
}

/**
 * Deletes each legacy plaintext song file whose content is confirmed present in
 * the encrypted database -- see [migrateFromSongStorageIfNeeded] for why these
 * shouldn't linger.
 *
 * Runs unconditionally rather than behind `legacyJsonImportDone`, because the
 * devices that most need it are precisely the ones where that flag is *already*
 * true: they migrated before this cleanup existed and have been holding
 * plaintext ever since. On the overwhelmingly common device -- no legacy files
 * at all -- this is a single `listFiles` on a non-existent directory.
 *
 * Two deliberate conservatisms, both aimed at never being the bug the old
 * comment worried about:
 *
 * - The delete is gated on [SongRepository.existsIncludingDeleted], so a file is
 *   only removed once its song is provably in Room. Tombstones count: that means
 *   migrated-then-deleted, where the plaintext copy is both redundant and the
 *   one most worth removing.
 * - A file that fails to parse never reaches here at all -- [SongStorage.list]
 *   drops unreadable files via `runCatching`, so anything corrupt is left
 *   untouched rather than deleted on the strength of a failed read.
 */
private suspend fun sweepMigratedLegacyJson(context: android.content.Context, repo: SongRepository) {
    val storage = SongStorage(context)
    for (song in storage.list()) {
        if (repo.existsIncludingDeleted(song.id)) storage.delete(song.id)
    }
}
