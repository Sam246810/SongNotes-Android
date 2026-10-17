package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [formatSongMetaLine] and [formatSongForSharing] -- Android-only, not part of the web export's golden fixtures. */
class ExportSharingTest {

    private fun song(meta: SongMeta) = Song(
        id = "s",
        title = "Porch Light",
        meta = meta,
        lines = listOf(SongLine("1", "Left the porch light on", listOf(ChordAnchor(0, "G"), ChordAnchor(14, "C")))),
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `details line lists only the fields that are set`() {
        assertEquals("Key: G · Capo 2 · 92 BPM · Drop D", formatSongMetaLine(song(SongMeta(bpm = 92, key = "G", tuning = "Drop D", capo = 2))))
        assertEquals("Capo 3", formatSongMetaLine(song(SongMeta(capo = 3))))
        assertNull(formatSongMetaLine(song(SongMeta())))
    }

    @Test
    fun `shared text puts the details under the title and drops trailing padding`() {
        val text = formatSongForSharing(song(SongMeta(key = "G", capo = 2)))
        assertEquals(
            "Porch Light\n===========\nKey: G · Capo 2\n\nG             C\nLeft the porch light on",
            text,
        )
    }

    @Test
    fun `without details the shared text is the plain export minus trailing spaces`() {
        val s = song(SongMeta())
        assertEquals(formatSongAsText(s).lines().joinToString("\n") { it.trimEnd() }, formatSongForSharing(s))
    }
}
