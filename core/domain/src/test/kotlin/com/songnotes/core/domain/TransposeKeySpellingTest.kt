package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android-only key-aware spelling: [transposeKey], [prefersFlats], and `preferFlats` on chord transposition. */
class TransposeKeySpellingTest {

    @Test
    fun `flat keys are the conventional ones`() {
        listOf(5, 10, 3, 8, 1).forEach { assertTrue(prefersFlats(it, minor = false)) }
        listOf(0, 7, 2, 9, 4, 11, 6).forEach { assertFalse(prefersFlats(it, minor = false)) }
        listOf(2, 7, 0, 5, 10, 3).forEach { assertTrue(prefersFlats(it, minor = true)) }
        listOf(9, 4, 11, 6, 1, 8).forEach { assertFalse(prefersFlats(it, minor = true)) }
    }

    @Test
    fun `transposing the key spells it for the target key`() {
        assertEquals("Ab", transposeKey("G", 1))
        assertEquals("F#", transposeKey("G", -1))
        assertEquals("Fm", transposeKey("Em", 1))
        assertEquals("Bb major", transposeKey("A major", 1))
        assertEquals("C#m", transposeKey("Cm", 1))
    }

    @Test
    fun `text that isn't a key is left alone`() {
        assertEquals("", transposeKey("", 1))
        assertEquals("Gsus4", transposeKey("Gsus4", 1))
        assertEquals("unsure", transposeKey("unsure", 1))
        assertNull(parseKey("H"))
    }

    @Test
    fun `chords follow the requested spelling, roots and bass notes alike`() {
        assertEquals("Ab", transposeChordToken("G", 1, preferFlats = true))
        assertEquals("Db/F", transposeChordToken("C/E", 1, preferFlats = true))
        assertEquals("Eb  Ab", transposeChordsLine("D  G", 1, preferFlats = true))
        // The default stays the web app's always-sharps output.
        assertEquals("G#", transposeChordToken("G", 1))
    }

    @Test
    fun `a full round trip comes back to the same key`() {
        var key = "G"
        repeat(12) { key = transposeKey(key, 1) }
        assertEquals("G", key)
        assertEquals("G", transposeKey(transposeKey("G", 1), -1))
    }
}
