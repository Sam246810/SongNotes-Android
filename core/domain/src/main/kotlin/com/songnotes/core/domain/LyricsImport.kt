package com.songnotes.core.domain

/**
 * Ported from the desktop web app's `src/utils/lyricsImport.js` — same
 * faithful-port-plus-golden-fixture-cross-check approach as `Chords.kt`
 * and `Transpose.kt`. See `Chords.kt`'s own doc comment for the shared
 * reasoning; the fixtures this cross-checks against were generated from
 * the 25 hand-crafted scenarios already covered by the desktop repo's own
 * `src/test/lyricsImport.test.js` (each one targets one specific parsing
 * decision), not from combinatorial generation.
 */

/** One line of a parsed song — mirrors the JS version's `{chords, lyrics}` shape. */
data class LyricsLine(val chords: String, val lyrics: String)

/** The full result of [parseLyricsText]. */
data class ParsedLyrics(val title: String?, val meta: Map<String, String>, val lines: List<LyricsLine>)

private val SECTION_KEYWORDS = Regex(
    "^(verse|chorus|bridge|intro|outro|instrumental|interlude|pre-?chorus|post-?chorus|" +
        "pre-?bridge|refrain|hook|solo|tag|breakdown|build|drop|ending|coda|vamp|turnaround)\\b",
    RegexOption.IGNORE_CASE,
)

private val TITLE_UNDERLINE_RE = Regex("^[=-]{3,}\\s*$")

// Order matters: iterated in this order for each candidate header line,
// same as JS's Object.entries() preserving the literal's own field order.
private val META_HEADER_PATTERNS: List<Pair<String, Regex>> = listOf(
    "key" to Regex("^key\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE),
    "bpm" to Regex("^bpm\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE),
    "capo" to Regex("^capo\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE),
    "tuning" to Regex("^tuning\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE),
)

/**
 * Is the text inside one bracket group chord-shaped, as opposed to a
 * section label like "Verse" or a freeform note?
 */
private fun looksLikeChordBracketContent(inner: String): Boolean {
    val trimmed = inner.trim()
    if (trimmed.isEmpty()) return false
    if (SECTION_KEYWORDS.containsMatchIn(trimmed)) return false
    if (!Regex("^[A-G]").containsMatchIn(trimmed)) return false
    // a real chord (even compound, e.g. "Cmaj-slash-slash-C#maj") is short; a phrase isn't
    if (trimmed.length > 20) return false
    return true
}

/**
 * Does this line consist ENTIRELY of one or more bracket groups — no text
 * outside them (other than whitespace)? True for both chord cues and
 * section markers; [looksLikeBracketChordLine] narrows it down further.
 */
private fun isPureBracketLine(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || !trimmed.contains('[')) return false
    return Regex("\\[[^]]*]").replace(trimmed, "").trim().isEmpty()
}

/** A pure-bracket line whose bracket contents are (mostly) chord-shaped. */
private fun looksLikeBracketChordLine(text: String): Boolean {
    if (!isPureBracketLine(text)) return false
    val brackets = Regex("\\[([^]]*)]").findAll(text).map { it.groupValues[1] }.toList()
    if (brackets.isEmpty()) return false
    val chordLikeCount = brackets.count { looksLikeChordBracketContent(it) }
    return chordLikeCount.toDouble() / brackets.size >= 0.6
}

/**
 * A line that starts with a bracketed section label, e.g. "[Bridge]" or
 * "[Bridge] (very tentative)" — never a lyric, whatever follows the bracket.
 */
private fun startsWithSectionMarkerBracket(text: String): Boolean {
    val match = Regex("^\\[([^]]*)]").find(text.trim()) ?: return false
    return SECTION_KEYWORDS.containsMatchIn(match.groupValues[1].trim())
}

/**
 * A candidate "next line" is only ever treated as sung lyrics if it isn't
 * itself some flavor of chord/annotation line. [text] is null exactly
 * where the JS version's would be `undefined` — reading past the end of
 * the line list.
 */
private fun readsAsLyrics(text: String?): Boolean {
    return text != null &&
        text.trim().isNotEmpty() &&
        !isPureBracketLine(text) &&
        !looksLikeChordLine(text) &&
        !startsWithSectionMarkerBracket(text)
}

/**
 * Converts a bracket chord-cue line into a bare chords-track string by
 * stripping just the brackets — everything else, including whatever
 * leading whitespace hints at the intended column, is left exactly as
 * typed.
 */
private fun stripBracketsForChordsLine(text: String): String = text.replace(Regex("[\\[\\]]"), "")

/**
 * Best-effort detector for "this line is a row of chord symbols sitting
 * above a lyric line" (the plain-text chord-chart convention where
 * whitespace does the aligning). Reuses [tokenizeChordLine] (same
 * validation the editor's chord track already relies on), plus two extra
 * filters aimed specifically at telling chord lines apart from prose: a
 * token only counts if its ORIGINAL text starts with an uppercase A-G
 * (since [normalizeChordName] uppercases everything before lookup and
 * would otherwise treat a lowercase word like "a" as chord A), and a line
 * ending in normal sentence punctuation is never a chord line.
 */
fun looksLikeChordLine(text: String?): Boolean {
    if (text.isNullOrEmpty()) return false
    val trimmedEnd = text.replace(Regex("\\s+$"), "")
    if (trimmedEnd.trim().isEmpty()) return false
    if (Regex("[.,!?;:]$").containsMatchIn(trimmedEnd)) return false

    val tokens = tokenizeChordLine(text).filter { !it.isWhitespace }
    if (tokens.isEmpty()) return false

    val chordLikeCount = tokens.count { it.isChord && Regex("^[A-G]").containsMatchIn(it.text) }
    return chordLikeCount.toDouble() / tokens.size >= 0.6
}

/**
 * Parses raw imported text (from a pasted textarea, a .txt file, or PDF
 * text extraction) into the same `{chords, lyrics}` line shape the editor
 * already uses everywhere else. Detection runs independently per line, so
 * a false positive/negative on one line never affects the rest of the
 * song; the result is always meant to be hand-edited afterward like any
 * other pasted text.
 *
 * Understands two chord notations (freely mixed within the same file):
 * bare chords aligned above a lyric line via whitespace, and chords
 * bracketed on their own line (optionally several per line) — plus, if
 * present, a small "Key: .../BPM: .../Capo: .../Tuning: ..." header block
 * up top.
 */
fun parseLyricsText(rawText: String?): ParsedLyrics {
    val allLines = (rawText ?: "").replace(Regex("\\r\\n?"), "\n").split("\n")

    // Trim fully-blank lines from the start/end.
    var start = 0
    var end = allLines.size
    while (start < end && allLines[start].trim().isEmpty()) start++
    while (end > start && allLines[end - 1].trim().isEmpty()) end--
    var lines = allLines.subList(start, end).toMutableList()

    // Optional title header: a title line followed by a row of =/- characters.
    var title: String? = null
    if (lines.size >= 2 && lines[0].trim().isNotEmpty() && TITLE_UNDERLINE_RE.containsMatchIn(lines[1])) {
        title = lines[0].trim()
        lines = lines.subList(2, lines.size).toMutableList()
        if (lines.isNotEmpty() && lines[0].trim().isEmpty()) lines = lines.subList(1, lines.size).toMutableList()
    }

    // Optional "Key: .../BPM: ..." reference header block.
    val meta = LinkedHashMap<String, String>()
    var lastHeaderIdx = -1
    for (idx in lines.indices) {
        // Trimmed for matching only — PDF-extracted text commonly carries a
        // uniform left-margin indent on every line.
        val line = lines[idx].trim()
        if (line.isEmpty()) continue // blank lines inside the header block are fine
        val matchEntry = META_HEADER_PATTERNS.firstOrNull { (_, re) -> re.containsMatchIn(line) } ?: break
        val (field, re) = matchEntry
        meta[field] = re.find(line)!!.groupValues[1].trim()
        lastHeaderIdx = idx
    }
    if (lastHeaderIdx >= 0) {
        lines = lines.subList(lastHeaderIdx + 1, lines.size).toMutableList()
        while (lines.isNotEmpty() && lines[0].trim().isEmpty()) lines = lines.subList(1, lines.size).toMutableList()
    }

    val result = mutableListOf<LyricsLine>()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]

        if (raw.trim().isEmpty()) {
            result.add(LyricsLine(chords = "", lyrics = ""))
            i += 1
            continue
        }

        if (looksLikeBracketChordLine(raw)) {
            val next = lines.getOrNull(i + 1)
            if (readsAsLyrics(next)) {
                result.add(LyricsLine(chords = stripBracketsForChordsLine(raw), lyrics = next!!))
                i += 2
            } else {
                result.add(LyricsLine(chords = stripBracketsForChordsLine(raw), lyrics = ""))
                i += 1
            }
            continue
        }

        if (!isPureBracketLine(raw) && looksLikeChordLine(raw)) {
            val next = lines.getOrNull(i + 1)
            if (readsAsLyrics(next)) {
                result.add(LyricsLine(chords = raw, lyrics = next!!))
                i += 2
            } else {
                result.add(LyricsLine(chords = raw, lyrics = ""))
                i += 1
            }
            continue
        }

        result.add(LyricsLine(chords = "", lyrics = raw))
        i += 1
    }

    return ParsedLyrics(title = title, meta = meta, lines = result)
}
