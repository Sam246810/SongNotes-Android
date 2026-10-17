package com.songnotes.core.domain

/**
 * Editing rules for a chords track, where a column is a position over the
 * lyric below rather than a place in a sentence. Applied to every edit of the
 * chords field: [oldText] is the line before, [newText]/[newCaret] what the
 * text field produced. Returns the line and caret to use instead.
 *
 * - Typing a chord name (an insert containing anything but spaces) eats as
 *   many spaces right after it as it added, so later chords stay over their
 *   words -- but always leaves one space before the next chord, so two names
 *   never fuse. Spaces at the end of the line are padding and can all go.
 * - Deleting text that contains a chord name puts spaces back in its place,
 *   so later chords don't slide left either.
 * - Typing or deleting only spaces is a plain insert/delete: that's how a
 *   chord gets nudged left or right.
 * - A replacement (a selection typed over, or the keyboard swapping its
 *   composing text "A" -> "Am") is a delete and an insert in one.
 *
 * Without this the field behaved like any text field, and adding "Am"
 * between a C and a G pushed the G two columns off the word it was over.
 */
fun applyChordOverwrite(oldText: String, newText: String, newCaret: Int): Pair<String, Int> {
    if (oldText == newText) return newText to newCaret
    val (start, oldEnd, newEnd) = locateEdit(oldText, newText, newCaret)
    val removed = oldText.substring(start, oldEnd)
    val inserted = newText.substring(start, newEnd)
    val after = oldText.substring(oldEnd)
    val growth = inserted.length - removed.length

    return when {
        growth > 0 && inserted.any { it != ' ' } -> {
            val leadingSpaces = after.length - after.trimStart(' ').length
            val eatable = if (leadingSpaces == after.length) leadingSpaces else (leadingSpaces - 1).coerceAtLeast(0)
            val eat = minOf(growth, eatable)
            (oldText.substring(0, start) + inserted + after.substring(eat)) to (start + inserted.length)
        }
        growth < 0 && removed.any { it != ' ' } ->
            (oldText.substring(0, start) + inserted + " ".repeat(-growth) + after) to (start + inserted.length)
        else -> newText to newCaret
    }
}

/**
 * Where [oldText] became [newText]: `(start, oldEnd, newEnd)`, the changed
 * span in each. The caret pins down a plain insert or delete even inside a
 * run of identical characters (typing a space into a run of spaces), where a
 * text diff alone can't tell which one changed; anything else falls back to
 * the longest common prefix and suffix.
 */
private fun locateEdit(oldText: String, newText: String, newCaret: Int): Triple<Int, Int, Int> {
    val delta = newText.length - oldText.length
    if (delta > 0) {
        val start = newCaret - delta
        if (start in 0..oldText.length && newCaret <= newText.length &&
            newText.regionMatches(0, oldText, 0, start) &&
            newText.regionMatches(newCaret, oldText, start, oldText.length - start)
        ) return Triple(start, start, newCaret)
    } else if (delta < 0) {
        val start = newCaret
        val oldEnd = start - delta
        if (start in 0..newText.length && oldEnd <= oldText.length &&
            newText.regionMatches(0, oldText, 0, start) &&
            newText.regionMatches(start, oldText, oldEnd, oldText.length - oldEnd)
        ) return Triple(start, oldEnd, start)
    }
    var prefix = 0
    while (prefix < oldText.length && prefix < newText.length && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(oldText.length, newText.length) - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++
    return Triple(prefix, oldText.length - suffix, newText.length - suffix)
}
