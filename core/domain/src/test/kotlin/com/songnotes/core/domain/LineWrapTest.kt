package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hand-crafted tests for [columnBreaks]/[cutIntoRows]/[wrapSongToColumns] --
 * Android-only logic (the web app stores phone-agnostic lines and never wraps
 * them for export), so there's no JS fixture to cross-check against.
 */
class LineWrapTest {

    private fun rows(text: String, maxColumns: Int): List<String> {
        val starts = columnBreaks(text, maxColumns)
        return starts.indices.map { k -> text.substring(starts[k], starts.getOrNull(k + 1) ?: text.length) }
    }

    @Test
    fun `a word that doesn't fit moves down whole and the space before it hangs`() {
        assertEquals(listOf("the quick ", "brown fox"), rows("the quick brown fox", 12))
    }

    @Test
    fun `a space exactly at the column limit hangs instead of starting the next row`() {
        assertEquals(listOf("abcdef ", "ghi"), rows("abcdef ghi", 6))
    }

    @Test
    fun `a run with no space is cut at the last column that fits`() {
        assertEquals(listOf("abcde", "fghij", "k"), rows("abcdefghijk", 5))
    }

    @Test
    fun `a line that fits stays one row, including an empty one`() {
        assertEquals(listOf(0), columnBreaks("short", 40))
        assertEquals(listOf(0), columnBreaks("", 40))
    }

    @Test
    fun `a surrogate pair is never split across rows`() {
        val text = "abcd🎸ef" // guitar emoji at index 4..5
        rows(text, 5).forEach { row ->
            assert(!row.first().isLowSurrogate()) { "row starts mid-pair: $row" }
        }
    }

    @Test
    fun `chords follow the lyric character they sit above onto its row`() {
        val chords = listOf(ChordAnchor(0, "G"), ChordAnchor(10, "C"), ChordAnchor(16, "D"))
        val result = cutIntoRows("the quick brown fox", chords, listOf(0, 10))
        assertEquals(WrappedRow("the quick ", listOf(ChordAnchor(0, "G"))), result[0])
        assertEquals(WrappedRow("brown fox", listOf(ChordAnchor(0, "C"), ChordAnchor(6, "D"))), result[1])
    }

    @Test
    fun `chords past the end of the lyrics stay on the last row`() {
        val result = cutIntoRows("la la", listOf(ChordAnchor(9, "Em")), listOf(0, 3))
        assertEquals(listOf(ChordAnchor(6, "Em")), result[1].chords)
    }

    private fun song(vararg lines: SongLine) = Song(id = "s", title = "T", lines = lines.toList(), createdAt = 0, updatedAt = 0)

    @Test
    fun `wrapSongToColumns splits each line into rows and trims the hanging space`() {
        val wrapped = wrapSongToColumns(song(SongLine("a", "the quick brown fox", listOf(ChordAnchor(10, "Am")))), 12)
        assertEquals(listOf("the quick", "brown fox"), wrapped.lines.map { it.lyrics })
        assertEquals(listOf(emptyList(), listOf(ChordAnchor(0, "Am"))), wrapped.lines.map { it.chords })
    }

    @Test
    fun `wrapSongToColumns leaves short lines exactly as they were`() {
        val line = SongLine("a", "short", listOf(ChordAnchor(0, "G")))
        assertEquals(listOf(line), wrapSongToColumns(song(line), 40).lines)
    }

    @Test
    fun `chords running past the column limit after the lyrics continue on their own row`() {
        val line = SongLine("a", "", listOf(ChordAnchor(0, "G"), ChordAnchor(6, "C"), ChordAnchor(12, "D")))
        val wrapped = wrapSongToColumns(song(line), 10)
        assertEquals(
            listOf(listOf(ChordAnchor(0, "G"), ChordAnchor(6, "C")), listOf(ChordAnchor(0, "D"))),
            wrapped.lines.map { it.chords },
        )
    }

    @Test
    fun `formatSongAsText of a wrapped song keeps every chord over its word`() {
        val line = SongLine("a", "the quick brown fox jumps", listOf(ChordAnchor(4, "G"), ChordAnchor(16, "C"), ChordAnchor(20, "D")))
        val text = formatSongAsText(wrapSongToColumns(song(line), 12))
        // Chord lines stay padded to their lyric line's length, as formatSongAsText always pads them.
        assertEquals("T\n=\n\n    G    \nthe quick\n      C  \nbrown fox\nD    \njumps", text)
    }
}
