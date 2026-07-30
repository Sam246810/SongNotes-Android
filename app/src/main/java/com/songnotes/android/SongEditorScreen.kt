package com.songnotes.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.input.key.KeyEventType
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import com.songnotes.core.domain.SongMeta
import com.songnotes.core.domain.alignChordsWithLyrics
import com.songnotes.core.domain.anchorsToChordsLine
import com.songnotes.core.domain.chordsLineToAnchors
import com.songnotes.core.domain.parseLyricsText
import com.songnotes.core.domain.tokenizeChordLine
import com.songnotes.core.domain.transposeChordsLine
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 5.5's editor, second pass — rebuilt to match the desktop web app's
 * actual editing model (`src/components/SongLine/SongLine.jsx`) instead of
 * a from-scratch Android-native design, after the first pass's tap-to-place
 * anchor UI proved genuinely unusable. The desktop app's model is a plain
 * space-padded chords string aligned above a lyrics string per line —
 * literally a notes app, with a colored inline token view when the chord
 * row isn't focused. This is a faithful behavioral port of that: same
 * paper-notebook visual language, same Enter-creates-a-line /
 * Backspace-at-start-merges-with-previous flow, same auto-split when a
 * line runs long.
 *
 * The wire-format anchor model (`docs/WIRE-FORMAT-v2.md` §4,
 * [ChordAnchor]) is kept, but demoted to a pure storage/serialization
 * detail — [EditorLine] works entirely in the padded-string domain the
 * user actually edits, converting to/from anchors only at load ([songToEditorLines])
 * and save ([currentSong]/[chordsLineToAnchors]) via functions already
 * built for exactly this purpose.
 */

private val ParchmentBg = Color(0xFFFDFBF7)
private val WorkspaceBg = Color(0xFFEAE1CE)
private val PaperBorder = Color(0xFFCEBFAB)
private val MarginLine = Color(0xFFDC2626).copy(alpha = 0.22f)
private val ChordColor = Color(0xFFB45309)
private val LyricColor = Color(0xFF2A221B)
private val TextMuted = Color(0xFF8A7663)
private val PaperLine = Color(0xFFB45309).copy(alpha = 0.14f)

private const val kMaxLineChars = 38

private data class EditorLine(val id: String, val chords: String, val lyrics: String)
private enum class Track { Chords, Lyrics }
private data class PendingFocus(val lineId: String, val track: Track, val caretIndex: Int? = null)

private fun songToEditorLines(song: Song): List<EditorLine> {
    if (song.lines.isEmpty()) return listOf(EditorLine(UUID.randomUUID().toString(), "", ""))
    return song.lines.map { EditorLine(it.id, anchorsToChordsLine(it.lyrics.length, it.chords), it.lyrics) }
}

/** JS `String.slice`-style forgiving substring — never throws on an out-of-range index. */
private fun String.sliceSafe(start: Int, end: Int = length): String {
    val s = start.coerceIn(0, length)
    val e = end.coerceIn(s, length)
    return substring(s, e)
}

private fun splitLineAt(line: EditorLine, splitIndex: Int): Pair<EditorLine, EditorLine> {
    val lyrics1 = line.lyrics.sliceSafe(0, splitIndex)
    val lyrics2 = line.lyrics.sliceSafe(splitIndex)
    val chords1 = line.chords.sliceSafe(0, splitIndex)
    val chords2 = line.chords.sliceSafe(splitIndex)
    val first = EditorLine(line.id, alignChordsWithLyrics(chords1, lyrics1), lyrics1)
    val second = EditorLine(UUID.randomUUID().toString(), chords2, lyrics2)
    return first to second
}

private fun mergeWithPrevious(prev: EditorLine, curr: EditorLine): EditorLine {
    val alignedPrevChords = alignChordsWithLyrics(prev.chords, prev.lyrics)
    val mergedChords = alignedPrevChords + curr.chords
    val mergedLyrics = prev.lyrics + curr.lyrics
    return EditorLine(prev.id, alignChordsWithLyrics(mergedChords, mergedLyrics), mergedLyrics)
}

@Composable
fun SongEditorScreen(songId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { SongStorage(context) }
    val loadedSong = remember { storage.load(songId) ?: emptySong(songId) }
    var title by remember { mutableStateOf(loadedSong.title) }
    var meta by remember { mutableStateOf(loadedSong.meta) }
    var lines by remember { mutableStateOf(songToEditorLines(loadedSong)) }
    var pendingFocus by remember { mutableStateOf<PendingFocus?>(null) }
    var showImport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun currentSong(): Song = Song(
        id = songId,
        title = title,
        meta = meta,
        lines = lines.map { SongLine(id = it.id, lyrics = it.lyrics, chords = chordsLineToAnchors(it.chords)) },
        createdAt = loadedSong.createdAt,
        updatedAt = System.currentTimeMillis(),
    )

    fun persist() {
        scope.launch {
            delay(400) // debounce — avoid a disk write on every keystroke
            storage.save(currentSong())
        }
    }

    fun updateLine(id: String, transform: (EditorLine) -> EditorLine) {
        lines = lines.map { if (it.id == id) transform(it) else it }
        persist()
    }

    fun handleEnterFromLyrics(afterId: String) {
        val idx = lines.indexOfFirst { it.id == afterId }
        if (idx == -1) return
        val newLine = EditorLine(UUID.randomUUID().toString(), "", "")
        lines = lines.toMutableList().apply { add(idx + 1, newLine) }
        pendingFocus = PendingFocus(newLine.id, Track.Lyrics)
        persist()
    }

    fun handleMergeWithPrevious(lineId: String) {
        val idx = lines.indexOfFirst { it.id == lineId }
        if (idx <= 0) return // can't merge the first line
        val prev = lines[idx - 1]
        val curr = lines[idx]
        val caret = prev.lyrics.length
        val merged = mergeWithPrevious(prev, curr)
        lines = lines.toMutableList().apply {
            removeAt(idx)
            set(idx - 1, merged)
        }
        pendingFocus = PendingFocus(merged.id, Track.Lyrics, caret)
        persist()
    }

    fun handleDeleteLine(lineId: String) {
        if (lines.size <= 1) return // keep at least one line
        val idx = lines.indexOfFirst { it.id == lineId }
        lines = lines.filterNot { it.id == lineId }
        val target = lines.getOrNull((idx - 1).coerceAtLeast(0))
        if (target != null) pendingFocus = PendingFocus(target.id, Track.Lyrics)
        persist()
    }

    fun handleAutoSplit(line: EditorLine, caretIndex: Int) {
        val text = line.lyrics
        if (text.length < kMaxLineChars) return
        var splitIdx = text.lastIndexOf(' ', kMaxLineChars)
        if (splitIdx == -1 || splitIdx < kMaxLineChars / 2) splitIdx = kMaxLineChars - 1
        if (splitIdx <= 0 || splitIdx >= text.length) return
        val (first, second) = splitLineAt(line, splitIdx)
        val idx = lines.indexOfFirst { it.id == line.id }
        if (idx == -1) return
        lines = lines.toMutableList().apply {
            set(idx, first)
            add(idx + 1, second)
        }
        pendingFocus = PendingFocus(second.id, Track.Lyrics, (caretIndex - splitIdx).coerceAtLeast(0))
        persist()
    }

    if (showImport) {
        ImportStep(
            onCancel = { showImport = false },
            onImport = { text ->
                val parsed = parseLyricsText(text)
                val importedLines = parsed.lines.map { l -> EditorLine(UUID.randomUUID().toString(), l.chords, l.lyrics) }
                lines = importedLines.ifEmpty { listOf(EditorLine(UUID.randomUUID().toString(), "", "")) }
                title = parsed.title ?: title
                meta = SongMeta(
                    bpm = parsed.meta["bpm"]?.toIntOrNull() ?: meta.bpm,
                    key = parsed.meta["key"] ?: meta.key,
                    tuning = parsed.meta["tuning"] ?: meta.tuning,
                    capo = parsed.meta["capo"]?.toIntOrNull() ?: meta.capo,
                )
                persist()
                showImport = false
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WorkspaceBg)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = title,
                onValueChange = { title = it; persist() },
                textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LyricColor),
                singleLine = true,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                decorationBox = { inner ->
                    if (title.isEmpty()) Text("Untitled", style = MaterialTheme.typography.headlineSmall, color = TextMuted)
                    inner()
                },
            )
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = {
                storage.save(currentSong()) // flush immediately — don't lose the last debounced edit
                onDone()
            }) { Text("Done") }
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { showImport = true }) { Text("Import text", color = ChordColor) }
            TextButton(onClick = {
                lines = lines.map { it.copy(chords = transposeChordsLine(it.chords, -1) ?: it.chords) }
                persist()
            }) { Text("Transpose -1", color = ChordColor) }
            TextButton(onClick = {
                lines = lines.map { it.copy(chords = transposeChordsLine(it.chords, 1) ?: it.chords) }
                persist()
            }) { Text("Transpose +1", color = ChordColor) }
        }
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(4.dp, RoundedCornerShape(8.dp))
                .background(ParchmentBg, RoundedCornerShape(8.dp))
                .padding(1.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .padding(start = 28.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            ) {
                // The notebook's red margin rule — purely decorative, matches the desktop paper theme.
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MarginLine),
                )
                LazyColumn(modifier = Modifier.fillMaxSize().padding(start = 12.dp)) {
                    items(lines, key = { it.id }) { line ->
                        LineRow(
                            line = line,
                            pendingFocus = pendingFocus,
                            onConsumedPendingFocus = { pendingFocus = null },
                            onChordsChange = { updated -> updateLine(line.id) { it.copy(chords = updated) } },
                            onLyricsChange = { updated, caret ->
                                updateLine(line.id) { it.copy(lyrics = updated) }
                                handleAutoSplit(line.copy(lyrics = updated), caret)
                            },
                            onEnter = { handleEnterFromLyrics(line.id) },
                            onBackspaceMerge = { handleMergeWithPrevious(line.id) },
                            onBackspaceDeleteEmpty = { handleDeleteLine(line.id) },
                            onDelete = { handleDeleteLine(line.id) },
                        )
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                val newLine = EditorLine(UUID.randomUUID().toString(), "", "")
                                lines = lines + newLine
                                pendingFocus = PendingFocus(newLine.id, Track.Lyrics)
                                persist()
                            },
                        ) { Text("+ Add line", color = TextMuted) }
                    }
                }
            }
        }
    }
}

private fun emptySong(id: String) = Song(id = id, title = "", createdAt = 0L, updatedAt = 0L)

private val chordTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.4.sp,
)
private val lyricTextStyle = TextStyle(
    fontSize = 16.sp,
    color = LyricColor,
)

@Composable
private fun LineRow(
    line: EditorLine,
    pendingFocus: PendingFocus?,
    onConsumedPendingFocus: () -> Unit,
    onChordsChange: (String) -> Unit,
    onLyricsChange: (String, caretIndex: Int) -> Unit,
    onEnter: () -> Unit,
    onBackspaceMerge: () -> Unit,
    onBackspaceDeleteEmpty: () -> Unit,
    onDelete: () -> Unit,
) {
    var chordEditMode by remember(line.id) { mutableStateOf(false) }
    // Guards against onFocusChanged's spurious isFocused=false callback,
    // which fires the instant the chord field is first composed (before
    // requestFocus() has taken effect) — without this, chordEditMode flips
    // straight back to false in the same frame it was set true, and the
    // token row's onClick appears to silently do nothing.
    var chordFieldHasGainedFocus by remember(line.id) { mutableStateOf(false) }
    var chordsField by remember(line.id) { mutableStateOf(TextFieldValue(line.chords)) }
    var lyricsField by remember(line.id) { mutableStateOf(TextFieldValue(line.lyrics)) }
    val chordsFocus = remember(line.id) { FocusRequester() }
    val lyricsFocus = remember(line.id) { FocusRequester() }

    LaunchedEffect(line.chords) {
        if (chordsField.text != line.chords) chordsField = TextFieldValue(line.chords, TextRange(line.chords.length))
    }
    LaunchedEffect(line.lyrics) {
        if (lyricsField.text != line.lyrics) lyricsField = TextFieldValue(line.lyrics, TextRange(line.lyrics.length))
    }
    LaunchedEffect(pendingFocus) {
        val pf = pendingFocus ?: return@LaunchedEffect
        if (pf.lineId != line.id) return@LaunchedEffect
        if (pf.track == Track.Lyrics) {
            lyricsFocus.requestFocus()
            pf.caretIndex?.let { lyricsField = lyricsField.copy(selection = TextRange(it.coerceIn(0, lyricsField.text.length))) }
        } else {
            chordEditMode = true
            chordsFocus.requestFocus()
        }
        onConsumedPendingFocus()
    }
    // Tapping the token-display row (below) sets chordEditMode = true to
    // swap in the real text field, but the field doesn't exist yet at the
    // moment of that click — request focus on the next frame once it's
    // actually composed, same reasoning as the desktop's own
    // `setTimeout(() => chordsRef.current.focus(), 0)`.
    LaunchedEffect(chordEditMode) {
        if (chordEditMode) chordsFocus.requestFocus()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Chord track: plain text field while editing, colored token row otherwise.
        if (chordEditMode) {
            BasicTextField(
                value = chordsField,
                onValueChange = { chordsField = it; onChordsChange(it.text) },
                textStyle = chordTextStyle.copy(color = ChordColor),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { lyricsFocus.requestFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 26.dp)
                    .padding(vertical = 2.dp)
                    .focusRequester(chordsFocus)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            chordFieldHasGainedFocus = true
                        } else if (chordFieldHasGainedFocus) {
                            chordEditMode = false
                            chordFieldHasGainedFocus = false
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                            chordsField.selection.start == 0 && chordsField.selection.end == 0
                        ) {
                            if (line.chords.isBlank() && line.lyrics.isBlank()) onBackspaceDeleteEmpty()
                            true
                        } else {
                            false
                        }
                    },
            )
        } else {
            ChordTokenRow(text = line.chords, onClick = { chordEditMode = true })
        }

        BasicTextField(
            value = lyricsField,
            onValueChange = { new ->
                lyricsField = new
                onLyricsChange(new.text, new.selection.start)
            },
            textStyle = lyricTextStyle,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onEnter() }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp)
                .padding(vertical = 2.dp)
                .focusRequester(lyricsFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                        lyricsField.selection.start == 0 && lyricsField.selection.end == 0
                    ) {
                        onBackspaceMerge()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                if (line.lyrics.isEmpty()) Text("Lyrics…", style = lyricTextStyle.copy(color = TextMuted))
                inner()
            },
        )
        androidx.compose.material3.HorizontalDivider(color = PaperLine, thickness = 1.dp)
    }
}

@Composable
private fun ChordTokenRow(text: String, onClick: () -> Unit) {
    val tokens = remember(text) { tokenizeChordLine(text) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 26.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (tokens.isEmpty()) {
            Text(" ", style = chordTextStyle)
        } else {
            for (tok in tokens) {
                Text(
                    tok.text,
                    style = chordTextStyle.copy(
                        color = when {
                            tok.isWhitespace -> Color.Transparent
                            tok.looksLikeChord -> ChordColor
                            else -> TextMuted
                        },
                        textDecoration = if (!tok.isWhitespace && !tok.looksLikeChord) TextDecoration.Underline else null,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ImportStep(onCancel: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().background(WorkspaceBg).padding(24.dp)) {
        Text("Import lyrics + chords", style = MaterialTheme.typography.headlineSmall, color = LyricColor)
        Spacer(Modifier.height(8.dp))
        Text(
            "Paste a plain-text chord sheet — chords on their own line above the lyrics they " +
                "belong to, or bracketed like [G]. Replaces this song's lines.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = lyricTextStyle,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(ParchmentBg, RoundedCornerShape(8.dp))
                .padding(12.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row {
            TextButton(onClick = onCancel) { Text("Cancel", color = TextMuted) }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { onImport(text) }) { Text("Import", color = ChordColor, fontWeight = FontWeight.Bold) }
        }
    }
}
