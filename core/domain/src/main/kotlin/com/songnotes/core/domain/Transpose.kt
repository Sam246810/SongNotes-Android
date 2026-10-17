package com.songnotes.core.domain

/**
 * Ported from the desktop web app's `src/utils/transpose.js` — same
 * "faithful line-by-line port, cross-checked against golden fixtures
 * generated from the real JS implementation" approach as `Chords.kt`. See
 * that file's doc comment for the shared reasoning.
 */

private val SHARP_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val FLAT_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

private val NOTE_TO_SEMITONE: Map<String, Int> = mapOf(
    "C" to 0, "C#" to 1, "Db" to 1, "D" to 2, "D#" to 3, "Eb" to 3, "E" to 4, "F" to 5,
    "F#" to 6, "Gb" to 6, "G" to 7, "G#" to 8, "Ab" to 8, "A" to 9, "A#" to 10, "Bb" to 10, "B" to 11,
)

/**
 * Shift a single note name (a chord's root, or a slash-bass note) by
 * [semitones], wrapping through the octave. Unrecognized input passes
 * through unchanged.
 */
private fun transposeNoteName(note: String, semitones: Int, preferFlats: Boolean = false): String {
    val semitone = NOTE_TO_SEMITONE[note] ?: return note
    val shifted = (((semitone + semitones) % 12) + 12) % 12
    return (if (preferFlats) FLAT_NAMES else SHARP_NAMES)[shifted]
}

/**
 * Transposes one chord token's root — and its slash-bass note, if it has
 * one (e.g. "D/F#") — by [semitones], spelled with sharps unless
 * [preferFlats] (an Android-only addition; the default keeps the web app's
 * always-sharps output that the golden fixtures check).
 * Everything else about the token (its quality suffix: m7, sus4, maj7,
 * ...) is left exactly as typed; only the note letters move. Does NOT
 * call [normalizeChordName] — it re-parses the root/accidental itself via
 * the same lightweight regex the JS version uses, independent of
 * normalization (see `Chords.kt`'s doc comment: these are two independent
 * parsers of a chord's leading note in the original implementation, and
 * the port preserves that).
 */
fun transposeChordToken(raw: String?, semitones: Int, preferFlats: Boolean = false): String? {
    if (raw.isNullOrEmpty() || semitones == 0) return raw
    val rootMatch = Regex("^([A-G])([#b]?)").find(raw) ?: return raw

    val wholeRootMatch = rootMatch.value
    val root = rootMatch.groupValues[1]
    val accidental = rootMatch.groupValues[2]
    val rest = raw.substring(wholeRootMatch.length)
    val newRoot = transposeNoteName(root + accidental, semitones, preferFlats)

    val bassMatch = Regex("/([A-G])([#b]?)\\s*$").find(rest)
    if (bassMatch != null) {
        val wholeBassMatch = bassMatch.value
        val bassRoot = bassMatch.groupValues[1]
        val bassAccidental = bassMatch.groupValues[2]
        val newBass = transposeNoteName(bassRoot + bassAccidental, semitones, preferFlats)
        val restBeforeBass = rest.substring(0, rest.length - wholeBassMatch.length)
        return "$newRoot$restBeforeBass/$newBass"
    }

    return newRoot + rest
}

/**
 * Transposes every recognized chord in a chords-track string by
 * [semitones], leaving whitespace and any non-chord text (section labels,
 * typos) alone. "Recognized" uses [CHORD_DB_KEYS] after
 * [normalizeChordName] — same gate the JS version's [CHORD_DB] membership
 * check applies — so this only ever touches tokens that would actually be
 * rendered as a recognized chord.
 */
fun transposeChordsLine(chordsLine: String?, semitones: Int, preferFlats: Boolean = false): String? {
    if (chordsLine.isNullOrEmpty() || semitones == 0) return chordsLine
    return Regex("\\S+").replace(chordsLine) { match ->
        val token = match.value
        val normalized = normalizeChordName(token)
        if (normalized.isEmpty() || normalized !in CHORD_DB_KEYS) {
            token
        } else {
            transposeChordToken(token, semitones, preferFlats) ?: token
        }
    }
}

/**
 * Whether a key is conventionally written with flats: F, Bb, Eb, Ab, Db
 * major and their relative minors Dm, Gm, Cm, Fm, Bbm, Ebm. Everything else
 * -- including the F#/Gb tie, which goes to F# -- uses sharps. Android-only;
 * the web app always spells with sharps (porting this there is a follow-up).
 */
fun prefersFlats(keyRootSemitone: Int, minor: Boolean): Boolean {
    val root = ((keyRootSemitone % 12) + 12) % 12
    return if (minor) root in setOf(2, 7, 0, 5, 10, 3) else root in setOf(5, 10, 3, 8, 1)
}

/** A key's root semitone and whether it's minor, or null if [key] doesn't read as a key ("G", "Em", "Bb major", "F# minor"). */
fun parseKey(key: String): Pair<Int, Boolean>? {
    val m = Regex("""^\s*([A-G])([#b]?)(.*)$""").find(key) ?: return null
    val semitone = NOTE_TO_SEMITONE[m.groupValues[1] + m.groupValues[2]] ?: return null
    val rest = m.groupValues[3].trim().lowercase()
    val minor = when {
        rest.isEmpty() || rest == "maj" || rest == "major" -> false
        rest == "m" || rest == "min" || rest == "minor" -> true
        else -> return null
    }
    return semitone to minor
}

/**
 * The song key [key] moved by [semitones], spelled the way that key is
 * written (see [prefersFlats]): "G" +1 is "Ab", "Em" +1 is "Fm", "G" -1 is
 * "F#". Anything after the root ("m", " minor") is kept as typed. Text that
 * doesn't read as a key comes back unchanged.
 */
fun transposeKey(key: String, semitones: Int): String {
    if (semitones == 0) return key
    val (semitone, minor) = parseKey(key) ?: return key
    val m = Regex("""^(\s*)([A-G][#b]?)""").find(key) ?: return key
    val target = semitone + semitones
    val root = (if (prefersFlats(target, minor)) FLAT_NAMES else SHARP_NAMES)[((target % 12) + 12) % 12]
    return m.groupValues[1] + root + key.substring(m.value.length)
}
