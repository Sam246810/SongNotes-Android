package com.songnotes.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.data.SongRepository
import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.ChordBarre
import com.songnotes.core.domain.ChordVoicing
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import com.songnotes.core.domain.SongMeta
import com.songnotes.core.domain.anchorsToChordsLine
import com.songnotes.core.domain.chordsLineToAnchors
import com.songnotes.core.domain.cutIntoRows
import com.songnotes.core.domain.formatFretsForInput
import com.songnotes.core.domain.lookupChord
import com.songnotes.core.domain.parseFretsInput
import com.songnotes.core.domain.tokenizeChordLine
import com.songnotes.core.domain.transposeChordsLine
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

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
 * Lyrics wrap the way Samsung Notes does (compared side by side on-device):
 * each real line is a [Paragraph] whose rows are re-flowed from its whole
 * text with Android's own line breaker ([breakParagraph]), so words move
 * between rows as you type or delete, wrapped rows start flush, and
 * backspace at the start of a wrapped row deletes the character before it
 * rather than "joining" anything. Wrapping is display-only: each paragraph is
 * saved as one [SongLine] and re-wrapped for whatever screen and font size
 * opens it, and exports wrap it to the page. The chords track never wraps;
 * it's hard-capped at what fits on screen ([chordCapacity]) so it never
 * scrolls sideways. Lyrics and chords share one monospace glyph width, so a
 * chord's column is exactly the lyric character it sits over.
 *
 * The wire-format anchor model ([ChordAnchor]) is still a storage detail,
 * plus how chords keep their place across a re-wrap.
 */

// The parchment palette moved into the theme (see Theme.kt) so it can have a
// Cozy Dark variant, matching the web app's own long-standing light/dark toggle.
// These stay here as @Composable accessors with the same names, so every call
// site below reads exactly as it did when they were plain constants -- the
// values are now looked up per-composition instead of being fixed at class-init.
private val ParchmentBg: Color @Composable get() = SongNotesTheme.colors.parchment
private val ChordColor: Color @Composable get() = SongNotesTheme.colors.chord
private val LyricColor: Color @Composable get() = SongNotesTheme.colors.lyric
private val TextMuted: Color @Composable get() = SongNotesTheme.colors.muted
private val PaperLine: Color @Composable get() = SongNotesTheme.colors.paperLine

/** How long a lyrics row must sit still before its paragraph is re-wrapped after typing — see [handleLyricsChange]. */
private const val SPLIT_DEBOUNCE_MS = 180L

/**
 * How long editing must pause before the current text state is committed as
 * one undo step — matches the "coalesce a run of typing into a single undo"
 * behavior of most text editors, rather than pushing a new stack frame on
 * every keystroke (which would make Undo only ever step back one character).
 */
private const val HISTORY_DEBOUNCE_MS = 600L

/** The autoscroll button's height plus its margins -- see [AutoscrollControls]. */
private val AUTOSCROLL_BUTTON_CLEARANCE = 88.dp
private const val MAX_HISTORY = 100

/**
 * One visual row of the editor. Consecutive rows sharing a [paragraphId] are
 * one paragraph -- a real line the user made (Enter/Next, a pasted newline,
 * "+ Add line") and the soft-wrap rows it needed to fit the screen width.
 * Editing treats a paragraph's rows as one string, the way Samsung Notes (any
 * text editor) treats a wrapped line, and each paragraph is saved as one
 * [SongLine] whose id is [paragraphId] -- see [Paragraph]. [id] is only the
 * row's on-screen identity (its composable key and focus target), which moves
 * between rows as text re-wraps.
 */
private data class EditorLine(val id: String, val paragraphId: String, val chords: String, val lyrics: String)
private data class PendingFocus(val lineId: String, val caretIndex: Int? = null)

/** A point-in-time snapshot of everything Undo/Redo restores — deliberately NOT song metadata like id/createdAt, which never change from within the editor. */
private data class EditorSnapshot(
    val title: String,
    val meta: SongMeta,
    val lines: List<EditorLine>,
    val customChords: Map<String, ChordVoicing>,
)

/**
 * A saved-instance-state Bundle survives both a fold/unfold (a screen-size
 * config change, not just rotation -- Android recreates the Activity for it
 * the same as rotation, and this app declares no configChanges to opt out)
 * and a plain backgrounding severe enough for the OS to kill the process for
 * memory -- both destroy and recreate [SongEditorScreen], which would
 * otherwise silently wipe an in-progress undo/redo history built on plain
 * `remember`. Serializing through here (same "one JSON blob" shape
 * [SongEntity] already uses for Room, org.json rather than a new dependency)
 * is what lets [undoStack]/[redoStack] survive that.
 *
 * Deliberately capped independently of [MAX_HISTORY]: that cap bounds how
 * many snapshots live in memory during a normal session, where the cost is
 * just Kotlin objects. This trims what actually gets written into the
 * Activity's saved-instance-state Bundle, which -- per [SongDraftAutosaver]'s
 * own TransactionTooLargeException concern about a single copy of `lines` --
 * is a much tighter, shared budget: a long song's full history at
 * [MAX_HISTORY] depth could alone blow past what the whole Bundle can hold.
 * Both a count and a total-byte-size cap apply, oldest entries dropped
 * first, since either alone leaves a gap (few entries from a huge song, or
 * many from a tiny one) the other catches.
 */
private const val MAX_SAVED_HISTORY = 20
private const val MAX_SAVED_HISTORY_BYTES = 150_000

private fun serializeSnapshot(snapshot: EditorSnapshot): String {
    val obj = JSONObject()
    obj.put("title", snapshot.title)
    obj.put(
        "meta",
        JSONObject()
            .put("bpm", snapshot.meta.bpm)
            .put("key", snapshot.meta.key)
            .put("tuning", snapshot.meta.tuning)
            .put("capo", snapshot.meta.capo),
    )
    val linesArr = JSONArray()
    for (line in snapshot.lines) {
        linesArr.put(
            JSONObject().put("id", line.id).put("paragraphId", line.paragraphId).put("chords", line.chords).put("lyrics", line.lyrics),
        )
    }
    obj.put("lines", linesArr)
    val customChordsObj = JSONObject()
    for ((name, voicing) in snapshot.customChords) {
        val voicingJson = JSONObject().put("frets", JSONArray(voicing.frets)).put("baseFret", voicing.baseFret)
        voicing.barre?.let {
            voicingJson.put("barre", JSONObject().put("fret", it.fret).put("fromString", it.fromString).put("toString", it.toString))
        }
        customChordsObj.put(name, voicingJson)
    }
    obj.put("customChords", customChordsObj)
    return obj.toString()
}

/** Inverse of [serializeSnapshot]. Never throws on its own -- callers wrap this in [runCatching] so one malformed saved entry (e.g. a future app version changing this shape) drops just that entry instead of crashing history restoration entirely. */
private fun deserializeSnapshot(json: String): EditorSnapshot {
    val obj = JSONObject(json)
    val metaObj = obj.getJSONObject("meta")
    val meta = SongMeta(bpm = metaObj.getInt("bpm"), key = metaObj.getString("key"), tuning = metaObj.getString("tuning"), capo = metaObj.getInt("capo"))
    val linesArr = obj.getJSONArray("lines")
    val lines = (0 until linesArr.length()).map { i ->
        val lineObj = linesArr.getJSONObject(i)
        val id = lineObj.getString("id")
        EditorLine(
            id = id,
            paragraphId = lineObj.optString("paragraphId", id),
            chords = lineObj.getString("chords"),
            lyrics = lineObj.getString("lyrics"),
        )
    }
    val customChordsObj = obj.getJSONObject("customChords")
    val customChords = LinkedHashMap<String, ChordVoicing>()
    for (name in customChordsObj.keys()) {
        val voicingObj = customChordsObj.getJSONObject(name)
        val fretsArr = voicingObj.getJSONArray("frets")
        val frets = (0 until fretsArr.length()).map { fretsArr.getInt(it) }
        val barreObj = voicingObj.optJSONObject("barre")
        val barre = barreObj?.let { ChordBarre(fret = it.getInt("fret"), fromString = it.getInt("fromString"), toString = it.getInt("toString")) }
        customChords[name] = ChordVoicing(frets = frets, baseFret = voicingObj.getInt("baseFret"), barre = barre)
    }
    return EditorSnapshot(title = obj.getString("title"), meta = meta, lines = lines, customChords = customChords)
}

private val editorSnapshotListSaver: Saver<List<EditorSnapshot>, Any> = listSaver(
    save = { stack ->
        // Oldest-first (stack order), so takeLast keeps the most recent --
        // the entries an Undo press would actually reach first.
        var kept = stack.takeLast(MAX_SAVED_HISTORY).map { serializeSnapshot(it) }
        while (kept.size > 1 && kept.sumOf { it.length } > MAX_SAVED_HISTORY_BYTES) kept = kept.drop(1)
        kept
    },
    restore = { saved -> saved.mapNotNull { runCatching { deserializeSnapshot(it) }.getOrNull() } },
)

/**
 * One unwrapped row per saved line (paragraph), shown only until the editor
 * knows its width and wraps the song (see `wrapLoadedSong`).
 */
private fun songToEditorLines(song: Song): List<EditorLine> {
    if (song.lines.isEmpty()) return listOf(newEditorLine())
    return song.lines.map { EditorLine(it.id, it.id, renderChordAnchors(it.chords, padTo = it.lyrics.length), it.lyrics) }
}

/** A blank row starting a paragraph of its own. */
private fun newEditorLine(): EditorLine = EditorLine(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "", "")

/** The saved form of [rows]: one [SongLine] per paragraph, wrapping undone. */
private fun songLinesOf(rows: List<EditorLine>): List<SongLine> {
    val out = mutableListOf<SongLine>()
    var i = 0
    while (i < rows.size) {
        var end = i
        while (end + 1 < rows.size && rows[end + 1].paragraphId == rows[i].paragraphId) end++
        val p = paragraphOf(rows.subList(i, end + 1))
        out += SongLine(id = p.id, lyrics = p.lyrics, chords = p.anchors.sortedBy { it.i })
        i = end + 1
    }
    return out
}

/**
 * The chords track's hard length limit, in characters: as many monospace
 * glyphs as fit across the lines area at the current font size, with room
 * left for the caret. The chords field is single-line and never wraps (a run
 * of chord names has no sensible place to break), so anything longer than
 * this makes the field scroll sideways to keep the caret in view. A fixed
 * character count can't prevent that, because how many characters fit
 * depends on the screen width and the A-/A+ font size.
 */
private fun chordCapacity(charWidthPx: Float, maxWidthPx: Int, caretSlackPx: Int): Int =
    if (maxWidthPx <= 0 || charWidthPx <= 0f) Int.MAX_VALUE else ((maxWidthPx - caretSlackPx) / charWidthPx).toInt().coerceAtLeast(1)

/** Advance of one glyph of a monospace [style], letter spacing included. */
private fun monospaceAdvance(style: TextStyle, measurer: TextMeasurer): Float =
    measurer.measure("M".repeat(100), style).size.width / 100f

/**
 * Applies [capacity] to a chords-field edit like a maxlength, with one
 * allowance: spaces trailing after the edit are alignment padding (see
 * [renderChordAnchors]), not content, so an insertion may consume them before
 * anything is refused -- otherwise a chord line padded out under a long lyric
 * line would refuse every keystroke. An insertion that only partly fits keeps
 * what fits (a paste is truncated); one that can't fit at all is dropped and
 * the field stays as it was. Edits that don't lengthen the line (deleting,
 * replacing) always go through, so a line already over the limit -- old
 * data, or the font just got bigger -- can still be shortened.
 */
private fun capChordInput(old: TextFieldValue, new: TextFieldValue, capacity: Int): TextFieldValue {
    val oldText = old.text
    val newText = new.text
    if (newText.length <= capacity || newText.length <= oldText.length) return new

    var prefix = 0
    while (prefix < oldText.length && prefix < newText.length && oldText[prefix] == newText[prefix]) prefix++
    val maxSuffix = minOf(oldText.length, newText.length) - prefix
    var suffix = 0
    while (suffix < maxSuffix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++

    val before = newText.substring(0, prefix)
    var inserted = newText.substring(prefix, newText.length - suffix)
    var after = newText.substring(newText.length - suffix)

    val overBy = before.length + inserted.length + after.length - capacity
    val trailingPadding = after.length - after.trimEnd().length
    after = after.dropLast(minOf(overBy, trailingPadding))

    val stillOver = before.length + inserted.length + after.length - capacity
    if (stillOver > 0) {
        var keep = (inserted.length - stillOver).coerceAtLeast(0)
        if (keep > 0 && inserted[keep - 1].isHighSurrogate()) keep--
        inserted = inserted.take(keep)
    }
    if (inserted.isEmpty()) return old
    return TextFieldValue(before + inserted + after, TextRange(before.length + inserted.length))
}

/**
 * Renders chord anchors as a chords-track string, padded with spaces to
 * [padTo]. Unlike the wire format's [anchorsToChordsLine] -- where a later
 * anchor overwrites an earlier one at a shared column -- an anchor that would
 * collide with the one before it is nudged right instead: an editor must never
 * fuse two chord names into one ("Am" + "G" -> "AG"). Collisions only arise
 * when re-wrapping a paragraph lands chords from two different rows on one.
 */
private fun renderChordAnchors(anchors: List<ChordAnchor>, padTo: Int): String {
    val sb = StringBuilder()
    for (anchor in anchors.sortedBy { it.i }) {
        val start = if (sb.isEmpty()) anchor.i else maxOf(anchor.i, sb.length + 1)
        while (sb.length < start) sb.append(' ')
        sb.append(anchor.c)
    }
    while (sb.length < padTo) sb.append(' ')
    return sb.toString()
}

/**
 * A lyrics row's chords after its lyrics went from [oldLyrics] to
 * [newLyrics] by typing with the caret ending at [caret]: each chord stays
 * over the character it was over, as the words it marks move. Without this,
 * typing in the middle of a row left its chords at their old columns while
 * the words shifted out from under them -- and once a paragraph re-wrapped,
 * the chords on the edited row were over the wrong words while the rows
 * below (re-wrapped by character offset) were right. Chords past the end of
 * the lyrics (an ending chord) are placed by column, not by character, so
 * typing at the end of the line doesn't shove them along.
 */
private fun chordsAfterLyricsEdit(chords: String, oldLyrics: String, newLyrics: String, caret: Int, cap: Int): String {
    val delta = newLyrics.length - oldLyrics.length
    // Where the edit happened: the caret pins it down even inside a run of
    // repeated letters, where a plain text diff can't tell which one changed.
    var start = if (delta > 0) caret - delta else caret
    var oldEnd = if (delta > 0) start else caret - delta
    val caretConsistent = start in 0..oldEnd && oldEnd <= oldLyrics.length &&
        newLyrics.regionMatches(0, oldLyrics, 0, start) &&
        newLyrics.regionMatches(newLyrics.length - (oldLyrics.length - oldEnd), oldLyrics, oldEnd, oldLyrics.length - oldEnd) &&
        oldLyrics.length - oldEnd <= newLyrics.length - start
    if (!caretConsistent) {
        start = 0
        while (start < oldLyrics.length && start < newLyrics.length && oldLyrics[start] == newLyrics[start]) start++
        var suffix = 0
        while (suffix < minOf(oldLyrics.length, newLyrics.length) - start &&
            oldLyrics[oldLyrics.length - 1 - suffix] == newLyrics[newLyrics.length - 1 - suffix]
        ) suffix++
        oldEnd = oldLyrics.length - suffix
    }
    val anchors = chordsLineToAnchors(chords)
    val moved = anchors.map { a ->
        when {
            a.i < start || a.i >= oldLyrics.length -> a
            a.i >= oldEnd -> a.copy(i = a.i + delta)
            else -> a.copy(i = start)
        }
    }
    if (moved == anchors) return chords
    return renderChordAnchors(moved, padTo = minOf(newLyrics.length, cap))
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

/**
 * One logical line of lyrics -- a hard-break row plus the soft-wrap rows
 * after it -- flattened back into a single string, the way Samsung Notes (any
 * text editor) holds a wrapped line: wrapping is only how a paragraph is
 * drawn, never part of its text. Every structural edit (typing that changes
 * the wrap, Enter, backspace across a row boundary, a multi-line paste, a font
 * or width change) works on paragraphs and re-wraps them whole, so text
 * always flows between rows instead of leaving ragged half-empty ones. Chords
 * travel as anchors at paragraph-wide character offsets, so each stays above
 * the same lyric character however its paragraph is re-wrapped. A paragraph
 * is also exactly what gets saved: one [SongLine] with id [id].
 */
private class Paragraph(val id: String, val lyrics: String, val anchors: List<ChordAnchor>)

/**
 * Flattens one paragraph's [rows]. A chord typed out past the end of a row's
 * lyrics -- in the empty space a wrap leaves at the end of every row but the
 * last -- has no paragraph offset of its own (that offset is the start of
 * the next row), so it's pinned to the row's last character instead, the
 * space the wrap hung there.
 */
private fun paragraphOf(rows: List<EditorLine>): Paragraph {
    val lyrics = StringBuilder()
    val anchors = mutableListOf<ChordAnchor>()
    for ((k, row) in rows.withIndex()) {
        val offset = lyrics.length
        val lastCol = if (k < rows.lastIndex) maxOf(row.lyrics.length - 1, 0) else Int.MAX_VALUE
        chordsLineToAnchors(row.chords).mapTo(anchors) { it.copy(i = minOf(it.i, lastCol) + offset) }
        lyrics.append(row.lyrics)
    }
    return Paragraph(rows.first().paragraphId, lyrics.toString(), anchors)
}

/**
 * Start offsets of the rows [text] wraps into at [maxWidthPx] -- Android's own
 * greedy line breaker (the one Samsung Notes' text field uses), not a
 * reimplementation of it: a word that doesn't fit moves down whole, the space
 * before it hangs at the end of the row above so wrapped rows start flush,
 * and a run with no space in it breaks at the last character that fits.
 * [maxChars] also caps a row's length so its chords, which align to lyrics by
 * character position, never need more room than the chords track has (see
 * [chordCapacity]). Lyrics and chords share one glyph width, so this only
 * bites when the caret's own room at the edge pushes the last character out.
 * Spaces hanging at the end of a row are never counted, as on Android: a word
 * that exactly fills the row stays there after you type the space after it.
 */
private fun breakParagraph(text: String, style: TextStyle, maxWidthPx: Int, maxChars: Int, measurer: TextMeasurer, caretAtEnd: Boolean): List<Int> {
    val starts = mutableListOf(0)
    if (maxWidthPx <= 0) return starts
    val greedy = style.copy(lineBreak = LineBreak.Simple)
    var pos = 0
    while (pos < text.length) {
        val rest = text.substring(pos)
        val layout = measurer.measure(rest, greedy, softWrap = true, constraints = Constraints(maxWidth = maxWidthPx))
        var end = if (layout.lineCount > 1) layout.getLineEnd(0) else rest.length
        // Spaces hanging at the row's end don't count -- see HangingSpacesHidden.
        if (rest.substring(0, end).trimEnd(' ').length > maxChars) {
            val space = rest.lastIndexOf(' ', maxChars - 1)
            end = if (space > 0) space + 1 else maxChars
            while (end < rest.length && rest[end] == ' ') end++
            if (end < rest.length && rest[end].isLowSurrogate()) end--
        }
        if (end >= rest.length) {
            // The caret sits after spaces hanging past a full row (the user
            // just typed the space after the row's last word): it goes to the
            // start of a fresh row, as in any text editor -- not parked at the
            // edge until the next letter arrives, and not whisked up a row when
            // the word after the space is backspaced. Only while the caret is
            // there: anywhere else it'd just be a blank row in the song.
            if (caretAtEnd && rest.length > maxChars && rest.endsWith(' ')) starts += text.length
            break
        }
        if (end <= 0) end = if (rest.length > 1 && rest[0].isHighSurrogate()) 2 else 1
        pos += end
        starts += pos
    }
    return starts
}

/**
 * Draws a lyrics row without the spaces hanging past [capacity] at its end,
 * with a caret after them shown at the edge -- what Android does with a
 * wrapped line's trailing spaces. Drawn, they'd push the single-line field
 * past its width and make it scroll sideways to keep the caret in view.
 */
private class HangingSpacesHidden(private val capacity: Int) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val keep = maxOf(capacity, text.text.trimEnd(' ').length)
        if (text.length <= keep) return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(
            text.subSequence(0, keep),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = minOf(offset, keep)
                override fun transformedToOriginal(offset: Int): Int = offset
            },
        )
    }

    override fun equals(other: Any?): Boolean = other is HangingSpacesHidden && other.capacity == capacity
    override fun hashCode(): Int = capacity
}

/** Cuts [p] into rows at [starts]: (lyrics, chords) per row, each row's chords padded no wider than [chordCap]. */
private fun layoutParagraph(p: Paragraph, starts: List<Int>, chordCap: Int): List<Pair<String, String>> =
    cutIntoRows(p.lyrics, p.anchors, starts).map { row ->
        row.lyrics to renderChordAnchors(row.chords, padTo = minOf(row.lyrics.length, chordCap))
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongEditorScreen(songId: String, engine: AudioEngine, onDone: () -> Unit) {
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
    // The scratchpad opens as a full-screen overlay right on top of this
    // composable rather than as a separate MainActivity `screen` -- the
    // whole point (per direct product feedback) is that jumping into the
    // scratchpad to record an idea and back out to the lyrics never tears
    // down the editor underneath: undo history, unsaved-but-debounced text,
    // caret position, all of it is still exactly as it was the instant the
    // scratchpad's Close button is tapped, since this composable never left
    // composition. This flag means "is the scratchpad's full UI showing,"
    // not "does a scratchpad session exist" -- ScratchpadScreen itself is
    // ALWAYS composed below regardless of this value, specifically so an
    // in-progress recording survives hiding the UI (see its own doc comment).
    var scratchpadOpen by rememberSaveable { mutableStateOf(false) }
    // The piano moved here from the song list's header, where it sat beside
    // "Songs" as a top-level destination. It is a songwriting aid -- you reach
    // for it to find a chord for the song you are writing -- so it belongs
    // inside a song, not next to the list of them. Same overlay treatment as
    // the scratchpad: shown on top, closes back to exactly this editor state.
    var pianoOpen by rememberSaveable { mutableStateOf(false) }
    // Mirrors ScratchpadScreen's own private isRecording -- surfaced here
    // only so the BackHandler below can tell "minimized but still
    // recording" apart from "nothing going on," not general-purpose state
    // this composable otherwise reads or displays.
    var isScratchpadRecording by remember { mutableStateOf(false) }
    var fontScale by rememberSaveable { mutableStateOf(1f) }
    var linesAreaWidthPx by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // Hands-free autoscroll (the same concept as Ultimate Guitar's, not its
    // UI) -- lets someone playing an instrument keep both hands busy while
    // the page scrolls itself instead of having to reach over and swipe.
    val lazyListState = rememberLazyListState()
    var autoscrollEnabled by rememberSaveable { mutableStateOf(false) }
    var autoscrollSpeed by rememberSaveable { mutableStateOf(1f) }

    // Undo/redo over the text content only (title/meta/lines/customChords) --
    // deliberately separate from Room/the autosaver, which stay the durable
    // draft store regardless of undo history. Saved via editorSnapshotListSaver
    // (a size- and count-capped JSON encoding, not the raw objects -- see its
    // own doc comment) so history survives a fold/unfold or the process being
    // killed while backgrounded, both of which recreate this composable the
    // same as rotation would. lastHistorySnapshot deliberately stays plain
    // `remember`, not saved: it always gets recomputed fresh from
    // title/meta/lines/customChords below, so it's correct either way, and
    // saving it too would just be more Bundle bytes for no benefit.
    var undoStack by rememberSaveable(stateSaver = editorSnapshotListSaver) { mutableStateOf(listOf<EditorSnapshot>()) }
    var redoStack by rememberSaveable(stateSaver = editorSnapshotListSaver) { mutableStateOf(listOf<EditorSnapshot>()) }
    var lastHistorySnapshot by remember { mutableStateOf(EditorSnapshot(title, meta, lines, customChords)) }
    // Set right before undo()/redo() assign state so the debounced effect
    // below treats that assignment as "already historical" instead of
    // pushing it right back onto the stack it was just popped from.
    var suppressNextHistoryPush by remember { mutableStateOf(false) }

    LaunchedEffect(title, meta, lines, customChords) {
        val current = EditorSnapshot(title, meta, lines, customChords)
        if (suppressNextHistoryPush) {
            suppressNextHistoryPush = false
            lastHistorySnapshot = current
            return@LaunchedEffect
        }
        delay(HISTORY_DEBOUNCE_MS)
        if (current != lastHistorySnapshot) {
            undoStack = (undoStack + lastHistorySnapshot).takeLast(MAX_HISTORY)
            lastHistorySnapshot = current
            redoStack = emptyList()
        }
    }

    // Drives the actual scrolling, one real display-frame delta at a time
    // (withFrameNanos, not a fixed-interval delay loop) so the rate stays
    // correct regardless of the device's refresh rate. Restarts cleanly
    // whenever autoscrollEnabled/autoscrollSpeed change since both are
    // LaunchedEffect keys -- toggling off cancels this coroutine outright
    // (the `if (!autoscrollEnabled) return@LaunchedEffect` guard), so
    // there's never a stray scroll loop running after the button says off.
    val lyricsAtEnd by remember { derivedStateOf { !lazyListState.canScrollForward } }
    val lyricsCanScroll by remember { derivedStateOf { lazyListState.canScrollForward || lazyListState.canScrollBackward } }

    LaunchedEffect(autoscrollEnabled, autoscrollSpeed) {
        if (!autoscrollEnabled) return@LaunchedEffect
        // Tuned as a comfortable baseline reading pace at 1x, not derived
        // from the song's own BPM -- this scrolls prose/lyrics, not a
        // metronome, and UG's own autoscroll speed is independently tunable
        // for the same reason (a fast song's lyric density and its tempo
        // aren't the same thing).
        val basePixelsPerSecond = 18f
        var lastFrameNanos = withFrameNanos { it }
        while (isActive) {
            val frameNanos = withFrameNanos { it }
            val deltaSeconds = (frameNanos - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = frameNanos
            lazyListState.scrollBy(basePixelsPerSecond * autoscrollSpeed * deltaSeconds)
            if (!lazyListState.canScrollForward) {
                autoscrollEnabled = false
                break
            }
        }
    }

    // Per-row debounce jobs for re-wrapping after typing -- see
    // handleLyricsChange's doc comment for why this can't just run inline
    // on every keystroke.
    val splitJobs = remember { mutableMapOf<String, Job>() }
    // The lyrics row that currently has focus, so a debounced re-wrap knows
    // whether it's still allowed to place the caret (see reflowParagraphOf).
    var focusedLyricsId by remember { mutableStateOf<String?>(null) }
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
    // Registered after (so it sits higher on the dispatcher's back stack)
    // and enabled whenever there's a scratchpad state back should protect --
    // the UI showing, OR (this is the part that isn't just the mirror image
    // of opening it) a take still recording in the background after the
    // user already minimized it once. Without the second condition, a
    // second back-press while minimized+recording fell through to finish(),
    // which leaves the song entirely -- disposing this whole composable's
    // ScratchpadScreen child and hitting its DisposableEffect's "abandon an
    // in-progress recording" safety net, silently DROPPING the take rather
    // than stopping it cleanly. Caught by testing this exact sequence
    // on-device. `!scratchpadOpen` as the action (not a hardcoded `true`) is
    // deliberate: the only two states this handler is ever enabled for are
    // "showing" (toggle closes it) and "hidden but recording" (toggle
    // re-opens it so Stop is reachable) -- never both false, since then this
    // whole handler is disabled and back correctly falls through to finish().
    BackHandler(enabled = scratchpadOpen || isScratchpadRecording) { scratchpadOpen = !scratchpadOpen }

    val density = LocalDensity.current
    val lyricStyle = baseLyricTextStyle.copy(fontSize = baseLyricTextStyle.fontSize * fontScale)
    // Chords sit above lyrics by character column, so both tracks must share
    // one glyph width or a chord drifts off its word further along the line
    // (chords used to be monospace over proportional lyrics, and did). Both
    // are monospace now; the chords, a size smaller and bold, get exactly the
    // letter spacing that makes up the difference, measured rather than
    // assumed since the system monospace font varies by device.
    val glyphWidthPx = remember(fontScale, density) { monospaceAdvance(lyricStyle, textMeasurer) }
    val chordStyle = remember(fontScale, density, glyphWidthPx) {
        val unspaced = baseChordTextStyle.copy(fontSize = baseChordTextStyle.fontSize * fontScale, letterSpacing = 0.sp)
        val gapPx = glyphWidthPx - monospaceAdvance(unspaced, textMeasurer)
        unspaced.copy(letterSpacing = with(density) { gapPx.toSp() })
    }
    // The caret is drawn past the last glyph; leave room for it, or a chord
    // line exactly at capacity would still scroll by the caret's width.
    val caretSlackPx = with(density) { 2.dp.roundToPx() } + 1
    val chordCap = remember(linesAreaWidthPx, glyphWidthPx, caretSlackPx) {
        chordCapacity(glyphWidthPx, linesAreaWidthPx, caretSlackPx)
    }

    fun currentSong(): Song = Song(
        id = songId,
        title = title,
        meta = meta,
        lines = songLinesOf(lines),
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

    fun undo() {
        val prev = undoStack.lastOrNull() ?: return
        redoStack = redoStack + EditorSnapshot(title, meta, lines, customChords)
        undoStack = undoStack.dropLast(1)
        suppressNextHistoryPush = true
        title = prev.title
        meta = prev.meta
        lines = prev.lines
        customChords = prev.customChords
        persist()
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        undoStack = undoStack + EditorSnapshot(title, meta, lines, customChords)
        redoStack = redoStack.dropLast(1)
        suppressNextHistoryPush = true
        title = next.title
        meta = next.meta
        lines = next.lines
        customChords = next.customChords
        persist()
    }

    fun updateLine(id: String, transform: (EditorLine) -> EditorLine) {
        lines = lines.map { if (it.id == id) transform(it) else it }
        persist()
    }

    /**
     * Transposes each chord in place. Done per anchor rather than over the
     * padded string, which would shift every later chord one column right
     * each time a name got longer (F -> F#) and could push a full chord line
     * past [chordCap]. Each token still goes through [transposeChordsLine] so
     * non-chords (section labels, typos) are left alone, same as before.
     */
    fun transposeAll(semitones: Int) {
        lines = lines.map { line ->
            val anchors = chordsLineToAnchors(line.chords).map { it.copy(c = transposeChordsLine(it.c, semitones) ?: it.c) }
            line.copy(chords = renderChordAnchors(anchors, padTo = minOf(line.lyrics.length, chordCap)))
        }
        persist()
    }

    /** Indices of the rows of the paragraph containing `lines[idx]`. */
    fun paragraphRange(idx: Int): IntRange {
        val pid = lines[idx].paragraphId
        var start = idx
        while (start > 0 && lines[start - 1].paragraphId == pid) start--
        var end = idx
        while (end + 1 < lines.size && lines[end + 1].paragraphId == pid) end++
        return start..end
    }

    /** Where row `lines[idx]` starts within its paragraph's flattened lyrics. */
    fun offsetInParagraph(range: IntRange, idx: Int): Int = (range.first until idx).sumOf { lines[it].lyrics.length }

    /**
     * Wraps [paragraphs] into rows for the current width. [caret] is
     * (paragraph index, offset within it); a caret sitting exactly on a row
     * boundary belongs to the start of the next row, which is where Android
     * draws it too. The caret's row gets [focusedId] -- the row being typed in
     * keeps its identity, and therefore its focus, while text moves between
     * rows around it (handing focus to a different composable mid-typing is
     * what used to fork keystrokes across two rows). Every other row reuses
     * [oldIds] in order. Returns the rows and where the caret landed.
     */
    fun buildRows(oldIds: List<String>, paragraphs: List<Paragraph>, caret: Pair<Int, Int>?, focusedId: String?): Pair<List<EditorLine>, PendingFocus?> {
        val built = mutableListOf<Triple<String, String, String>>()
        var caretRow = -1
        var caretLocal = 0
        for ((pi, p) in paragraphs.withIndex()) {
            val caretAtEnd = caret != null && caret.first == pi && caret.second >= p.lyrics.length
            val starts = breakParagraph(p.lyrics, lyricStyle, linesAreaWidthPx, chordCap, textMeasurer, caretAtEnd)
            if (caret != null && caret.first == pi) {
                val k = starts.indexOfLast { it <= caret.second }
                caretRow = built.size + k
                caretLocal = caret.second - starts[k]
            }
            layoutParagraph(p, starts, chordCap).forEach { (lyrics, chords) -> built += Triple(p.id, lyrics, chords) }
        }
        val keepFocusedId = focusedId != null && caretRow >= 0
        val pool = ArrayDeque(if (keepFocusedId) oldIds.filter { it != focusedId } else oldIds)
        val rows = built.mapIndexed { r, (paragraphId, lyrics, chords) ->
            val id = if (keepFocusedId && r == caretRow) focusedId!! else pool.removeFirstOrNull() ?: UUID.randomUUID().toString()
            EditorLine(id, paragraphId, chords, lyrics)
        }
        return rows to (if (caretRow >= 0) PendingFocus(rows[caretRow].id, caretLocal) else null)
    }

    /**
     * Swaps `lines[range]` -- always whole paragraphs -- for [paragraphs]
     * re-wrapped; see [buildRows]. A true no-op when no text moved between
     * rows (chord padding alone doesn't count): the debounced re-wrap lands
     * here after every typing pause, and needlessly rewriting the row and
     * re-placing the caret gave a keystroke arriving in that same frame a
     * stale caret to type at. When something did move, the caret is always
     * re-placed -- the focused field resyncs to its row's new text with the
     * caret at the end, so leaving it there would send the next keystroke to
     * the end of the row.
     */
    fun replaceParagraphs(range: IntRange, paragraphs: List<Paragraph>, caret: Pair<Int, Int>?, focusedId: String?) {
        val old = lines.slice(range)
        val (rows, focus) = buildRows(old.map { it.id }, paragraphs, caret, focusedId)
        val unchanged = rows.size == old.size && rows.indices.all { i ->
            rows[i].id == old[i].id && rows[i].lyrics == old[i].lyrics && rows[i].paragraphId == old[i].paragraphId &&
                chordsLineToAnchors(rows[i].chords) == chordsLineToAnchors(old[i].chords)
        }
        if (unchanged) return
        lines = lines.subList(0, range.first) + rows + lines.subList(range.last + 1, lines.size)
        if (focus != null) pendingFocus = focus
        persist()
    }

    /** A structural edit is about to re-wrap paragraphs from the current text; a debounced re-wrap queued before it would only fight it. */
    fun cancelPendingReflows() {
        splitJobs.values.forEach { it.cancel() }
        splitJobs.clear()
    }

    /**
     * Enter/Next in a lyrics row: splits the paragraph at the caret, like any
     * text editor -- everything after the caret becomes a new paragraph below
     * (empty when the caret was at the end), saved as a new line. The new
     * paragraph's first row takes over the focused row's id so focus never
     * has to move.
     */
    fun handleEnterFromLyrics(lineId: String, caretIndex: Int) {
        cancelPendingReflows()
        val idx = lines.indexOfFirst { it.id == lineId }
        if (idx == -1) return
        val range = paragraphRange(idx)
        val p = paragraphOf(lines.slice(range))
        val at = (offsetInParagraph(range, idx) + caretIndex).coerceIn(0, p.lyrics.length)
        val before = Paragraph(p.id, p.lyrics.substring(0, at), p.anchors.filter { it.i < at })
        val after = Paragraph(UUID.randomUUID().toString(), p.lyrics.substring(at), p.anchors.filter { it.i >= at }.map { it.copy(i = it.i - at) })
        replaceParagraphs(range, listOf(before, after), caret = 1 to 0, focusedId = lineId)
    }

    /**
     * Backspace with the caret at the very start of a lyrics row, matching
     * Samsung Notes (checked side by side on-device):
     * - At the start of a soft-wrapped row, the row boundary isn't a real
     *   line break -- it's the same spot as the end of the row above, so
     *   backspace deletes the character before it (normally the space the
     *   wrap hung there), and the paragraph re-wraps.
     * - At the start of a real line, it joins that whole line onto the end
     *   of the line above, with nothing added or removed between them --
     *   which is also how an empty line gets deleted.
     * - At the start of the first line, nothing happens.
     * Immediate, not debounced: a single backspace is a one-shot event with
     * nothing in flight to race against.
     */
    fun handleLyricsBackspaceAtStart(lineId: String) {
        cancelPendingReflows()
        val idx = lines.indexOfFirst { it.id == lineId }
        if (idx <= 0) return
        if (lines[idx - 1].paragraphId == lines[idx].paragraphId) {
            val range = paragraphRange(idx)
            val p = paragraphOf(lines.slice(range))
            val at = offsetInParagraph(range, idx)
            if (at == 0) return
            val del = if (at >= 2 && p.lyrics[at - 1].isLowSurrogate() && p.lyrics[at - 2].isHighSurrogate()) 2 else 1
            // Chords after the deleted character shift with the text they sit
            // above, so the rows below keep their chords lined up.
            val anchors = p.anchors.map { if (it.i >= at) it.copy(i = it.i - del) else it }
            replaceParagraphs(range, listOf(Paragraph(p.id, p.lyrics.removeRange(at - del, at), anchors)), 0 to at - del, lineId)
        } else {
            val prevRange = paragraphRange(idx - 1)
            val currRange = paragraphRange(idx)
            val above = paragraphOf(lines.slice(prevRange))
            val below = paragraphOf(lines.slice(currRange))
            // A chord sitting past the end of the line above's lyrics (an
            // ending chord, or a chords-only line) keeps its place: the joined
            // line's chords start after it rather than landing on top of it.
            val chordShift = maxOf(above.lyrics.length, above.anchors.maxOfOrNull { it.i + it.c.length } ?: 0)
            val joined = Paragraph(above.id, above.lyrics + below.lyrics, above.anchors + below.anchors.map { it.copy(i = it.i + chordShift) })
            replaceParagraphs(prevRange.first..currRange.last, listOf(joined), 0 to above.lyrics.length, lineId)
        }
    }

    /**
     * Re-wraps the whole paragraph [lineId] belongs to, from its current text
     * -- so typing in the middle of a wrapped paragraph pushes words down into
     * the next row (and deleting pulls them back up), instead of only ever
     * splitting the one row that was edited. The caret only follows along if
     * that row still has focus; if the user has already moved on to another
     * field, re-wrapping must not drag focus back.
     */
    fun reflowParagraphOf(lineId: String, caretIndex: Int) {
        val idx = lines.indexOfFirst { it.id == lineId }
        if (idx == -1) return
        val range = paragraphRange(idx)
        val stillFocused = focusedLyricsId == lineId
        val caret = if (stillFocused) 0 to offsetInParagraph(range, idx) + caretIndex else null
        replaceParagraphs(range, listOf(paragraphOf(lines.slice(range))), caret, if (stillFocused) lineId else null)
    }

    /**
     * Re-wraps every paragraph for the current width and font size. Nothing
     * else re-runs wrapping when the rendering context changes rather than
     * the text: without this, a line that fit at 1.0x sat clipped mid-word off
     * the right edge after A+, and a chord line that fit before could now
     * scroll. Layout-only, so it isn't an undo step, and it only writes to
     * storage when the saved form ([songLinesOf]) actually changed, which
     * wrapping alone never does.
     */
    fun reflowAllLines() {
        val rebuilt = mutableListOf<EditorLine>()
        var i = 0
        while (i < lines.size) {
            val range = paragraphRange(i)
            rebuilt += buildRows(lines.slice(range).map { it.id }, listOf(paragraphOf(lines.slice(range))), null, null).first
            i = range.last + 1
        }
        if (rebuilt == lines) return
        val storedFormChanged = songLinesOf(rebuilt) != songLinesOf(lines)
        suppressNextHistoryPush = true
        lines = rebuilt
        if (storedFormChanged) persist()
    }

    /**
     * The first wrap after opening a song, built from the saved chord anchors
     * themselves rather than from the placeholder rows. Those rows hold a
     * whole paragraph's chords as one string, where a chord ending one row and
     * a chord starting the next are neighbours that collide -- and re-reading
     * that string nudged the second one sideways, so a chord moved every time
     * the song was reopened. Split into rows first, they never meet.
     */
    var loadedSongWrapped by remember { mutableStateOf(false) }
    fun wrapLoadedSong() {
        loadedSongWrapped = true
        if (loaded.lines.isEmpty()) return
        val paragraphs = loaded.lines.map { Paragraph(it.id, it.lyrics, it.chords) }
        suppressNextHistoryPush = true
        lines = buildRows(lines.map { it.id }, paragraphs, null, null).first
    }

    LaunchedEffect(linesAreaWidthPx, fontScale) {
        if (linesAreaWidthPx <= 0) return@LaunchedEffect
        if (loadedSongWrapped) reflowAllLines() else wrapLoadedSong()
    }

    // "+ Add line" sits right under the last row, but when the keyboard comes
    // up Compose only scrolls the focused field itself into view, leaving the
    // button hidden just below the keyboard's edge. While the last row is being
    // typed in, keep the list scrolled to its end -- where the button is --
    // following the keyboard as it animates in and the paragraph as it wraps
    // onto new rows. Only on those changes, so it never fights a manual scroll.
    val typingOnLastRow = WindowInsets.isImeVisible && focusedLyricsId != null && focusedLyricsId == lines.lastOrNull()?.id
    LaunchedEffect(typingOnLastRow, lines.size) {
        if (!typingOnLastRow) return@LaunchedEffect
        snapshotFlow { lazyListState.layoutInfo.viewportSize.height }.collect {
            lazyListState.scrollToItem(lines.size)
        }
    }

    /**
     * Every edit to a lyrics row. A paste containing newlines (the row's field
     * is `singleLine`, so they'd otherwise sit invisibly inside one row) splits
     * into paragraphs right away -- a paste is a one-shot event, and the caret
     * lands at the end of what was pasted.
     *
     * Ordinary typing updates the row's text immediately, so nothing typed is
     * ever lost, but re-wrapping its paragraph waits for [SPLIT_DEBOUNCE_MS] of
     * quiet. Re-wrapping on every keystroke was reproduced on-device forking
     * keystrokes across two rows: the field's own `TextFieldValue` only catches
     * up to restructured `lines` a frame later, and a keystroke landing in that
     * gap applied to the stale text.
     */
    fun handleLyricsChange(line: EditorLine, updatedLyrics: String, caretIndex: Int) {
        if (updatedLyrics.contains('\n')) {
            cancelPendingReflows()
            val idx = lines.indexOfFirst { it.id == line.id }
            if (idx == -1) return
            val range = paragraphRange(idx)
            val rows = lines.slice(range).map { if (it.id == line.id) it.copy(lyrics = updatedLyrics) else it }
            val p = paragraphOf(rows)
            val caretOffset = offsetInParagraph(range, idx) + caretIndex
            val segments = p.lyrics.split('\n')
            val paragraphs = mutableListOf<Paragraph>()
            var caret: Pair<Int, Int>? = null
            var segStart = 0
            segments.forEachIndexed { k, seg ->
                val segEnd = segStart + seg.length
                val anchors = p.anchors
                    .filter { it.i >= segStart && (k == segments.lastIndex || it.i <= segEnd) }
                    .map { it.copy(i = it.i - segStart) }
                paragraphs += Paragraph(if (k == 0) p.id else UUID.randomUUID().toString(), seg, anchors)
                if (caret == null && caretOffset <= segEnd) caret = k to (caretOffset - segStart).coerceAtLeast(0)
                segStart = segEnd + 1
            }
            replaceParagraphs(range, paragraphs, caret, line.id)
            return
        }

        updateLine(line.id) { it.copy(lyrics = updatedLyrics, chords = chordsAfterLyricsEdit(it.chords, it.lyrics, updatedLyrics, caretIndex, chordCap)) }

        splitJobs[line.id]?.cancel()
        splitJobs[line.id] = scope.launch {
            delay(SPLIT_DEBOUNCE_MS)
            splitJobs.remove(line.id)
            reflowParagraphOf(line.id, caretIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ParchmentBg),
    ) {
        // Top bar: Export (left, scrolls independently if it doesn't fit)
        // and Done (right, always fully visible -- never shrunk or pushed
        // off by Export). Own row with real clearance from the status bar
        // — same "actions on top, title below with room to breathe" rhythm
        // Samsung Notes/Keep/Apple Notes all use, rather than cramming the
        // title into the same row as an action button flush against the
        // top edge. statusBarsPadding() is load-bearing, not decorative:
        // targetSdk 36 (Android 15+) enforces edge-to-edge with no opt-out,
        // and this screen otherwise has zero WindowInsets handling, so
        // without it Done draws under the status bar/battery icon on some
        // devices — reported directly after live testing. The extra top
        // padding beyond that inset is deliberate breathing room, not just
        // the bare minimum to clear the icons.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { copySongTextToClipboard(context, currentSong()) }) { Text("Copy", color = ChordColor) }
                TextButton(onClick = { shareSongAsText(context, currentSong()) }) { Text(".txt", color = ChordColor) }
                TextButton(onClick = { shareSongAsPdf(context, currentSong()) }) { Text("PDF", color = ChordColor) }
            }
            // A labeled button, not a bare icon -- GraphicEq alone tested as
            // "pretty random" (direct feedback): nothing about a graphic-
            // equalizer glyph reads as "record a quick idea for this song"
            // at a glance. Mic + a real word fixes that without needing to
            // invent a new icon.
            TextButton(onClick = { scratchpadOpen = true }) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = ChordColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Scratchpad", color = ChordColor)
            }
            // Icon-only, unlike Scratchpad above: the argument there was that
            // a graphic-equalizer glyph says nothing about "record an idea".
            // A keyboard reads as "piano" on sight, and this row is already
            // carrying two labelled buttons.
            IconButton(onClick = { pianoOpen = true }) {
                Icon(Icons.Filled.Piano, contentDescription = "Piano", tint = ChordColor, modifier = Modifier.size(20.dp))
            }
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
            TextButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) {
                Text("Undo", color = if (undoStack.isNotEmpty()) ChordColor else TextMuted)
            }
            TextButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) {
                Text("Redo", color = if (redoStack.isNotEmpty()) ChordColor else TextMuted)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { transposeAll(-1) }) { Text("Transpose -1", color = ChordColor) }
            TextButton(onClick = { transposeAll(1) }) { Text("Transpose +1", color = ChordColor) }
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
            state = lazyListState,
            // Room past the last row to scroll it, and "+ Add line", clear of
            // the autoscroll button floating over the bottom corner (hidden
            // while the keyboard is up, so no room needed then).
            contentPadding = PaddingValues(bottom = if (WindowInsets.isImeVisible) 8.dp else AUTOSCROLL_BUTTON_CLEARANCE),
            modifier = Modifier
                .fillMaxSize()
                // Edge-to-edge: without this the list ran on under the system
                // navigation bar, and the last row and "+ Add line" sat behind
                // its Back/Home buttons where they couldn't be tapped. (While
                // the keyboard is up, the root's imePadding already covers it.)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { linesAreaWidthPx = it.size.width }
                // Any manual touch on the lyrics themselves cancels
                // autoscroll -- reaching in to nudge/correct the scroll
                // position by hand implicitly means "I'll take it from
                // here," the same assumption UG's own autoscroll makes.
                // requireUnconsumed = false and never calling .consume()
                // means this never interferes with the LazyColumn's own
                // normal scroll/tap handling underneath.
                .then(
                    if (autoscrollEnabled) {
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                autoscrollEnabled = false
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            itemsIndexed(lines, key = { _, line -> line.id }) { index, line ->
                LineRow(
                    line = line,
                    latestLine = { lines.firstOrNull { it.id == line.id } },
                    chordStyle = chordStyle,
                    lyricStyle = lyricStyle,
                    chordCapacity = chordCap,
                    glyphWidthPx = glyphWidthPx,
                    wrapsOntoNextRow = lines.getOrNull(index + 1)?.paragraphId == line.paragraphId,
                    isWrappedRow = lines.getOrNull(index - 1)?.paragraphId == line.paragraphId,
                    customChords = customChords,
                    onTapChord = { name -> activeChordName = name },
                    pendingFocus = pendingFocus,
                    onConsumedPendingFocus = { pendingFocus = null },
                    onChordsChange = { updated -> updateLine(line.id) { it.copy(chords = updated) } },
                    onLyricsChange = { updated, caret -> handleLyricsChange(line, updated, caret) },
                    onLyricsFocusChanged = { focused ->
                        if (focused) {
                            focusedLyricsId = line.id
                        } else if (focusedLyricsId == line.id) {
                            focusedLyricsId = null
                            // The empty row a trailing space opened for the caret
                            // (see breakParagraph) goes once the caret leaves it.
                            if (lines.firstOrNull { it.id == line.id }?.lyrics?.isEmpty() == true) reflowParagraphOf(line.id, 0)
                        }
                    },
                    onEnter = { caret -> handleEnterFromLyrics(line.id, caret) },
                    onBackspaceAtStart = { handleLyricsBackspaceAtStart(line.id) },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        val newLine = newEditorLine()
                        lines = lines + newLine
                        pendingFocus = PendingFocus(newLine.id)
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

    // Drawn before ScratchpadScreen/ChordVoicingPanel below so either one's
    // full-screen surface naturally covers this button when showing --
    // never needs its own extra "hide me" condition for that. Hidden while
    // the keyboard is up: the screen ends at the keyboard, so the button would
    // ride up over the row being typed in, and autoscroll is for playing, not
    // writing.
    if (!WindowInsets.isImeVisible) {
        AutoscrollControls(
            enabled = autoscrollEnabled,
            atEnd = lyricsAtEnd,
            canScroll = lyricsCanScroll,
            onToggle = {
                if (autoscrollEnabled) {
                    autoscrollEnabled = false
                } else {
                    scope.launch {
                        // Nothing left below: play from the top again, like
                        // replaying a track, rather than starting and
                        // stopping on the same frame (which only flashed the
                        // speed pill up and away).
                        if (!lazyListState.canScrollForward) lazyListState.animateScrollToItem(0)
                        autoscrollEnabled = true
                    }
                }
            },
            speed = autoscrollSpeed,
            onSpeedChange = { autoscrollSpeed = it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }

    // Unconditional -- NOT `if (scratchpadOpen)` -- so a take can keep
    // recording in the background while this composable's `visible = false`
    // just hides its UI (see ScratchpadScreen's own doc comment for the full
    // reasoning). `scratchpadOpen` now means "is the full scratchpad UI on
    // top," not "does a scratchpad session exist at all."
    // Registered after the scratchpad's handlers so it sits higher on the
    // back stack and closes the piano first when both could apply.
    BackHandler(enabled = pianoOpen) { pianoOpen = false }
    if (pianoOpen) {
        PianoScreen(engine = engine, onDone = { pianoOpen = false })
    }

    ScratchpadScreen(
        engine = engine,
        songId = songId,
        visible = scratchpadOpen,
        songBpm = meta.bpm,
        onRecordingChanged = { isScratchpadRecording = it },
        onDone = { scratchpadOpen = false },
        onExpand = { scratchpadOpen = true },
    )
    }
}

private val baseChordTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.4.sp,
)
// Composable, unlike baseChordTextStyle above, purely because it carries a
// colour and colours are now theme-dependent -- the chord style gets its colour
// applied at each use site instead, so it can stay a plain constant.
// Monospace so chord columns line up with lyric characters (the editor gives
// the chords letter spacing to match this font's glyph width exactly); 14sp
// rather than the 15sp it was as a proportional font, since monospace runs
// wider and every glyph costs a little of the row.
private val baseLyricTextStyle: TextStyle
    @Composable get() = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        color = LyricColor,
    )

@Composable
private fun LineRow(
    line: EditorLine,
    latestLine: () -> EditorLine?,
    chordStyle: TextStyle,
    lyricStyle: TextStyle,
    chordCapacity: Int,
    glyphWidthPx: Float,
    wrapsOntoNextRow: Boolean,
    isWrappedRow: Boolean,
    customChords: Map<String, ChordVoicing>,
    onTapChord: (String) -> Unit,
    pendingFocus: PendingFocus?,
    onConsumedPendingFocus: () -> Unit,
    onChordsChange: (String) -> Unit,
    onLyricsChange: (String, caretIndex: Int) -> Unit,
    onLyricsFocusChanged: (Boolean) -> Unit,
    onEnter: (caretIndex: Int) -> Unit,
    onBackspaceAtStart: () -> Unit,
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

    // Pull external changes (a re-wrap, undo) into the fields. Compares against
    // the row as it is *now*, not the `line` this effect launched with: in a
    // quick burst, the effect launched for keystroke k can run after keystroke
    // k+1 already landed, and comparing against its own stale snapshot reset
    // the field to text k -- silently dropping k+1.
    LaunchedEffect(line.chords) {
        val now = latestLine()?.chords ?: return@LaunchedEffect
        if (chordsField.text != now) chordsField = TextFieldValue(now, TextRange(now.length))
    }
    LaunchedEffect(line.lyrics) {
        val now = latestLine()?.lyrics ?: return@LaunchedEffect
        if (lyricsField.text != now) lyricsField = TextFieldValue(now, TextRange(now.length))
    }
    LaunchedEffect(pendingFocus) {
        val pf = pendingFocus ?: return@LaunchedEffect
        if (pf.lineId != line.id) return@LaunchedEffect
        lyricsFocus.requestFocus()
        pf.caretIndex?.let { lyricsField = lyricsField.copy(selection = TextRange(it.coerceIn(0, lyricsField.text.length))) }
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

    // A placeholder is a prompt to start typing; once the caret is in the
    // field it has done its job, and leaving it up means the caret is drawn
    // straight through the first glyph -- the "C" of Chords and the "L" of
    // Lyrics both sit at x=0, exactly where the caret parks. Reported as the
    // letters looking broken while editing; they were, by a bar through them.
    var chordsFocused by remember { mutableStateOf(false) }
    var lyricsFocused by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Chord track: plain text field while editing, colored token row otherwise.
        if (chordEditMode) {
            BasicTextField(
                value = chordsField,
                onValueChange = { candidate ->
                    val accepted = capChordInput(chordsField, suppressDoubleSpacePeriod(chordsField, candidate), chordCapacity)
                    val textChanged = accepted.text != chordsField.text
                    chordsField = accepted
                    if (textChanged) onChordsChange(accepted.text)
                },
                textStyle = chordStyle.copy(color = ChordColor),
                // Without this the caret is BasicTextField's default black,
                // which on the Cozy Dark parchment is all but invisible.
                cursorBrush = SolidColor(ChordColor),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onNext = { lyricsFocus.requestFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp)
                    .padding(vertical = 1.dp)
                    .focusRequester(chordsFocus)
                    .onFocusChanged { focusState ->
                        chordsFocused = focusState.isFocused
                        if (focusState.isFocused) {
                            chordFieldHasGainedFocus = true
                        } else if (chordFieldHasGainedFocus) {
                            chordEditMode = false
                            chordFieldHasGainedFocus = false
                        }
                    },
                // Deliberately no backspace-at-start handling: a chord line never
                // joins the line above, so backspacing stops at column 0.
                decorationBox = { inner ->
                    // Bottom-aligned to match ChordTokenRow, which this field
                    // swaps places with on tap. The row is a Row with
                    // verticalAlignment = Bottom inside the same 24.dp min
                    // height, so a top-aligned field made the chord text jump
                    // up ~17px the instant you tapped it and drop back on
                    // blur. Bottom is also the right resting place: chords
                    // want to sit close to the lyric line they annotate.
                    Box(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (chordsField.text.isEmpty() && !chordsFocused) {
                            Text("Chords…", style = chordStyle.copy(color = TextMuted))
                        }
                        inner()
                    }
                },
            )
        } else {
            ChordTokenRow(
                text = line.chords,
                style = chordStyle,
                // A wrapped row is the rest of the line above, not a line of its
                // own to prompt for -- the empty row a trailing space opens
                // would otherwise read as a blank new line.
                showPlaceholder = !isWrappedRow,
                customChords = customChords,
                onClick = { x ->
                    // Caret at the tapped column, like tapping into any text
                    // field -- not parked at the end of the alignment padding,
                    // which on a full-width line is past where anything fits.
                    val column = (x / glyphWidthPx).roundToInt().coerceIn(0, chordsField.text.length)
                    chordsField = chordsField.copy(selection = TextRange(column))
                    chordEditMode = true
                },
                onTapChord = onTapChord,
            )
        }

        BasicTextField(
            value = lyricsField,
            onValueChange = { new ->
                // A row that wraps onto the next one ends in the space the wrap
                // hung there. Tapping past the end of the row must put the caret
                // before that space, as Android does -- after it is logically the
                // start of the next row's first word, so typing there would glue
                // onto that word.
                val caretPastHangingSpace = wrapsOntoNextRow && new.text == lyricsField.text && new.selection.collapsed &&
                    new.selection.start == new.text.length && new.text.endsWith(' ')
                val value = if (caretPastHangingSpace) new.copy(selection = TextRange(new.text.length - 1)) else new
                lyricsField = value
                onLyricsChange(value.text, value.selection.start)
            },
            textStyle = lyricStyle,
            visualTransformation = HangingSpacesHidden(chordCapacity),
            cursorBrush = SolidColor(LyricColor),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onEnter(lyricsField.selection.start) }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .padding(vertical = 1.dp)
                .focusRequester(lyricsFocus)
                .onFocusChanged {
                    lyricsFocused = it.isFocused
                    onLyricsFocusChanged(it.isFocused)
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                        lyricsField.selection.start == 0 && lyricsField.selection.end == 0
                    ) {
                        onBackspaceAtStart()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                if (line.lyrics.isEmpty() && !lyricsFocused && !isWrappedRow) {
                    Text("Lyrics…", style = lyricStyle.copy(color = TextMuted))
                }
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
    showPlaceholder: Boolean,
    customChords: Map<String, ChordVoicing>,
    onClick: (x: Float) -> Unit,
    onTapChord: (String) -> Unit,
) {
    val tokens = remember(text, customChords) { tokenizeChordLine(text, customChords) }
    val currentOnClick by rememberUpdatedState(onClick)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            // A tap (rather than .clickable) so the edit field can open with
            // its caret at the tapped column. Taps on a chord token itself are
            // consumed by that token's own clickable and open its diagram.
            .pointerInput(Unit) { detectTapGestures { currentOnClick(it.x) } }
            .semantics { onClick(label = "Edit chords") { currentOnClick(Float.MAX_VALUE); true } }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (tokens.isEmpty()) {
            if (showPlaceholder) Text("Chords…", style = style.copy(color = TextMuted))
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
                    // A line saved wider than today's limit (older data, or a
                    // narrower screen) is clipped rather than wrapping tokens.
                    softWrap = false,
                    maxLines = 1,
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
 * Autoscroll's whole UI footprint: a round toggle button (Play while off,
 * Pause while on, Replay at the end of the song -- it scrolls back to the top
 * and plays from there -- and greyed out when the whole song fits on screen
 * with nothing to scroll), with a small speed pill above it that only appears while
 * running -- a stepped +/- rather than a drag slider, matching the same
 * "tap targets, not fine gestures" language [TimelineZoomControls] in
 * `ScratchpadScreen.kt` already established for a very similar "adjust a
 * number in small steps" control. Deliberately no persistent settings
 * screen for this: the concept UG's autoscroll is chasing is "one tap
 * before I start playing," not a configuration surface.
 */
@Composable
private fun AutoscrollControls(
    enabled: Boolean,
    atEnd: Boolean,
    canScroll: Boolean,
    onToggle: () -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        if (enabled) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(
                        enabled = speed > 0.25f,
                        onClick = { onSpeedChange((speed - 0.25f).coerceAtLeast(0.25f)) },
                    ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                    Text(
                        "%.2fx".format(speed),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(42.dp),
                    )
                    IconButton(
                        enabled = speed < 3f,
                        onClick = { onSpeedChange((speed + 0.25f).coerceAtMost(3f)) },
                    ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        FilledIconButton(onClick = onToggle, enabled = enabled || canScroll) {
            Icon(
                when {
                    enabled -> Icons.Filled.Pause
                    atEnd && canScroll -> Icons.Filled.Replay
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = when {
                    enabled -> "Stop autoscroll"
                    atEnd && canScroll -> "Autoscroll from the top"
                    else -> "Start autoscroll"
                },
            )
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
