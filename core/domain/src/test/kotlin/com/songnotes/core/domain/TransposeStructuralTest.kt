package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hand-ported structural/ordering tests from the desktop repo's
 * `src/test/transpose.test.js` — same rationale as [ChordsStructuralTest]:
 * the golden fixtures already prove byte-identical output at volume, this
 * file names the specific rules (octave wraparound, dual root/bass
 * parsing, per-token independence) a maintainer would otherwise have to
 * infer from the fixtures.
 */
class TransposeStructuralTest {

    @Test
    fun `wraps around the top of the octave`() {
        assertEquals("C", transposeChordToken("B", 1))
    }

    @Test
    fun `wraps around the bottom of the octave`() {
        assertEquals("B", transposeChordToken("C", -1))
    }

    @Test
    fun `transposes both the root and the slash-bass note independently`() {
        assertEquals("E/G#", transposeChordToken("D/F#", 2))
    }

    @Test
    fun `flat root spellings are normalized to sharp on output`() {
        assertEquals("B", transposeChordToken("Bb", 1))
        assertEquals("C", transposeChordToken("Db", -1))
    }

    @Test
    fun `a full octave, up or down, returns to the same chord`() {
        assertEquals("F#m7", transposeChordToken("F#m7", 12))
        assertEquals("F#m7", transposeChordToken("F#m7", -12))
    }

    @Test
    fun `non-chord-shaped text passes through untouched`() {
        assertEquals("(fast)", transposeChordToken("(fast)", 2))
        assertEquals("", transposeChordToken("", 2))
    }

    @Test
    fun `zero semitones returns the original text unchanged, byte-identical`() {
        assertEquals("G", transposeChordToken("G", 0))
        val line = "G          C          D"
        assertEquals(line, transposeChordsLine(line, 0))
    }

    @Test
    fun `unrecognized tokens are left untouched while the rest of the line still transposes`() {
        // Per-token independence: one non-chord word on a chords-track line
        // must not stop its neighbors from transposing.
        assertEquals("A    (slow)    E", transposeChordsLine("G    (slow)    D", 2))
    }

    @Test
    fun `a mix of qualities and slash chords on one line all transpose correctly`() {
        assertEquals("Gm7   C/E   Fmaj7", transposeChordsLine("Am7   D/F#   Gmaj7", -2))
    }
}
