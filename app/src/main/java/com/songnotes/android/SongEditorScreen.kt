package com.songnotes.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.data.SongRepository
import com.songnotes.core.domain.ChordBarre
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

/** How long a lyrics line must sit still before the width-triggered auto-wrap (or its reverse, auto-merge) runs — see [handleLyricsChange]. */
private const val SPLIT_DEBOUNCE_MS = 180L

/**
 * How long editing must pause before the current text state is committed as
 * one undo step — matches the "coalesce a run of typing into a single undo"
 * behavior of most text editors, rather than pushing a new stack frame on
 * every keystroke (which would make Undo only ever step back one character).
 */
private const val HISTORY_DEBOUNCE_MS = 600L
private const val MAX_HISTORY = 100

/**
 * [hardBreak] distinguishes a real line boundary (the user pressed
 * Enter/Next, pasted a literal newline, tapped "+ Add line", or this line
 * simply came from the saved song) from a boundary [wrapLineByWidth] created
 * purely because a line overflowed the screen width. Defaults to true --
 * only the continuation piece(s) [wrapLineByWidth] splits off get it set to
 * false. [performReflowIfNeeded] uses this to decide whether it's safe to
 * silently re-merge a line into the one above it just because the combined
 * text would now fit: safe for a soft wrap shrinking back down, wrong for
 * two lines the user (or the song) deliberately kept separate -- without
 * this, any two adjacent short-enough lines got silently merged the moment
 * either was edited, e.g. hitting Next to start a new line and typing into
 * it merged it straight back into the previous line.
 */
private data class EditorLine(val id: String, val chords: String, val lyrics: String, val hardBreak: Boolean = true)
private enum class Track { Chords, Lyrics }
private data class PendingFocus(val lineId: String, val track: Track, val caretIndex: Int? = null)

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
        linesArr.put(JSONObject().put("id", line.id).put("chords", line.chords).put("lyrics", line.lyrics))
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
        EditorLine(id = lineObj.getString("id"), chords = lineObj.getString("chords"), lyrics = lineObj.getString("lyrics"))
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
 * Unlike the lyrics track, the chords track has no width-based wrap/reflow —
 * it's a run of chord names with alignment spacing, not prose, so there's no
 * sensible word-boundary or per-character split point to carry a chord onto
 * a second line. Without a cap, a very long line of chords (or, worse, a
 * large accidental paste — e.g. an entire clipboard's worth of text landing
 * in the wrong field) just grows the single-line field indefinitely, forcing
 * it to auto-scroll horizontally to keep the caret visible with no way back
 * except backspacing it all out again. Capping the raw length here is
 * simpler and more predictable than trying to wrap chords the way lyrics
 * wrap: input simply stops being accepted past the limit, same as a form
 * field's maxlength — never scrolls, never spills onto another line.
 */
private const val MAX_CHORDS_LENGTH = 60

private fun TextFieldValue.capLength(max: Int): TextFieldValue {
    if (text.length <= max) return this
    return TextFieldValue(text.take(max), TextRange(selection.end.coerceIn(0, max)))
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
 * [secondHardBreak] defaults to false (a pure wrap continuation, [wrapLineByWidth]'s
 * only use of this) -- pass true for an explicit user-invoked split (Enter/Next
 * mid-line, see [handleEnterFromLyrics]), where the tail is a real line the
 * user deliberately started, not a wrap artifact eligible for silent re-merging.
 */
private fun splitLineAt(line: EditorLine, splitIndex: Int, secondHardBreak: Boolean = false): Pair<EditorLine, EditorLine> {
    val lyrics1 = line.lyrics.sliceSafe(0, splitIndex)
    val lyrics2 = line.lyrics.sliceSafe(splitIndex)
    val chords1 = line.chords.sliceSafe(0, splitIndex)
    val chords2 = line.chords.sliceSafe(splitIndex)
    val first = EditorLine(line.id, alignChordsWithLyrics(chords1, lyrics1), lyrics1, hardBreak = line.hardBreak)
    val second = EditorLine(UUID.randomUUID().toString(), chords2, lyrics2, hardBreak = secondHardBreak)
    return first to second
}

private fun mergeWithPrevious(prev: EditorLine, curr: EditorLine): EditorLine {
    val alignedPrevChords = alignChordsWithLyrics(prev.chords, prev.lyrics)
    val mergedChords = alignedPrevChords + curr.chords
    val mergedLyrics = prev.lyrics + curr.lyrics
    return EditorLine(prev.id, alignChordsWithLyrics(mergedChords, mergedLyrics), mergedLyrics, hardBreak = prev.hardBreak)
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
     * Splits the line at [caretIndex] -- same as pressing Enter mid-text in any
     * other editor, not just a "start a blank line after this one" shortcut.
     * Everything from the caret onward moves to the new line below (empty
     * when the caret was already at the end, which is the common case and
     * matches the old always-blank-line behavior exactly). Both halves are
     * run through [wrapLineByWidth] since a split can leave either one still
     * too long for the screen on its own -- e.g. splitting a single very long
     * word-free run roughly in half.
     */
    fun handleEnterFromLyrics(afterId: String, caretIndex: Int) {
        val idx = lines.indexOfFirst { it.id == afterId }
        if (idx == -1) return
        val line = lines[idx]
        val (first, second) = splitLineAt(line, caretIndex, secondHardBreak = true)
        val producedFirst = wrapLineByWidth(first, lyricStyle, linesAreaWidthPx, textMeasurer)
        val producedSecond = wrapLineByWidth(second, lyricStyle, linesAreaWidthPx, textMeasurer)
        lines = lines.toMutableList().apply {
            removeAt(idx)
            addAll(idx, producedFirst + producedSecond)
        }
        pendingFocus = PendingFocus(producedSecond.first().id, Track.Lyrics, 0)
        persist()
    }

    fun handleMergeWithPrevious(lineId: String) {
        val idx = lines.indexOfFirst { it.id == lineId }
        if (idx <= 0) return // can't merge the first line
        val prev = lines[idx - 1]
        val curr = lines[idx]
        val caret = prev.lyrics.length
        val merged = mergeWithPrevious(prev, curr)
        // Immediate, not debounced like performReflowIfNeeded's forward split --
        // this is a one-shot event (a backspace), not a run of keystrokes to
        // coalesce. Without this, joining two lines whose combined text
        // overflows the width left it sitting in the single-line field
        // clipped/scrolled off past the right edge instead of wrapping back
        // onto its own row(s), same as it would if typed that long directly.
        val produced = wrapLineByWidth(merged, lyricStyle, linesAreaWidthPx, textMeasurer)
        lines = lines.toMutableList().apply {
            removeAt(idx)
            removeAt(idx - 1)
            addAll(idx - 1, produced)
        }
        var remaining = caret
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

        if (idx > 0 && !curr.hardBreak) {
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
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
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
                    onEnter = { caret -> handleEnterFromLyrics(line.id, caret) },
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

    // Drawn before ScratchpadScreen/ChordVoicingPanel below so either one's
    // full-screen surface naturally covers this button when showing --
    // never needs its own extra "hide me" condition for that.
    AutoscrollControls(
        enabled = autoscrollEnabled,
        onToggle = { autoscrollEnabled = !autoscrollEnabled },
        speed = autoscrollSpeed,
        onSpeedChange = { autoscrollSpeed = it },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(16.dp),
    )

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
private val baseLyricTextStyle: TextStyle
    @Composable get() = TextStyle(
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
    onEnter: (caretIndex: Int) -> Unit,
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
                    chordsField = suppressDoubleSpacePeriod(chordsField, candidate).capLength(MAX_CHORDS_LENGTH)
                    onChordsChange(chordsField.text)
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
            cursorBrush = SolidColor(LyricColor),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onEnter(lyricsField.selection.start) }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .padding(vertical = 1.dp)
                .focusRequester(lyricsFocus)
                .onFocusChanged { lyricsFocused = it.isFocused }
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
                if (line.lyrics.isEmpty() && !lyricsFocused) {
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
 * Autoscroll's whole UI footprint: a round toggle button (Play while off,
 * Pause while on), with a small speed pill above it that only appears while
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
        FilledIconButton(onClick = onToggle) {
            Icon(
                if (enabled) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (enabled) "Stop autoscroll" else "Start autoscroll",
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
