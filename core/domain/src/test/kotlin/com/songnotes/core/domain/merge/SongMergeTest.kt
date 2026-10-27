package com.songnotes.core.domain.merge

import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit checks for the merge engine's building blocks. The full case set --
 * shared with the web app -- runs in `:core:data`'s SongMergeGoldenFixtureTest
 * (it needs SongDocument.kt to read the fixture); these cover what that file
 * doesn't: limits, caret mapping, and that a long song stays fast.
 */
class SongMergeTest {

    private fun song(lines: List<SongLine>, updatedAt: Long = 1_000L, title: String = "T") =
        Song(id = "s", title = title, lines = lines, createdAt = 0L, updatedAt = updatedAt)

    @Test
    fun `tokens split words, whitespace runs and punctuation, and rebuild the text exactly`() {
        assertEquals(listOf("Don't", " ", "stop", ",", "  ", "now", "!"), tokenize("Don't stop,  now!"))
        assertEquals(listOf("😀", " ", "hi"), tokenize("😀 hi"))
        for (text in listOf("", "  lead and trail  ", "a b\tc\n d", "\"q\" (x) — y…")) {
            assertEquals(text, tokenize(text).joinToString(""))
        }
    }

    @Test
    fun `diff3 marks the same words changed differently as a conflict, and gives up past the LCS budget`() {
        val chunks = diff3(listOf("a", " ", "b"), listOf("a", " ", "x"), listOf("a", " ", "y"))!!
        assertNull(chunks.first { !it.stable }.take)
        val big = (0 until 500).map { "t$it" }
        assertNull(matchTokens(big, big.reversed()))
    }

    @Test
    fun `different words of the same line both survive, chords following their words`() {
        val base = song(listOf(SongLine("b", "Sunlight spilling through the blinds", listOf(ChordAnchor(9, "C"), ChordAnchor(18, "D")))))
        val local = song(listOf(SongLine("b", "Sunlight dancing through the blinds", listOf(ChordAnchor(9, "C"), ChordAnchor(17, "D")), editedAt = 9L)), 9L)
        val remote = song(listOf(SongLine("b", "Sunlight spilling across the blinds", listOf(ChordAnchor(9, "C"), ChordAnchor(18, "D")), editedAt = 10L)), 10L)

        val merged = mergeSongs(base, local, remote).lines.single()
        assertEquals("Sunlight dancing across the blinds", merged.lyrics)
        assertEquals(listOf(ChordAnchor(9, "C"), ChordAnchor(17, "D")), merged.chords)
        assertEquals(10L, merged.editedAt)
    }

    @Test
    fun `an edit beats a delete, and a true clash keeps both versions newest first`() {
        val base = song(listOf(SongLine("a", "one two"), SongLine("b", "keep me")))
        val local = song(listOf(SongLine("a", "one big two", editedAt = 9L)), 9L) // deleted b
        val remote = song(listOf(SongLine("a", "one small two", editedAt = 10L), SongLine("b", "keep me, edited", editedAt = 10L)), 10L)

        val lines = mergeSongs(base, local, remote).lines
        assertEquals(listOf("one small two", "one big two", "keep me, edited"), lines.map { it.lyrics })
        assertTrue(lines[1].id.startsWith("a~"))
    }

    @Test
    fun `the caret stays on the same words when text changes around it`() {
        assertEquals(2, mapOffsetThroughEdit("the cat sat", "the big cat sat", 2))
        assertEquals(13, mapOffsetThroughEdit("the cat sat", "the big cat sat", 9))
        assertEquals(7, mapOffsetThroughEdit("a dog here", "a horse here", 3))
        assertEquals(0, mapOffsetThroughEdit("abc", "", 2))
    }

    @Test
    fun `a 300-line song edited throughout on both sides merges in well under 50 ms`() {
        fun words(k: Int) = (0 until 100).joinToString(" ") { "word${k}x$it" }
        val baseLines = (0 until 300).map { SongLine("l$it", words(it), listOf(ChordAnchor(0, "G"))) }
        fun edited(tag: String, at: Int, stamp: Long) = song(
            baseLines.map { l -> l.copy(lyrics = l.lyrics.split(" ").toMutableList().also { it[at] = "$tag$at" }.joinToString(" "), editedAt = stamp) },
            stamp,
        )
        val base = song(baseLines)
        val local = edited("L", 10, 9L)
        val remote = edited("R", 90, 10L)

        repeat(3) { mergeSongs(base, local, remote) } // warm up the JIT
        val runs = 5
        val start = System.nanoTime()
        var merged: Song? = null
        repeat(runs) { merged = mergeSongs(base, local, remote) }
        val perMergeMs = (System.nanoTime() - start) / 1_000_000.0 / runs

        val first = merged!!.lines.first().lyrics.split(" ")
        assertEquals("L10", first[10])
        assertEquals("R90", first[90])
        assertTrue("merge took $perMergeMs ms", perMergeMs < 50.0)
    }
}
