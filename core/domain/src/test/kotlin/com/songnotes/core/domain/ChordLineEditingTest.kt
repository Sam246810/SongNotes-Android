package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** [applyChordOverwrite]: chord names overwrite spaces, spaces still nudge. */
class ChordLineEditingTest {

    /** Types [typed] at [at] into [old] the way a text field would, then applies the rules. */
    private fun type(old: String, at: Int, typed: String) =
        applyChordOverwrite(old, old.substring(0, at) + typed + old.substring(at), at + typed.length)

    /** Backspaces [count] characters before [at]. */
    private fun backspace(old: String, at: Int, count: Int = 1) =
        applyChordOverwrite(old, old.substring(0, at - count) + old.substring(at), at - count)

    @Test
    fun `typing a chord between two chords leaves the later one where it was`() {
        val old = "G            C           G"
        val (text, caret) = type(old, 19, "Am")
        assertEquals("G            C     Am    G", text)
        assertEquals(old.indexOfLast { it == 'G' }, text.indexOfLast { it == 'G' })
        assertEquals(21, caret)
    }

    @Test
    fun `typing next to a chord keeps one space before it`() {
        assertEquals("C  Am G" to 5, type("C  A  G", 4, "m"))
        // No room left: the separator stays and the chord moves.
        assertEquals("Am G" to 2, type("A G", 1, "m"))
    }

    @Test
    fun `typing into the end-of-line padding eats it freely`() {
        assertEquals("G   D   " to 5, type("G       ", 4, "D"))
        assertEquals("G D" to 3, type("G  ", 2, "D"))
    }

    @Test
    fun `backspacing a chord name keeps later chords in their columns`() {
        val old = "G            C     Am    G"
        val (text, caret) = backspace(old, 21, 2)
        assertEquals("G            C           G", text)
        assertEquals(19, caret)
    }

    @Test
    fun `typing or deleting spaces still nudges the chords after it`() {
        assertEquals("G   C" to 2, type("G  C", 1, " "))
        assertEquals("G C" to 1, backspace("G  C", 2))
    }

    @Test
    fun `keyboard composing A into Am counts as typing the m`() {
        assertEquals("Am  G" to 2, applyChordOverwrite("A   G", "Am   G", 2))
    }

    @Test
    fun `replacing a selected chord with a longer one eats spaces after it`() {
        // "C" selected and replaced with "Cmaj7": 4 characters longer.
        assertEquals("Cmaj7 G" to 5, applyChordOverwrite("C     G", "Cmaj7     G", 5))
    }

    @Test
    fun `an unchanged line passes straight through`() {
        assertEquals("G  C" to 3, applyChordOverwrite("G  C", "G  C", 3))
    }
}
