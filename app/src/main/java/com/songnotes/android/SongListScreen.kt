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
import com.songnotes.core.data.SongRepository
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
 * Phase 6: backed by [SongRepository] (Room + SQLCipher) instead of the
 * original [SongStorage] (plain JSON files) — [songs] is fed by
 * [SongRepository.observeAll]'s [kotlinx.coroutines.flow.Flow], so create/
 * delete no longer need an explicit `refresh()`; Room's own change
 * notification re-emits the list automatically.
 */
@Composable
fun SongListScreen(
    onOpenSong: (songId: String) -> Unit,
    onOpenScratchpad: () -> Unit,
    onOpenPiano: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember { SongRepository(context) }
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }

    LaunchedEffect(Unit) {
        migrateFromSongStorageIfNeeded(context, repo)
        repo.observeAll().collect { songs = it }
    }

    fun createSong() {
        scope.launch {
            val now = System.currentTimeMillis()
            val song = Song(id = UUID.randomUUID().toString(), title = "", createdAt = now, updatedAt = now)
            repo.upsert(song)
            onOpenSong(song.id)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
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

        if (songs.isEmpty()) {
            Text(
                "No songs yet — tap \"New song\" to write your first one.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn {
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        onOpen = { onOpenSong(song.id) },
                        onDelete = { scope.launch { repo.delete(song) } },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, onOpen: () -> Unit, onDelete: () -> Unit) {
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
 * One-time (per app-start) import of any songs still sitting in the pre-
 * Phase-6 [SongStorage] (plain JSON files under `filesDir/songs/`) into the
 * new [SongRepository] (Room + SQLCipher). Idempotent via [SongRepository.upsert]
 * matching on song id, so running this on every launch is harmless — no
 * separate "have we migrated yet" flag needed at this scale. Old JSON files
 * are deliberately left in place rather than deleted: they're inert once
 * migrated (nothing reads them again), and leaving them is a strictly safer
 * default than a delete bug quietly destroying the only copy of a song.
 */
private suspend fun migrateFromSongStorageIfNeeded(context: android.content.Context, repo: SongRepository) {
    val legacySongs = SongStorage(context).list()
    for (song in legacySongs) repo.upsert(song)
}
