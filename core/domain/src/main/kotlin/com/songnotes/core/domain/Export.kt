package com.songnotes.core.domain

/**
 * Ported from the web app's `src/utils/export.js` `exportToText` -- Ultimate
 * Guitar-style chord/lyric pairs, chord line directly above its lyric line.
 * Verified byte-for-byte against `spec/export-to-text.json` (fixtures from
 * the real JS function) by `ExportGoldenFixtureTest`.
 *
 * The JS version operates on the app's in-memory song shape, where
 * `line.chords` is already the padded-string track the editor works with --
 * this Kotlin port instead has `:core:domain`'s native [ChordAnchor] list,
 * so each line's chords are rendered back to that padded form via
 * [anchorsToChordsLine] first, matching what the JS side actually sees.
 */
fun formatSongAsText(song: Song): String {
    val title = song.title.ifBlank { "Untitled" }
    val header = "$title\n${"=".repeat(title.length)}\n\n"

    val body = song.lines.joinToString("\n") { line ->
        val chords = anchorsToChordsLine(line.lyrics.length, line.chords)
        val hasChords = chords.isNotBlank()
        val hasLyrics = line.lyrics.isNotBlank()
        when {
            !hasChords && !hasLyrics -> ""
            hasChords && hasLyrics -> "$chords\n${line.lyrics}"
            hasChords -> chords
            else -> line.lyrics
        }
    }
    return header + body
}
