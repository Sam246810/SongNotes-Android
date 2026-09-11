package com.songnotes.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.songnotes.core.audio.MultitrackProjectStorage
import com.songnotes.core.data.LocalDataResetStore
import com.songnotes.core.data.SongListItem
import com.songnotes.core.data.SongRepository
import com.songnotes.core.data.SyncPreferences
import com.songnotes.core.data.SyncStatus
import com.songnotes.core.domain.Song
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 5.5's entry screen: every locally-saved [Song], newest-edited
 * first. "New song" creates an empty one and hands off to the editor
 * immediately — no separate "create" dialog, since an empty title/lyrics
 * is a perfectly valid starting state the editor already handles (the
 * user names it by just typing a title, same as any notes app).
 *
 * Phase 13: local-first, opt-in manual sync -- home screen for a local-only
 * user works identically to before (no account, no network, ever). When
 * sync is enabled, [SyncStatusInline] shows unsynced state and delete goes through
 * [DeleteSongDialog] with copy that depends on whether a song has ever
 * reached the account ([SongListItem.isOnAccount]).
 */
@Composable
fun SongListScreen(
    status: SyncStatus,
    onSyncClick: () -> Unit,
    onSignInClick: () -> Unit,
    onOpenSong: (songId: String) -> Unit,
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
    var searchQuery by remember { mutableStateOf("") }

    // Title substring, case-insensitive -- matching the web app's Dashboard
    // filter exactly. Deliberately not a full-text search over lyrics: the web
    // app doesn't do that either, and matching its behaviour keeps "I searched
    // for this on my laptop and it found it" true on both.
    val visibleItems = remember(items, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) items else items.filter { it.song.title.contains(query, ignoreCase = true) }
    }

    LaunchedEffect(Unit) {
        migrateFromSongStorageIfNeeded(context, repo, syncPrefs)
        sweepMigratedLegacyJson(context, repo)
        sweepOrphanedScratchpads(context, repo)
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
                val songId = target.song.id
                scope.launch {
                    repo.deleteRespectingSync(target.song)
                    // Scratchpad recordings live outside Room entirely (raw
                    // audio + a manifest on disk, keyed by songId -- see
                    // MultitrackProjectStorage), so deleting the song row
                    // alone leaves that folder behind forever. Deliberately
                    // AFTER the repo delete, not before or in parallel: if
                    // the process dies between the two steps, worst case is
                    // today's existing gap (an orphaned folder, harmless
                    // besides wasted space) -- clearing the audio FIRST
                    // risks the opposite, worse failure: a song that still
                    // shows up in the list with its recordings silently gone.
                    withContext(Dispatchers.Default) { MultitrackProjectStorage(context, songId).clear() }
                }
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
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Sync state rides on this row rather than in a full-width band
            // above the list. The band cost a large slice of vertical space
            // and cut the screen in half for one short line that usually says
            // there is nothing to do; here it sits on the row the eye already
            // lands on. SyncStatusInline right-aligns its own contents and
            // renders nothing at all when sync is off, in which case this
            // weighted slot just acts as the spacer that keeps Piano right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Songs", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(12.dp))
                SyncStatusInline(
                    status = status,
                    onSyncClick = onSyncClick,
                    onSignInClick = onSignInClick,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = ::createSong, modifier = Modifier.fillMaxWidth()) {
                Text("New song")
            }
            Spacer(Modifier.height(16.dp))

            // Only worth the vertical space once there's enough to search
            // through -- same reasoning as the web app, which hides its own
            // search box until at least one song exists.
            if (items.size > 1) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search songs…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
            }

            if (items.isEmpty()) {
                Text(
                    "No songs yet — tap \"New song\" to write your first one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (visibleItems.isEmpty()) {
                Text(
                    "No songs match \"${searchQuery.trim()}\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn {
                    items(visibleItems, key = { it.song.id }) { item ->
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

/**
 * Scratchpad recordings live outside Room entirely (raw audio + a manifest
 * on disk, keyed by songId -- see [MultitrackProjectStorage]). Deleting a
 * song through THIS screen's own delete flow already clears its folder
 * directly (see `deleteTarget`'s `onConfirm` above), but a song deleted on
 * a *different* device and pulled down here as a tombstone during sync
 * bypasses that entirely -- `SyncEngine` writes straight to Room and never
 * goes through this screen. Rather than reach into `:core:data` from here
 * (or worse, give it a dependency on `:core:audio` just to delete some
 * files), this runs as a lazy, self-healing sweep on every visit to the
 * list instead: cheap (one folder listing plus one already-tombstone-aware
 * [SongRepository.getById] lookup per entry, and the folder count here is
 * always small), and correct regardless of *how* a song's row ended up
 * gone or tombstoned rather than needing every possible deletion path
 * individually hooked.
 */
private suspend fun sweepOrphanedScratchpads(context: android.content.Context, repo: SongRepository) {
    val scratchpadRoot = File(context.filesDir, "scratchpad")
    val songDirs = scratchpadRoot.listFiles { file -> file.isDirectory } ?: return
    for (dir in songDirs) {
        if (repo.getById(dir.name) == null) dir.deleteRecursively()
    }
}
