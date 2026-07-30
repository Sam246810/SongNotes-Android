package com.songnotes.core.domain

/**
 * Conversions between the Android app's per-chord-anchor model ([ChordAnchor])
 * and the desktop web app's space-padded "chords line aligned above a
 * lyrics line" convention — exactly the two directions `docs/WIRE-FORMAT-v2.md`
 * §4 specifies (needed for pasting a plain-text chord sheet into the
 * editor, and for exporting back out to one). Not a port of anything in
 * the JS source — the desktop app has no anchor model to port from, this
 * is new logic written directly from the wire-format spec.
 */

/**
 * Scans [chordsLine] for maximal non-whitespace runs; each run's start
 * column becomes [ChordAnchor.i], its text becomes [ChordAnchor.c].
 * Mirrors wire-format §4's "Converting v1's padded string → v2 anchors."
 */
fun chordsLineToAnchors(chordsLine: String?): List<ChordAnchor> {
    if (chordsLine.isNullOrEmpty()) return emptyList()
    val anchors = mutableListOf<ChordAnchor>()
    var i = 0
    while (i < chordsLine.length) {
        if (chordsLine[i].isWhitespace()) {
            i++
            continue
        }
        val start = i
        while (i < chordsLine.length && !chordsLine[i].isWhitespace()) i++
        anchors.add(ChordAnchor(i = start, c = chordsLine.substring(start, i)))
    }
    return anchors
}

/**
 * Renders [chords] back into a space-padded string at least [lyricsLength]
 * characters long (longer if any anchor's rendered span extends past it).
 * Mirrors wire-format §4's "Converting v2 anchors → a v1-style padded
 * string": overlapping columns are resolved by later-anchor-in-sort-order
 * wins, matching the spec's mandated overlap rule exactly (both platforms
 * must agree, since plain-text export has no way to represent two chords
 * at the same column).
 */
fun anchorsToChordsLine(lyricsLength: Int, chords: List<ChordAnchor>): String {
    if (chords.isEmpty()) return " ".repeat(maxOf(0, lyricsLength))
    val sorted = chords.sortedBy { it.i } // stable — ties keep their original relative order
    val totalLength = maxOf(lyricsLength, sorted.maxOf { it.i + it.c.length })
    val chars = CharArray(totalLength) { ' ' }
    for (anchor in sorted) {
        for (j in anchor.c.indices) {
            val pos = anchor.i + j
            if (pos in chars.indices) chars[pos] = anchor.c[j]
        }
    }
    return String(chars)
}

/**
 * Transposes every anchor's chord text by [semitones], leaving [ChordAnchor.i]
 * completely untouched — the entire point of the anchor model over the
 * padded-string one (`docs/PLAN.md`'s own reasoning: a token changing
 * width, e.g. `F#`→`G`, no longer needs to shift every subsequent chord's
 * column, because position was never encoded as a column in the first
 * place).
 */
fun transposeChordAnchors(chords: List<ChordAnchor>, semitones: Int): List<ChordAnchor> {
    if (semitones == 0) return chords
    return chords.map { it.copy(c = transposeChordToken(it.c, semitones) ?: it.c) }
}
