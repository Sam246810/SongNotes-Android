package com.songnotes.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.songnotes.core.data.SongRepository
import com.songnotes.core.domain.ChordVoicing
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import com.songnotes.core.domain.SongMeta
import com.songnotes.core.domain.alignChordsWithLyrics
import com.songnotes.core.domain.anchorsToChordsLine
import com.songnotes.core.domain.chordsLineToAnchors
import com.songnotes.core.domain.formatFretsForInput
import com.songnotes.core.domain.lookupChord
import com.songnotes.core.domain.parseFretsInput
import com.songnotes.core.domain.tokenizeChordLine
import com.songnotes.core.domain.transposeChordsLine
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 5.5's editor, third pass. The second pass ported the desktop web
 * app's editing model faithfully (plain padded-string chords, colored
 * token display, Enter/Backspace line flow) — that part stays. What this
 * pass drops is the desktop's literal *visual* metaphor: a shadowed,
 * rounded-corner "page" card indented for a red margin rule. On a wide
 * monitor that reads as a notebook page with room to spare; on a phone it
 * meant a permanent ~40dp tax on every line's available width just to
 * clear a vertical line that then still visually cut across short lines'
 * text anyway. Dropped in favor of the same warm palette and typography
 * applied directly to a full-bleed background, with a thin horizontal
 * rule under each line (not a vertical one through the text) as the only
 * "ruled paper" cue — a more discreet nod to the theme that doesn't cost
 * layout width to maintain.
 *
 * Also replaces the fixed-character-count auto-split heuristic with one
 * based on actually measuring the line's rendered width
 * ([TextMeasurer]) against the real available width, for two reasons a
 * guessed character count got wrong in practice: it doesn't adapt to the
 * font-size control below, and worse, its "no good word-boundary found"
 * fallback could hard-split *inside* a word — never acceptable, per
 * direct feedback after it happened. The new version always finds an
 * actual space to split on, searching forward past the target width if
 * necessary rather than ever breaking a word — [findSplitIndex] only
 * reaches for a hard character-level cut once it's proven no space exists
 * anywhere left in the run, e.g. a pasted wall of characters with no
 * whitespace at all, where the alternative is the line silently
 * overflowing off the edge of the screen forever rather than a real word
 * getting mangled.
 *
 * The wire-format anchor model ([ChordAnchor]) stays exactly as
 * demoted-to-storage-detail as the second pass left it — nothing about
 * this pass touches that boundary.
 */

private val ParchmentBg = Color(0xFFF7F1E6)
private val ChordColor = Color(0xFFB45309)
private val LyricColor = Color(0xFF2A221B)
private val TextMuted = Color(0xFF8A7663)
private val PaperLine = Color(0xFFB45309).copy(alpha = 0.16f)

/** How long a lyrics line must sit still before the width-triggered auto-wrap (or its reverse, auto-merge) runs — see [handleLyricsChange]. */
private const val SPLIT_DEBOUNCE_MS = 180L

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

/**
 * Undoes the soft keyboard's "double space -> period" auto-punctuation on
 * the chords track, where a run of spaces is meaningful alignment against
 * the lyrics below, not prose — per direct feedback after it kept firing
 * mid-chord-entry and flinging the caret ahead of where the space was
 * actually tapped. Detected generically (a single space in the old value
 * became ". " in the new one, wherever in the string) rather than
 * special-cased to end-of-line, so it also catches the substitution
 * happening mid-line; a real, manually-typed period (e.g. the "N.C." no-
 * chord annotation) never matches this shape and passes through untouched.
 */
private fun suppressDoubleSpacePeriod(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val oldText = old.text
    val newText = new.text
    if (newText.length != oldText.length + 1) return new

    var prefix = 0
    while (prefix < oldText.length && prefix < newText.length && oldText[prefix] == newText[prefix]) prefix++
    val maxSuffix = minOf(oldText.length - prefix, newText.length - prefix)
    var suffix = 0
    while (suffix < maxSuffix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++

    val oldMiddle = oldText.substring(prefix, oldText.length - suffix)
    val newMiddle = newText.substring(prefix, newText.length - suffix)
    if (oldMiddle != " " || newMiddle != ". ") return new

    val corrected = oldText.substring(0, prefix) + "  " + oldText.substring(oldText.length - suffix)
    return TextFieldValue(corrected, TextRange(prefix + 2))
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

/**
 * Finds where to split [text] so it fits within [maxWidthPx] at [style],
 * preferring a real space over breaking mid-word — the earlier
 * fixed-character-count heuristic's habit of hard-splitting *ordinary*
 * words it had simply misjudged the width of was rejected outright per
 * direct feedback, and that stands: any word a nearby space could resolve
 * is never cut. This only reaches for a hard character-level cut as the
 * last resort *after* [wrapLineByWidth] has proven no space exists
 * anywhere in the run being measured — a pasted wall of characters (a long
 * URL, mashed keys, no whitespace at all), not a real word a smarter split
 * point would have saved. Leaving that run untouched doesn't avoid
 * mangling a word; it just means the line silently overflows off the edge
 * of the screen forever, which is worse.
 */
private fun findSplitIndex(text: String, style: TextStyle, maxWidthPx: Int, measurer: TextMeasurer): Int? {
    if (maxWidthPx <= 0 || text.isEmpty()) return null
    val layout = measurer.measure(text, style)
    if (layout.size.width <= maxWidthPx) return null
    val boundary = layout.getOffsetForPosition(Offset(maxWidthPx.toFloat(), 0f)).coerceIn(0, text.length - 1)
    val lastSpaceBefore = text.lastIndexOf(' ', boundary)
    if (lastSpaceBefore > 0) return lastSpaceBefore
    // No space before the target width at all — one very long leading
    // word/token. Rather than break it, look forward for the next space
    // so the whole word stays together, even if this line ends up a bit
    // wider than the target.
    val nextSpaceAfter = text.indexOf(' ', boundary)
    if (nextSpaceAfter > 0) return nextSpaceAfter
    // No space anywhere in this run, forward or back -- there is no word
    // left to protect. Cut right at the measured boundary so the line
    // makes progress instead of overflowing indefinitely; nudge off a
    // surrogate-pair boundary (an emoji) so that doesn't get split in two.
    if (boundary <= 0) return null // can't make forward progress at all
    val cut = if (text[boundary].isLowSurrogate()) boundary - 1 else boundary
    return if (cut > 0) cut else null
}

/**
 * Repeatedly applies [findSplitIndex]/[splitLineAt] until every resulting
 * piece fits within [maxWidthPx] — as opposed to a single split pass, which
 * only shortens a too-long line once and leaves it still overflowing when
 * it started out several multiples of the available width (the common case
 * for a large paste, rather than a line that only just grew past the edge
 * one keystroke at a time).
 *
 * [line]'s own id is reassigned to the LAST piece rather than the first.
 * [splitLineAt] hands the incoming id to the earlier half and mints a
 * fresh one for the tail on every pass, which is backwards for typing:
 * the tail is where the caret actually is, so as someone keeps typing
 * past the edge, every single threshold-crossing keystroke was spawning a
 * brand-new composable + FocusRequester under their still-live cursor and
 * reassigning focus to it. A keystroke that arrived before that focus
 * transfer finished landed on the old, about-to-be-abandoned field instead
 * — forking what they were typing across two rows, with the earlier one
 * left orphaned ("just sits there") — reproduced directly by typing fast
 * through a wrap boundary. Keeping the original id on the tail means the
 * actively-focused field never actually changes identity while someone is
 * mid-word; only the already-committed earlier piece(s) need a new one.
 */
private fun wrapLineByWidth(line: EditorLine, style: TextStyle, maxWidthPx: Int, measurer: TextMeasurer): List<EditorLine> {
    val pieces = mutableListOf<EditorLine>()
    var current = line
    while (true) {
        val splitIdx = findSplitIndex(current.lyrics, style, maxWidthPx, measurer)
        if (splitIdx == null) {
            pieces += current
            break
        }
        val (first, second) = splitLineAt(current, splitIdx)
        pieces += first
        current = second
    }
    if (pieces.size <= 1) return pieces
    return pieces.mapIndexed { i, p -> p.copy(id = if (i == pieces.lastIndex) line.id else UUID.randomUUID().toString()) }
}

@Composable
fun SongEditorScreen(songId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SongRepository(context) }
    val sessionStore = remember { EditorSessionStore(context) }
    // AppScope.io, not rememberCoroutineScope() -- see SongDraftAutosaver's own
    // doc comment for why: a debounced write must survive this composable
    // leaving composition, not get cancelled by it.
    val autosaver = remember { SongDraftAutosaver(repo, AppScope.io) }

    // Room's load is suspend, unlike the old SongStorage's synchronous file read —
    // nothing below renders until it resolves, same "loading state gates the real
    // UI" pattern as everywhere else Compose talks to a database.
    var loadedSong by remember { mutableStateOf<Song?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(songId) {
        sessionStore.lastOpenSongId = songId
        val existing = repo.getById(songId)
        if (existing == null) {
            // Phase 13: no longer synthesizes an empty placeholder here (the
            // old emptySong() fallback would have created a brand-new song
            // with createdAt = 0 the moment anything triggered persist()).
            // A missing id here means either a stale last-open-song pointer,
            // or the song was deleted/pulled-as-a-tombstone by a sync that
            // happened while this device wasn't looking -- either way there
            // is nothing to edit; bail back to the list rather than
            // resurrecting a ghost song.
            sessionStore.lastOpenSongId = null
            missing = true
        } else {
            loadedSong = existing
        }
    }
    if (missing) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val loaded = loadedSong ?: return

    var title by remember { mutableStateOf(loaded.title) }
    var meta by remember { mutableStateOf(loaded.meta) }
    var lines by remember { mutableStateOf(songToEditorLines(loaded)) }
    var customChords by remember { mutableStateOf(loaded.customChords) }
    var pendingFocus by remember { mutableStateOf<PendingFocus?>(null) }
    // UI-only state (never persisted to Room -- there's nothing here Room's
    // own draft-of-record needs to know about) survives a config change via
    // rememberSaveable; title/meta/lines/customChords deliberately do NOT --
    // Room already IS the durable draft store (see SongDraftAutosaver's doc
    // comment), and a rememberSaveable Saver for a whole song's `lines` list
    // risks Bundle's ~500KB TransactionTooLargeException on a long song for
    // no benefit over what the autosaver + lifecycle flush already guarantee.
    var activeChordName by rememberSaveable { mutableStateOf<String?>(null) }
    var fontScale by rememberSaveable { mutableStateOf(1f) }
    var linesAreaWidthPx by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // Per-line debounce jobs for the width-triggered auto-wrap -- see
    // handleLyricsChange's doc comment for why this can't just run inline
    // on every keystroke.
    val splitJobs = remember { mutableMapOf<String, Job>() }
    val textMeasurer = rememberTextMeasurer()

    // Phase 13: guarantees nothing typed is lost on any exit path, not just
    // the Done button. ON_STOP is the last lifecycle callback guaranteed
    // before the process becomes a kill candidate; onDispose covers this
    // composable leaving the tree for any other reason (back-press,
    // navigating away via a route other than Done).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) AppScope.io.launch { autosaver.flush() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            AppScope.io.launch { autosaver.flush() }
        }
    }

    fun finish() {
        scope.launch {
            autosaver.flush()
            sessionStore.lastOpenSongId = null
            onDone()
        }
    }

    BackHandler { finish() }

    val chordStyle = baseChordTextStyle.copy(fontSize = baseChordTextStyle.fontSize * fontScale)
    val lyricStyle = baseLyricTextStyle.copy(fontSize = baseLyricTextStyle.fontSize * fontScale)

    fun currentSong(): Song = Song(
        id = songId,
        title = title,
        meta = meta,
        lines = lines.map { SongLine(id = it.id, lyrics = it.lyrics, chords = chordsLineToAnchors(it.chords)) },
        customChords = customChords,
        createdAt = loaded.createdAt,
        updatedAt = System.currentTimeMillis(),
    )

    // An immediate (non-debounced) save through the SAME autosaver + Mutex a
    // debounced text edit uses -- schedule() then flush() right away, rather
    // than a separate direct repo.upsert(), so a voicing save can never race
    // a still-pending debounced write into two independent upserts.
    fun saveNow(song: Song) {
        scope.launch {
            autosaver.schedule(song)
            autosaver.flush()
        }
    }

    fun saveVoicing(chordName: String, voicing: ChordVoicing) {
        customChords = customChords + (chordName to voicing)
        saveNow(currentSong())
    }

    fun resetVoicing(chordName: String) {
        customChords = customChords - chordName
        saveNow(currentSong())
    }

    fun persist() {
        autosaver.schedule(currentSong())
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

    /**
     * Re-checks [lineId]'s *current* lyrics (read fresh from `lines`, not a
     * captured snapshot) against the available width and reflows if
     * needed — called only after [SPLIT_DEBOUNCE_MS] of no further edits
     * to this line, per [handleLyricsChange]'s doc comment. Two directions:
     *
     * - Grown past the edge: split forward into wrapped pieces, same as
     *   before.
     * - Shrunk by backspacing far enough that it would now fit back onto
     *   the previous line: merge into it, undoing an earlier split the
     *   same way it happened — a few keystrokes of backspacing snap two
     *   lines back into one instead of leaving a now-short orphan line
     *   sitting there until it's explicitly joined at the very start.
     *
     * These are mutually exclusive (a line can't simultaneously overflow
     * on its own and also fit combined with the one above it), so one
     * function handles both rather than two independent checks.
     */
    fun performReflowIfNeeded(lineId: String, caretIndex: Int) {
        val idx = lines.indexOfFirst { it.id == lineId }
        if (idx == -1) return
        val curr = lines[idx]

        if (idx > 0) {
            val prev = lines[idx - 1]
            // Keep curr's id on the merged result, not prev's -- same
            // reasoning as wrapLineByWidth: curr is where the caret (and
            // focus) already is, so the merge must not hand identity to a
            // different, unfocused composable while backspacing continues.
            val merged = mergeWithPrevious(prev, curr).copy(id = curr.id)
            if (wrapLineByWidth(merged, lyricStyle, linesAreaWidthPx, textMeasurer).size <= 1) {
                lines = lines.toMutableList().apply { removeAt(idx); set(idx - 1, merged) }
                pendingFocus = PendingFocus(curr.id, Track.Lyrics, prev.lyrics.length + caretIndex)
                persist()
                return
            }
        }

        val produced = wrapLineByWidth(curr, lyricStyle, linesAreaWidthPx, textMeasurer)
        if (produced.size <= 1) return // still fits as-is -- nothing to do

        lines = lines.toMutableList().apply { removeAt(idx); addAll(idx, produced) }

        var remaining = caretIndex
        var chosen = produced.last()
        var localCaret = chosen.lyrics.length
        for (p in produced) {
            if (remaining <= p.lyrics.length) {
                chosen = p
                localCaret = remaining.coerceAtLeast(0)
                break
            }
            remaining -= p.lyrics.length
        }
        pendingFocus = PendingFocus(chosen.id, Track.Lyrics, localCaret)
        persist()
    }

    /**
     * Handles every edit to a line's lyrics, including a large paste — not
     * just the character-at-a-time case a single width check used to
     * assume. A paste can contain literal newlines (the lyrics field is
     * `singleLine`, so those would otherwise sit inside one field and get
     * visually swallowed rather than becoming separate lines) — handled
     * immediately below, since a paste is a one-shot event.
     *
     * The far more common case — an ordinary line growing past the width
     * one keystroke at a time — is deliberately NOT split inline here.
     * [wrapLineByWidth] used to run on every keystroke, replacing the
     * overflowing `EditorLine` with new pieces and moving focus to
     * whichever one the caret landed in. Direct repro on a physical device
     * (typing fast, not even a paste) showed why that's unsafe: Compose's
     * recomposition and the lyrics field's own local `TextFieldValue` only
     * catch up to a freshly-shortened `line.lyrics` on a later frame, so a
     * keystroke that arrives before that catch-up lands on the field's
     * still-stale (pre-split, longer) buffer instead — forking what was
     * typed across an abandoned old row and a live new one. Debouncing the
     * *reflow* (never the raw text update just below, which always applies
     * immediately so nothing typed is ever lost) means the restructuring —
     * splitting a line that grew past the edge, or, symmetrically,
     * backspacing one back into the line above it once it fits again, see
     * [performReflowIfNeeded] — only ever runs once typing has actually
     * paused, when there's nothing left to race against it.
     */
    fun handleLyricsChange(line: EditorLine, updatedLyrics: String, caretIndex: Int) {
        if (updatedLyrics.contains('\n')) {
            splitJobs.remove(line.id)?.cancel()
            val idx = lines.indexOfFirst { it.id == line.id }
            if (idx == -1) return
            val rawSegments = updatedLyrics.split('\n')
            val produced = rawSegments.flatMapIndexed { i, seg ->
                val base = if (i == 0) {
                    EditorLine(line.id, alignChordsWithLyrics(line.chords, seg), seg)
                } else {
                    EditorLine(UUID.randomUUID().toString(), "", seg)
                }
                wrapLineByWidth(base, lyricStyle, linesAreaWidthPx, textMeasurer)
            }
            lines = lines.toMutableList().apply { removeAt(idx); addAll(idx, produced) }
            // A multi-line paste lands the caret at the end of what was pasted.
            pendingFocus = produced.last().let { PendingFocus(it.id, Track.Lyrics, it.lyrics.length) }
            persist()
            return
        }

        updateLine(line.id) { it.copy(lyrics = updatedLyrics) }

        splitJobs[line.id]?.cancel()
        splitJobs[line.id] = scope.launch {
            delay(SPLIT_DEBOUNCE_MS)
            performReflowIfNeeded(line.id, caretIndex)
            splitJobs.remove(line.id)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ParchmentBg),
    ) {
        // Top bar: just Done, own row with real clearance from the status
        // bar — same "actions on top, title below with room to breathe"
        // rhythm Samsung Notes/Keep/Apple Notes all use, rather than
        // cramming the title into the same row as an action button flush
        // against the top edge.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { finish() }) { Text("Done", fontWeight = FontWeight.Bold, color = ChordColor) }
        }
        BasicTextField(
            value = title,
            onValueChange = { title = it; persist() },
            textStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = LyricColor),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            decorationBox = { inner ->
                if (title.isEmpty()) Text("Title", style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = TextMuted)
                inner()
            },
        )
        Spacer(Modifier.height(12.dp))
        SongMetaBar(meta = meta, onUpdateMeta = { updated -> meta = updated; persist() })
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { copySongTextToClipboard(context, currentSong()) }) { Text("Export text", color = ChordColor) }
            TextButton(onClick = { shareSongAsPdf(context, currentSong()) }) { Text("Export PDF", color = ChordColor) }
            TextButton(onClick = {
                lines = lines.map { it.copy(chords = transposeChordsLine(it.chords, -1) ?: it.chords) }
                persist()
            }) { Text("Transpose -1", color = ChordColor) }
            TextButton(onClick = {
                lines = lines.map { it.copy(chords = transposeChordsLine(it.chords, 1) ?: it.chords) }
                persist()
            }) { Text("Transpose +1", color = ChordColor) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { fontScale = (fontScale - 0.1f).coerceAtLeast(0.75f) }) {
                Text("A-", color = TextMuted, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { fontScale = (fontScale + 0.1f).coerceAtMost(1.4f) }) {
                Text("A+", color = TextMuted, fontWeight = FontWeight.Bold)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = PaperLine, thickness = 1.dp)
        Spacer(Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { linesAreaWidthPx = it.size.width },
        ) {
            items(lines, key = { it.id }) { line ->
                LineRow(
                    line = line,
                    chordStyle = chordStyle,
                    lyricStyle = lyricStyle,
                    customChords = customChords,
                    onTapChord = { name -> activeChordName = name },
                    pendingFocus = pendingFocus,
                    onConsumedPendingFocus = { pendingFocus = null },
                    onChordsChange = { updated -> updateLine(line.id) { it.copy(chords = updated) } },
                    onLyricsChange = { updated, caret -> handleLyricsChange(line, updated, caret) },
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

    activeChordName?.let { name ->
        ChordVoicingPanel(
            chordName = name,
            voicing = lookupChord(name, customChords),
            isCustom = customChords.containsKey(name),
            onSave = { voicing -> saveVoicing(name, voicing) },
            onReset = { resetVoicing(name) },
            onDismiss = { activeChordName = null },
        )
    }
    }
}

private val baseChordTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.4.sp,
)
private val baseLyricTextStyle = TextStyle(
    fontSize = 15.sp,
    color = LyricColor,
)

@Composable
private fun LineRow(
    line: EditorLine,
    chordStyle: TextStyle,
    lyricStyle: TextStyle,
    customChords: Map<String, ChordVoicing>,
    onTapChord: (String) -> Unit,
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
                onValueChange = { candidate ->
                    chordsField = suppressDoubleSpacePeriod(chordsField, candidate)
                    onChordsChange(chordsField.text)
                },
                textStyle = chordStyle.copy(color = ChordColor),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onNext = { lyricsFocus.requestFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp)
                    .padding(vertical = 1.dp)
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
                decorationBox = { inner ->
                    if (chordsField.text.isEmpty()) Text("Chords…", style = chordStyle.copy(color = TextMuted))
                    inner()
                },
            )
        } else {
            ChordTokenRow(
                text = line.chords,
                style = chordStyle,
                customChords = customChords,
                onClick = { chordEditMode = true },
                onTapChord = onTapChord,
            )
        }

        BasicTextField(
            value = lyricsField,
            onValueChange = { new ->
                lyricsField = new
                onLyricsChange(new.text, new.selection.start)
            },
            textStyle = lyricStyle,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onEnter() }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .padding(vertical = 1.dp)
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
                if (line.lyrics.isEmpty()) Text("Lyrics…", style = lyricStyle.copy(color = TextMuted))
                inner()
            },
        )
        HorizontalDivider(color = PaperLine, thickness = 1.dp)
    }
}

@Composable
private fun ChordTokenRow(
    text: String,
    style: TextStyle,
    customChords: Map<String, ChordVoicing>,
    onClick: () -> Unit,
    onTapChord: (String) -> Unit,
) {
    val tokens = remember(text, customChords) { tokenizeChordLine(text, customChords) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (tokens.isEmpty()) {
            Text("Chords…", style = style.copy(color = TextMuted))
        } else {
            for (tok in tokens) {
                Text(
                    tok.text,
                    style = style.copy(
                        color = when {
                            tok.isWhitespace -> Color.Transparent
                            tok.looksLikeChord -> ChordColor
                            else -> TextMuted
                        },
                        textDecoration = if (!tok.isWhitespace && !tok.looksLikeChord) TextDecoration.Underline else null,
                    ),
                    // Whitespace tokens have no chordName and stay unclickable so a
                    // tap there still falls through to the Row's onClick (raw edit mode).
                    modifier = if (!tok.isWhitespace) {
                        Modifier.clickable(onClick = { onTapChord(tok.chordName ?: tok.text) })
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/**
 * A small reference strip -- BPM/Key/Tuning/Capo, all optional free-text
 * fields, matching the web app's `SongMetaBar.jsx` exactly (same 4 fields,
 * same placeholders, no validation beyond BPM/Capo staying numeric since
 * [SongMeta.bpm]/[SongMeta.capo] are typed `Int` here rather than the JS
 * side's untyped string). Transpose stays in the existing button row above
 * rather than duplicating it here -- this bar is reference fields only.
 */
@Composable
private fun SongMetaBar(meta: SongMeta, onUpdateMeta: (SongMeta) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MetaField(
            label = "BPM",
            value = if (meta.bpm == 0) "" else meta.bpm.toString(),
            onValueChange = { onUpdateMeta(meta.copy(bpm = it.filter(Char::isDigit).toIntOrNull() ?: 0)) },
            placeholder = "—",
            modifier = Modifier.weight(1f),
        )
        MetaField(
            label = "Key",
            value = meta.key,
            onValueChange = { onUpdateMeta(meta.copy(key = it)) },
            placeholder = "—",
            modifier = Modifier.weight(1f),
        )
        MetaField(
            label = "Tuning",
            value = meta.tuning,
            onValueChange = { onUpdateMeta(meta.copy(tuning = it)) },
            placeholder = "Standard",
            modifier = Modifier.weight(1.3f),
        )
        MetaField(
            label = "Capo",
            value = if (meta.capo == 0) "" else meta.capo.toString(),
            onValueChange = { onUpdateMeta(meta.copy(capo = it.filter(Char::isDigit).toIntOrNull() ?: 0)) },
            placeholder = "—",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetaField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted, letterSpacing = 0.6.sp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontSize = 13.sp, color = LyricColor),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = TextStyle(fontSize = 13.sp, color = TextMuted))
                inner()
            },
        )
        HorizontalDivider(color = PaperLine, thickness = 1.dp)
    }
}

/**
 * Bottom-anchored overlay showing a tapped chord's diagram, with an inline
 * voicing editor -- Compose port of `ChordDiagram.jsx`'s popup, minus the
 * hover-anchored positioning (a touch UI has no hover; a full-width bottom
 * panel is the mobile-appropriate equivalent, dismissible by tapping the
 * scrim or the Close button).
 */
@Composable
private fun ChordVoicingPanel(
    chordName: String,
    voicing: ChordVoicing?,
    isCustom: Boolean,
    onSave: (ChordVoicing) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember(chordName) { mutableStateOf(false) }
    var draft by remember(chordName) { mutableStateOf("") }
    var error by remember(chordName) { mutableStateOf<String?>(null) }

    fun startEditing() {
        draft = voicing?.let { formatFretsForInput(it.frets) } ?: ""
        error = null
        editing = true
    }

    fun handleSave() {
        val parsed = parseFretsInput(draft)
        if (parsed == null) {
            error = "Enter 6 values (0–24 or x), e.g. \"x 3 2 0 1 0\""
            return
        }
        onSave(ChordVoicing(frets = parsed.frets, baseFret = parsed.baseFret))
        editing = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {} // absorb taps -- don't dismiss through the panel itself
                .background(ParchmentBg, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .navigationBarsPadding() // otherwise the bottom button sits under the system nav bar and is untappable
                .padding(20.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(chordName, style = MaterialTheme.typography.headlineSmall, color = LyricColor, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) }
            }
            Spacer(Modifier.height(12.dp))

            if (editing) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it; error = null },
                    textStyle = baseChordTextStyle.copy(color = ChordColor, fontSize = 16.sp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) Text("x 3 2 0 1 0", style = baseChordTextStyle.copy(color = TextMuted, fontSize = 16.sp))
                        inner()
                    },
                )
                Text(
                    "low E → high E, 0 = open, x = muted",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    TextButton(onClick = { handleSave() }) { Text("Save", color = ChordColor, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { editing = false; error = null }) { Text("Cancel", color = TextMuted) }
                    if (isCustom) {
                        TextButton(onClick = { onReset(); editing = false }) { Text("Reset", color = TextMuted) }
                    }
                }
            } else if (voicing == null) {
                Text("no chord chart for this chord yet >.<", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { startEditing() }) { Text("+ Add voicing", color = ChordColor, fontWeight = FontWeight.Bold) }
            } else {
                ChordDiagram(voicing = voicing)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { startEditing() }) {
                    Text(if (isCustom) "Edit voicing" else "Suggest a different voicing", color = ChordColor)
                }
            }
        }
    }
}
