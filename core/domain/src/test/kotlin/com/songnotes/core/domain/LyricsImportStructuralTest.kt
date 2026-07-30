package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-ported structural/ordering tests from the desktop repo's
 * `src/test/lyricsImport.test.js` — same rationale as
 * [ChordsStructuralTest]: the golden fixtures (the same 25 hand-crafted
 * scenarios plus a longer realistic song) already prove byte-identical
 * output; this file names the specific ambiguity-resolving rules a
 * maintainer would otherwise have to reverse-engineer from those fixtures.
 */
class LyricsImportStructuralTest {

    // ── looksLikeChordLine ───────────────────────────────────────────

    @Test
    fun `a lowercase one-word line that collides with a chord letter is rejected`() {
        // "a" would normalize to chord "A" if case were ignored — must not misfire.
        assertFalse(looksLikeChordLine("a"))
    }

    @Test
    fun `a lyric line ending in sentence punctuation is rejected even if it starts with a chord-like word`() {
        assertFalse(looksLikeChordLine("Did I do that?"))
    }

    @Test
    fun `a minority of non-chord annotation tokens is tolerated`() {
        assertTrue(looksLikeChordLine("G    C    (slow down)    D"))
    }

    @Test
    fun `mostly-prose text with one incidental chord-like word is rejected`() {
        assertFalse(looksLikeChordLine("C is for cookie, and that is good enough for me"))
    }

    // ── parseLyricsText: line pairing structure ───────────────────────

    @Test
    fun `chords and lyrics columns are never rewritten, only paired`() {
        val chordLine = "   G        C"
        val lyricLine = "Well hello there friend"
        val lines = parseLyricsText("$chordLine\n$lyricLine").lines
        assertEquals(chordLine, lines[0].chords)
        assertEquals(lyricLine, lines[0].lyrics)
        assertEquals(chordLine.indexOf('G'), lines[0].chords.indexOf('G'))
    }

    @Test
    fun `two consecutive chord lines are never paired with each other`() {
        val lines = parseLyricsText("G   C\nAm   F\nHere come the lyrics finally").lines
        assertEquals(listOf(LyricsLine("G   C", ""), LyricsLine("Am   F", "Here come the lyrics finally")), lines)
    }

    @Test
    fun `a standalone instrumental chord line not followed by lyrics stays chords-only`() {
        val lines = parseLyricsText("Verse\n\nG   C   D   G\n\nBridge").lines
        assertEquals(
            listOf(
                LyricsLine("", "Verse"),
                LyricsLine("", ""),
                LyricsLine("G   C   D   G", ""),
                LyricsLine("", ""),
                LyricsLine("", "Bridge"),
            ),
            lines,
        )
    }

    @Test
    fun `internal blank lines between verses are preserved`() {
        val lines = parseLyricsText("First verse line\n\nSecond verse line").lines
        assertEquals(
            listOf(LyricsLine("", "First verse line"), LyricsLine("", ""), LyricsLine("", "Second verse line")),
            lines,
        )
    }

    @Test
    fun `leading and trailing blank lines are trimmed`() {
        assertEquals(listOf(LyricsLine("", "Only line")), parseLyricsText("\n\n  \nOnly line\n\n\n").lines)
    }

    @Test
    fun `Windows-style line endings are handled`() {
        assertEquals(listOf(LyricsLine("G   C", "Hello there")), parseLyricsText("G   C\r\nHello there\r\n").lines)
    }

    @Test
    fun `a title header is detected and stripped, matching exportToText's own format`() {
        val text = "Yesterday\n=========\n\nG          Am\nYesterday, all my troubles seemed so far away"
        val result = parseLyricsText(text)
        assertEquals("Yesterday", result.title)
        assertEquals(listOf(LyricsLine("G          Am", "Yesterday, all my troubles seemed so far away")), result.lines)
    }

    @Test
    fun `a normal two-line song is not misdetected as having a title header`() {
        val result = parseLyricsText("First line\nSecond line")
        assertNull(result.title)
    }

    // ── parseLyricsText: bracketed chord cues ─────────────────────────

    @Test
    fun `bracket leading whitespace is preserved as the intended column position`() {
        val lines = parseLyricsText("          [G#maj]\nFor all the heart I have").lines
        assertEquals(listOf(LyricsLine("          G#maj", "For all the heart I have")), lines)
    }

    @Test
    fun `multiple bracketed chords on one cue line become a space-joined chords track`() {
        val lines = parseLyricsText("[Fmin] [G#maj]\nWhat is the color of your butterflies").lines
        assertEquals(listOf(LyricsLine("Fmin G#maj", "What is the color of your butterflies")), lines)
    }

    @Test
    fun `a bracketed section marker is never mistaken for a chord`() {
        val lines = parseLyricsText("[Verse]\n[G#maj]\nFor all the heart I have").lines
        assertEquals(
            listOf(LyricsLine("", "[Verse]"), LyricsLine("G#maj", "For all the heart I have")),
            lines,
        )
    }

    @Test
    fun `a standalone instrumental chord cue does not swallow a following section marker as its lyrics`() {
        val text = "[Instrumental]\n[F#maj] [A#min] [F#maj]\n\n[Bridge] (very very tentative)\nWhatever happens"
        val lines = parseLyricsText(text).lines
        assertEquals(
            listOf(
                LyricsLine("", "[Instrumental]"),
                LyricsLine("F#maj A#min F#maj", ""),
                LyricsLine("", ""),
                LyricsLine("", "[Bridge] (very very tentative)"),
                LyricsLine("", "Whatever happens"),
            ),
            lines,
        )
    }

    @Test
    fun `a section marker with descriptive text is not mistaken for a chord cue`() {
        val lines = parseLyricsText("[Last Chorus Ending (rest is the same)]\nMaybe instead of a last chorus").lines
        assertEquals(LyricsLine("", "[Last Chorus Ending (rest is the same)]"), lines[0])
    }

    // ── parseLyricsText: Key/BPM/Capo/Tuning header ───────────────────

    @Test
    fun `all four header fields are extracted when present`() {
        val text = "Key: G\nBPM: 90\nCapo: 2\nTuning: Drop D\n\nSome lyric line"
        val meta = parseLyricsText(text).meta
        assertEquals(mapOf("key" to "G", "bpm" to "90", "capo" to "2", "tuning" to "Drop D"), meta)
    }

    @Test
    fun `no header present yields an empty meta map`() {
        val result = parseLyricsText("Just a regular lyric line")
        assertTrue(result.meta.isEmpty())
    }

    @Test
    fun `an ordinary lyric line is never misdetected as a header`() {
        // "Key change is coming soon" starts with "Key" but isn't "Key: ...".
        val result = parseLyricsText("Key change is coming soon\nfor everyone involved")
        assertTrue(result.meta.isEmpty())
        assertEquals("Key change is coming soon", result.lines[0].lyrics)
    }

    @Test
    fun `a header with a uniform PDF-extraction left-margin indent is still detected`() {
        val text = "              Key: C# Maj\n              BPM: 118\n              [Verse]\n" +
            "              [G#maj]\n              For all the heart I have"
        val result = parseLyricsText(text)
        assertEquals(mapOf("key" to "C# Maj", "bpm" to "118"), result.meta)
        // But the surviving lines are NOT re-trimmed — the indent is preserved verbatim.
        assertEquals(LyricsLine("", "              [Verse]"), result.lines[0])
    }
}
