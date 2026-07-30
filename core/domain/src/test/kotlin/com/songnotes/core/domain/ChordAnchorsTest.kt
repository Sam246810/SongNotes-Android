package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hand-crafted tests for [chordsLineToAnchors]/[anchorsToChordsLine]/
 * [transposeChordAnchors] — new logic written directly from
 * `docs/WIRE-FORMAT-v2.md` §4, not a JS port, so there's no golden
 * fixture to cross-check against. Covers the specific tricky cases the
 * wire format itself calls out: overlapping chords, a chord past
 * end-of-lyrics, an all-instrumental (empty-lyrics) line, adjacent
 * chords with zero gap.
 */
class ChordAnchorsTest {

    @Test
    fun `chordsLineToAnchors finds maximal non-whitespace runs at their start column`() {
        val input = "G" + " ".repeat(10) + "C" // C starts at index 11
        assertEquals(listOf(ChordAnchor(0, "G"), ChordAnchor(11, "C")), chordsLineToAnchors(input))
    }

    @Test
    fun `chordsLineToAnchors preserves leading whitespace as the first anchor's column`() {
        assertEquals(listOf(ChordAnchor(3, "Am")), chordsLineToAnchors("   Am"))
    }

    @Test
    fun `chordsLineToAnchors handles adjacent chords with zero gap as one run`() {
        // No whitespace between "G" and "C" means they're one non-whitespace
        // run, "GC" — this is a degenerate/malformed input, not something a
        // real chords-track line produces, but the function must not crash.
        assertEquals(listOf(ChordAnchor(0, "GC")), chordsLineToAnchors("GC"))
    }

    @Test
    fun `chordsLineToAnchors returns empty list for null, empty, or whitespace-only input`() {
        assertEquals(emptyList<ChordAnchor>(), chordsLineToAnchors(null))
        assertEquals(emptyList<ChordAnchor>(), chordsLineToAnchors(""))
        assertEquals(emptyList<ChordAnchor>(), chordsLineToAnchors("    "))
    }

    @Test
    fun `anchorsToChordsLine renders each chord at its column, padded to lyrics length`() {
        val chords = listOf(ChordAnchor(0, "G"), ChordAnchor(11, "C"))
        assertEquals("G" + " ".repeat(10) + "C", anchorsToChordsLine(12, chords))
    }

    @Test
    fun `anchorsToChordsLine extends past lyricsLength when a chord's span exceeds it`() {
        // A chord placed past a short lyric line's end — valid per wire-format §4.
        val chords = listOf(ChordAnchor(10, "Dsus4"))
        val result = anchorsToChordsLine(3, chords)
        assertEquals(" ".repeat(10) + "Dsus4", result)
        assertEquals(15, result.length)
    }

    @Test
    fun `anchorsToChordsLine handles an all-instrumental empty-lyrics line`() {
        val chords = listOf(ChordAnchor(0, "G"), ChordAnchor(4, "C"), ChordAnchor(8, "D"))
        assertEquals("G   C   D", anchorsToChordsLine(0, chords))
    }

    @Test
    fun `anchorsToChordsLine returns spaces when there are no chords`() {
        assertEquals("     ", anchorsToChordsLine(5, emptyList()))
    }

    @Test
    fun `anchorsToChordsLine resolves overlapping columns with later-in-sort-order wins`() {
        // Both anchors start at column 0; "Am" (added first) is written,
        // then "C" (added second, same i, so it comes second under the
        // stable sort's tie-break) overwrites only the single column it
        // actually spans — the trailing "m" from "Am" is untouched, since
        // the algorithm overwrites at each i rather than clearing the
        // earlier anchor's whole span first. A real, if surprising, edge
        // case of the wire format's literal "overwrite at each i" rule.
        val chords = listOf(ChordAnchor(0, "Am"), ChordAnchor(0, "C"))
        assertEquals("Cm", anchorsToChordsLine(2, chords))
    }

    @Test
    fun `anchorsToChordsLine resolves a longer chord overwriting a shorter one at an overlapping later column`() {
        // "Am7" occupies columns 0-2; "C" at column 1 (sorted after Am7 by
        // start column) partially overwrites it.
        val chords = listOf(ChordAnchor(0, "Am7"), ChordAnchor(1, "C"))
        assertEquals("AC7", anchorsToChordsLine(3, chords))
    }

    @Test
    fun `chordsLineToAnchors and anchorsToChordsLine round-trip for non-overlapping chords`() {
        val original = "G          C          D"
        val anchors = chordsLineToAnchors(original)
        assertEquals(original, anchorsToChordsLine(original.length, anchors))
    }

    @Test
    fun `transposeChordAnchors shifts every chord's text but leaves i untouched`() {
        val chords = listOf(ChordAnchor(0, "C"), ChordAnchor(14, "G"))
        assertEquals(listOf(ChordAnchor(0, "D"), ChordAnchor(14, "A")), transposeChordAnchors(chords, 2))
    }

    @Test
    fun `transposeChordAnchors with zero semitones returns the identical list`() {
        val chords = listOf(ChordAnchor(0, "C"))
        assertEquals(chords, transposeChordAnchors(chords, 0))
    }

    @Test
    fun `transposeChordAnchors does not shift a chord's column even when the chord text changes width`() {
        // F# (2 chars) -> G (1 char): the whole point of the anchor model
        // over the padded-string one is that i doesn't need to change here.
        val chords = listOf(ChordAnchor(5, "F#"), ChordAnchor(10, "Bm"))
        val transposed = transposeChordAnchors(chords, 1)
        assertEquals(5, transposed[0].i)
        assertEquals("G", transposed[0].c)
        assertEquals(10, transposed[1].i)
    }
}
