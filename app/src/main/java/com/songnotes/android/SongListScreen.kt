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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.songnotes.core.domain.Song

/**
 * Phase 5.5's entry screen: every locally-saved [Song], newest-edited
 * first. "New song" creates an empty one and hands off to the editor
 * immediately — no separate "create" dialog, since an empty title/lyrics
 * is a perfectly valid starting state the editor already handles (the
 * user names it by just typing a title, same as any notes app).
 */
@Composable
fun SongListScreen(onOpenSong: (songId: String) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { SongStorage(context) }
    var songs by remember { mutableStateOf(storage.list()) }

    fun refresh() {
        songs = storage.list()
    }

    fun createSong() {
        val now = System.currentTimeMillis()
        val song = Song(id = storage.newSongId(), title = "", createdAt = now, updatedAt = now)
        storage.save(song)
        onOpenSong(song.id)
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Songs", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onDone) { Text("Done") }
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
                        onDelete = {
                            storage.delete(song.id)
                            refresh()
                        },
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
