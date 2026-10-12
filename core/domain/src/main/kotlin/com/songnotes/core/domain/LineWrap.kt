package com.songnotes.core.domain

/**
 * Wrapping a [SongLine] into display rows. A stored line is a whole
 * paragraph -- the editor wraps it to the screen, and exports wrap it to the
 * page -- so this is the one place that decides which chords go on which row.
 */

/** One display row of a wrapped line; [chords] are row-relative. */
data class WrappedRow(val lyrics: String, val chords: List<ChordAnchor>)

/**
 * Cuts [lyrics] into rows beginning at [starts] (ascending, first is 0). Each
 * chord goes to the row its index falls in, so it stays above the same lyric
 * character; chords at or past the end of the lyrics (an ending chord, or a
 * chords-only line) stay on the last row.
 */
fun cutIntoRows(lyrics: String, chords: List<ChordAnchor>, starts: List<Int>): List<WrappedRow> =
    starts.indices.map { k ->
        val start = starts[k]
        val end = starts.getOrNull(k + 1) ?: lyrics.length
        val rowChords = chords
            .filter { it.i >= start && (k == starts.lastIndex || it.i < end) }
            .map { it.copy(i = it.i - start) }
        WrappedRow(lyrics.substring(start, end), rowChords)
    }

/**
 * Row start offsets for [text] wrapped at [maxColumns] monospace columns,
 * with the same greedy rule Android's line breaker applies on screen: a word
 * that doesn't fit moves down whole, the spaces before it hang at the end of
 * the row above (so the next row starts flush), and a run with no space in
 * it is cut at the last character that fits.
 */
fun columnBreaks(text: String, maxColumns: Int): List<Int> {
    val starts = mutableListOf(0)
    if (maxColumns <= 0) return starts
    var pos = 0
    while (text.length - pos > maxColumns) {
        val limit = pos + maxColumns
        var end = if (text[limit] == ' ') {
            limit
        } else {
            val space = text.lastIndexOf(' ', limit - 1)
            if (space > pos) space + 1 else limit
        }
        while (end < text.length && text[end] == ' ') end++
        if (end < text.length && text[end].isLowSurrogate() && end - 1 > pos) end--
        if (end >= text.length) break
        pos = end
        starts += pos
    }
    return starts
}

/**
 * [song] with every line wrapped to [maxColumns] for a fixed-width export
 * (plain text, PDF): each row becomes its own line, with the spaces a wrap
 * hung at its end trimmed. Chords that sit past the end of a short last row
 * and would run beyond [maxColumns] continue on a chords-only row of their
 * own rather than off the page.
 */
fun wrapSongToColumns(song: Song, maxColumns: Int): Song {
    val lines = song.lines.flatMap { line ->
        val rows = cutIntoRows(line.lyrics, line.chords.sortedBy { it.i }, columnBreaks(line.lyrics, maxColumns))
        val out = rows.dropLast(1).map { SongLine(line.id, it.lyrics.trimEnd(), it.chords) }.toMutableList()
        var last = rows.last().let { it.copy(lyrics = it.lyrics.trimEnd()) }
        while (true) {
            val overflow = last.chords.indexOfFirst { it.i > 0 && it.i + it.c.length > maxColumns && it.i >= last.lyrics.length }
            if (overflow <= 0) break
            out += SongLine(line.id, last.lyrics, last.chords.take(overflow))
            val shift = last.chords[overflow].i
            last = WrappedRow("", last.chords.drop(overflow).map { it.copy(i = it.i - shift) })
        }
        out += SongLine(line.id, last.lyrics, last.chords)
        out
    }
    return song.copy(lines = lines)
}
