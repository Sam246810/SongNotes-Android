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

/**
 * The song's reference details as one line for exports -- "Key: G · Capo 2 ·
 * 92 BPM · Drop D" -- with only the fields that are set, or null when none
 * are. Android-only, like [formatSongForSharing].
 */
fun formatSongMetaLine(song: Song): String? {
    val meta = song.meta
    val parts = listOfNotNull(
        meta.key.trim().takeIf { it.isNotEmpty() }?.let { "Key: $it" },
        meta.capo.takeIf { it > 0 }?.let { "Capo $it" },
        meta.bpm.takeIf { it > 0 }?.let { "$it BPM" },
        meta.tuning.trim().takeIf { it.isNotEmpty() },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * What Copy and the .txt share actually send: [formatSongAsText] (left
 * byte-identical to the web app's export, which its golden fixtures check)
 * plus the [formatSongMetaLine] under the title -- capo especially, which
 * anyone playing along needs -- and without the trailing alignment padding
 * chord lines carry.
 */
fun formatSongForSharing(song: Song): String {
    val text = formatSongAsText(song)
    val metaLine = formatSongMetaLine(song)
    val lines = text.split("\n").toMutableList()
    // formatSongAsText's header is always title, underline, blank line.
    if (metaLine != null && lines.size >= 2) lines.add(2, metaLine)
    return lines.joinToString("\n") { it.trimEnd() }
}
