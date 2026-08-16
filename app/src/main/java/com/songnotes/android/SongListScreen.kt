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

    LaunchedEffect(Unit) {
        migrateFromSongStorageIfNeeded(context, repo, syncPrefs)
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
 * the list. Old JSON files are still deliberately left in place rather than
 * deleted: they're inert once migrated (nothing reads them again), and
 * leaving them is a strictly safer default than a delete bug quietly
 * destroying the only copy of a song.
 */
private suspend fun migrateFromSongStorageIfNeeded(context: android.content.Context, repo: SongRepository, syncPrefs: SyncPreferences) {
    if (syncPrefs.legacyJsonImportDone) return
    val legacySongs = SongStorage(context).list()
    for (song in legacySongs) repo.upsert(song)
    syncPrefs.legacyJsonImportDone = true
}
