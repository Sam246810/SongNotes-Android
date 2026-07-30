package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-ported structural/ordering tests from the desktop repo's
 * `src/test/chords.test.js` — per `docs/PLAN.md`'s Verification section:
 * the golden-fixture tests already prove byte-identical *output* against
 * the real JS at far higher volume than these ever could, so this file's
 * job isn't more coverage. It's naming the specific rules a maintainer
 * would otherwise have to reverse-engineer from the fixtures — e.g. the
 * `maj#7`-before-bare-`maj` ordering the plan calls out explicitly. Not a
 * 1:1 port of every JS `it()`: `lookupChord` and `CHORD_DB` structural
 * tests are skipped (out of scope — see `Chords.kt`'s doc comment on why
 * voicing data isn't ported).
 */
class ChordsStructuralTest {

    // ── normalizeChordName ──────────────────────────────────────────

    @Test
    fun `maj#7 and maj #7 shorthand resolve to maj7, not bare #7`() {
        // Ordering-dependent: the maj#7/maj7 rules must run before the
        // bare-maj rule, which would otherwise strip "maj" first and leave
        // a meaningless "#7".
        assertEquals("Dmaj7", normalizeChordName("Dmaj#7"))
        assertEquals("Dmaj7", normalizeChordName("Dmaj #7"))
        assertEquals("Cmaj7", normalizeChordName("Cmaj#7"))
    }

    @Test
    fun `jazz lead-sheet shorthand for minor, augmented, and diminished normalizes correctly`() {
        assertEquals("Dm", normalizeChordName("D-"))
        assertEquals("Dm7", normalizeChordName("D-7"))
        assertEquals("Caug", normalizeChordName("C+"))
        assertEquals("Bdim", normalizeChordName("B°"))
    }

    @Test
    fun `enharmonic aliases resolve Gb to F#`() {
        assertEquals("F#", normalizeChordName("Gb"))
        assertEquals("F#m", normalizeChordName("Gbm"))
    }

    @Test
    fun `slash-bass notation is stripped from the normalized name`() {
        assertEquals("G", normalizeChordName("G/B"))
    }

    @Test
    fun `empty, null, and blank input all normalize to empty string`() {
        assertEquals("", normalizeChordName(""))
        assertEquals("", normalizeChordName(null))
    }

    // ── tokenizeChordLine ────────────────────────────────────────────

    @Test
    fun `unknown chord-like words are isChord false but keep a non-blank chordName`() {
        // The chordName must survive even for an unrecognized chord so the
        // editor's popup can show a "no chart" message instead of nothing.
        val am9 = tokenizeChordLine("Am9 Am").first { it.text == "Am9" }
        assertFalse(am9.isChord)
        assertFalse(am9.isWhitespace)
        assertTrue(am9.chordName!!.isNotBlank())
    }

    @Test
    fun `looksLikeChord is lenient for a chord-shaped word with no DB entry`() {
        // isChord and looksLikeChord are deliberately different signals:
        // isChord gates chord-diagram lookup, looksLikeChord gates whether
        // a word is even treated as chord-track content at all.
        val am9 = tokenizeChordLine("Am9").first { !it.isWhitespace }
        assertFalse(am9.isChord)
        assertTrue(am9.looksLikeChord)
    }

    @Test
    fun `looksLikeChord is false for a word not starting with a root letter`() {
        val word = tokenizeChordLine("Slow").first { !it.isWhitespace }
        assertFalse(word.looksLikeChord)
    }

    @Test
    fun `customChords override takes priority when deciding isChord`() {
        val custom = mapOf("Am9" to mapOf("frets" to listOf(-1, 0, 2, 0, 1, 0), "baseFret" to 1))
        val withoutCustom = tokenizeChordLine("Am9").first { !it.isWhitespace }
        assertFalse(withoutCustom.isChord)
        val withCustom = tokenizeChordLine("Am9", custom).first { !it.isWhitespace }
        assertTrue(withCustom.isChord)
    }

    @Test
    fun `whitespace runs become their own separate tokens`() {
        val spaces = tokenizeChordLine("Am  G").filter { it.isWhitespace }
        assertTrue(spaces.isNotEmpty())
        assertTrue(Regex("^\\s+$").matches(spaces[0].text))
        assertFalse(spaces[0].looksLikeChord)
    }

    // ── formatFretsForInput / parseFretsInput ───────────────────────

    @Test
    fun `parseFretsInput round-trips with formatFretsForInput`() {
        val original = listOf(-1, 0, 2, 0, 1, 0) // Am7 shape
        val parsed = parseFretsInput(formatFretsForInput(original))
        assertEquals(original, parsed!!.frets)
    }

    @Test
    fun `baseFret is derived as the lowest fretted position when the shape does not fit the first 4 frets`() {
        val result = parseFretsInput("x 6 8 8 7 6")
        assertEquals(listOf(-1, 6, 8, 8, 7, 6), result!!.frets)
        assertEquals(6, result.baseFret)
    }

    @Test
    fun `x is case-insensitive as the muted-string marker`() {
        val result = parseFretsInput("X 0 2 2 2 0")
        assertEquals(listOf(-1, 0, 2, 2, 2, 0), result!!.frets)
        assertEquals(1, result.baseFret)
    }

    @Test
    fun `parseFretsInput rejects anything other than exactly 6 values`() {
        assertNull(parseFretsInput("x 3 2 0 1"))
        assertNull(parseFretsInput("x 3 2 0 1 0 3"))
    }

    // ── alignChordsWithLyrics ────────────────────────────────────────

    @Test
    fun `chords longer than lyrics with only trailing whitespace are trimmed to the lyric length`() {
        assertEquals("C ", alignChordsWithLyrics("C    ", "Hi"))
        assertEquals("C G  ", alignChordsWithLyrics("C G   ", "Hello"))
    }

    @Test
    fun `chords longer than lyrics with real trailing content are never trimmed or dropped`() {
        // The extra characters past the lyric length are real chord symbols
        // here, not padding — trimming them would silently lose data.
        assertEquals("C    G", alignChordsWithLyrics("C    G", "Hi"))
        assertEquals("C  Am  ", alignChordsWithLyrics("C  Am  ", "Hi"))
    }
}
