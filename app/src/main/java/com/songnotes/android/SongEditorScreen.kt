package com.songnotes.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import com.songnotes.core.domain.SongMeta
import com.songnotes.core.domain.chordsLineToAnchors
import com.songnotes.core.domain.parseLyricsText
import com.songnotes.core.domain.transposeChordAnchors
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 5.5's minimum shippable editor. Deliberately simple compared to
 * Phase 8's real editor UI ("budget real time for typography" — that's
 * explicitly a later phase's job).
 *
 * Chord placement is tap-to-place, directly on the rendered lyrics: tap
 * the exact character you want a chord above, a small editor appears
 * right there pre-filled if a chord already exists at that spot, type
 * the chord name and Save. This replaced an earlier version that placed
 * chords via an invisible text-cursor position in a separate field —
 * genuinely unusable (there was no way to see or control where a chord
 * would land before committing it). Positioning uses
 * [TextLayoutResult.getHorizontalPosition] to place each chord chip
 * exactly above its target character, and [TextLayoutResult.getOffsetForPosition]
 * to translate a tap back into a character index — the same anchor model
 * (`i` = character index into `lyrics`) `docs/WIRE-FORMAT-v2.md` §4
 * mandates, just with an actually-visual way to control it.
 *
 * Autosaves on a short debounce after any edit rather than requiring an
 * explicit save action.
 */
@Composable
fun SongEditorScreen(songId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { SongStorage(context) }
    var song by remember { mutableStateOf(storage.load(songId) ?: emptySong(songId)) }
    var showImport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persist(updated: Song) {
        val toSave = updated.copy(updatedAt = System.currentTimeMillis())
        song = toSave
        scope.launch {
            delay(400) // debounce — avoid a disk write on every keystroke
            storage.save(toSave)
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
    var textLayout by remember(line.id) { mutableStateOf<TextLayoutResult?>(null) }
    var selectedIndex by remember(line.id) { mutableStateOf<Int?>(null) }
    var chordDraft by remember(line.id) { mutableStateOf("") }

    // The lyrics text field's own state, synced from `line.lyrics` via a
    // proper side effect (LaunchedEffect) rather than a raw comparison
    // during composition — writing to a MutableState mid-composition based
    // on a value comparison is a real Compose anti-pattern that can loop
    // or crash under concurrent recomposition (e.g. every LineEditor in
    // this list recomposing together when a line is added/removed).
    var fieldValue by remember(line.id) { mutableStateOf(TextFieldValue(line.lyrics)) }
    LaunchedEffect(line.lyrics) {
        if (fieldValue.text != line.lyrics) {
            fieldValue = TextFieldValue(line.lyrics, selection = TextRange(line.lyrics.length.coerceAtMost(fieldValue.selection.start)))
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            "Tap the lyrics below to place a chord",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp)) // reserves room for chord chips rendered above the text

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(line.id, line.lyrics) {
                    detectTapGestures { tapOffset ->
                        val layout = textLayout ?: return@detectTapGestures
                        val tappedIndex = layout.getOffsetForPosition(tapOffset)
                        val nearestExisting = line.chords.minByOrNull { abs(it.i - tappedIndex) }
                        selectedIndex = if (nearestExisting != null && abs(nearestExisting.i - tappedIndex) <= 2) {
                            nearestExisting.i
                        } else {
                            tappedIndex
                        }
                        chordDraft = line.chords.firstOrNull { it.i == selectedIndex }?.c ?: ""
                    }
                },
        ) {
            Text(
                text = line.lyrics.ifEmpty { " " },
                onTextLayout = { textLayout = it },
                style = MaterialTheme.typography.bodyLarge,
            )
            textLayout?.let { layout ->
                for (chord in line.chords) {
                    val x = layout.getHorizontalPosition(chord.i.coerceIn(0, line.lyrics.length), true)
                    ChordChip(
                        text = chord.c,
                        highlighted = selectedIndex == chord.i,
                        modifier = Modifier
                            .offset { IntOffset(x.roundToInt(), -56) }
                            .clickable {
                                selectedIndex = chord.i
                                chordDraft = chord.c
                            },
                    )
                }
                val selected = selectedIndex
                if (selected != null && line.chords.none { it.i == selected }) {
                    val x = layout.getHorizontalPosition(selected.coerceIn(0, line.lyrics.length), true)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(x.roundToInt() - 1, -8) }
                            .width(2.dp)
                            .height(20.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }

        selectedIndex?.let { idx ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedTextField(
                    value = chordDraft,
                    onValueChange = { chordDraft = it },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    placeholder = { Text("Chord") },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (chordDraft.isNotBlank()) {
                            val without = line.chords.filterNot { it.i == idx }
                            onChange(line.copy(chords = (without + ChordAnchor(idx, chordDraft)).sortedBy { it.i }))
                        }
                        selectedIndex = null
                    },
                ) { Text("Save") }
                if (line.chords.any { it.i == idx }) {
                    TextButton(
                        onClick = {
                            onChange(line.copy(chords = line.chords.filterNot { it.i == idx }))
                            selectedIndex = null
                        },
                    ) { Text("Remove") }
                }
                TextButton(onClick = { selectedIndex = null }) { Text("Cancel") }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                if (it.text != line.lyrics) onChange(line.copy(lyrics = it.text))
            },
            label = { Text("Lyrics") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onDelete) { Text("Delete line") }
    }
}

@Composable
private fun ChordChip(text: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
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
