package com.songnotes.android

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import com.songnotes.core.domain.SongMeta
import com.songnotes.core.domain.anchorsToChordsLine
import com.songnotes.core.domain.chordsLineToAnchors
import com.songnotes.core.domain.parseLyricsText
import com.songnotes.core.domain.transposeChordAnchors
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 5.5's minimum shippable editor. Deliberately simple compared to
 * Phase 8's real editor UI ("budget real time for typography" — that's
 * explicitly a later phase's job): one lyrics [OutlinedTextField] per
 * line, a read-only chords-above-lyrics preview rendered via
 * [anchorsToChordsLine], and chord chips you tap to remove. Adding a
 * chord means typing it into a small per-line field and tapping "Add" —
 * it lands at wherever the lyrics field's cursor currently sits, per the
 * anchor model `docs/PLAN.md` locks in.
 *
 * Autosaves on a short debounce after any edit rather than requiring an
 * explicit save action — the product thesis this whole phase exists to
 * prove out ("you can write a real song on it and prefer it to a notes
 * app") fails immediately if the user has to remember to hit Save like
 * it's 1995.
 */
@Composable
fun SongEditorScreen(songId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { SongStorage(context) }
    var song by remember { mutableStateOf(storage.load(songId) ?: emptySong(songId)) }
    var showImport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persist(updated: Song) {
        song = updated.copy(updatedAt = System.currentTimeMillis())
        scope.launch {
            delay(400) // debounce — avoid a disk write on every keystroke
            storage.save(song)
        }
    }

    if (showImport) {
        ImportStep(
            onCancel = { showImport = false },
            onImport = { text ->
                val parsed = parseLyricsText(text)
                val lines = parsed.lines.map { l ->
                    SongLine(id = UUID.randomUUID().toString(), lyrics = l.lyrics, chords = chordsLineToAnchors(l.chords))
                }
                val meta = SongMeta(
                    bpm = parsed.meta["bpm"]?.toIntOrNull() ?: song.meta.bpm,
                    key = parsed.meta["key"] ?: song.meta.key,
                    tuning = parsed.meta["tuning"] ?: song.meta.tuning,
                    capo = parsed.meta["capo"]?.toIntOrNull() ?: song.meta.capo,
                )
                persist(song.copy(title = parsed.title ?: song.title, meta = meta, lines = lines))
                showImport = false
            },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = song.title,
                onValueChange = { persist(song.copy(title = it)) },
                label = { Text("Title") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = {
                storage.save(song) // flush immediately — don't lose the last debounced edit
                onDone()
            }) { Text("Done") }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            OutlinedButton(onClick = { showImport = true }) { Text("Import text") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = {
                persist(song.copy(lines = song.lines.map { it.copy(chords = transposeChordAnchors(it.chords, -1)) }))
            }) { Text("Transpose -1") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = {
                persist(song.copy(lines = song.lines.map { it.copy(chords = transposeChordAnchors(it.chords, 1)) }))
            }) { Text("Transpose +1") }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(song.lines, key = { it.id }) { line ->
                LineEditor(
                    line = line,
                    onChange = { updated ->
                        persist(song.copy(lines = song.lines.map { if (it.id == line.id) updated else it }))
                    },
                    onDelete = {
                        persist(song.copy(lines = song.lines.filterNot { it.id == line.id }))
                    },
                )
                HorizontalDivider()
            }
            item {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        val newLine = SongLine(id = UUID.randomUUID().toString(), lyrics = "")
                        persist(song.copy(lines = song.lines + newLine))
                    },
                ) { Text("+ Add line") }
            }
        }
    }
}

private fun emptySong(id: String) = Song(id = id, title = "", createdAt = 0L, updatedAt = 0L)

@Composable
private fun LineEditor(line: SongLine, onChange: (SongLine) -> Unit, onDelete: () -> Unit) {
    var fieldValue by remember(line.id) { mutableStateOf(TextFieldValue(line.lyrics)) }
    var chordInput by remember(line.id) { mutableStateOf("") }

    // Keep the field's text in sync if the line changed from elsewhere
    // (transpose, import) without stomping the user's own in-progress
    // cursor position on every recomposition.
    if (fieldValue.text != line.lyrics) {
        fieldValue = TextFieldValue(line.lyrics, selection = TextRange(line.lyrics.length))
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (line.chords.isNotEmpty()) {
            Text(
                anchorsToChordsLine(line.lyrics.length, line.chords),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                if (it.text != line.lyrics) onChange(line.copy(lyrics = it.text))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Lyrics") },
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (chord in line.chords) {
                Card(
                    modifier = Modifier.padding(end = 6.dp),
                    onClick = { onChange(line.copy(chords = line.chords - chord)) },
                ) {
                    Text(chord.c, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            OutlinedTextField(
                value = chordInput,
                onValueChange = { chordInput = it },
                modifier = Modifier.width(90.dp),
                singleLine = true,
                placeholder = { Text("Chord") },
            )
            TextButton(
                onClick = {
                    if (chordInput.isNotBlank()) {
                        val at = fieldValue.selection.start.coerceIn(0, line.lyrics.length)
                        val updatedChords = (line.chords + ChordAnchor(at, chordInput)).sortedBy { it.i }
                        onChange(line.copy(chords = updatedChords))
                        chordInput = ""
                    }
                },
            ) { Text("Add") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("Delete line") }
        }
    }
}

@Composable
private fun ImportStep(onCancel: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Import lyrics + chords", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Paste a plain-text chord sheet — chords on their own line above the lyrics they " +
                "belong to, or bracketed like [G]. Replaces this song's lines.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Spacer(Modifier.height(12.dp))
        Row {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { onImport(text) }) { Text("Import") }
        }
    }
}
