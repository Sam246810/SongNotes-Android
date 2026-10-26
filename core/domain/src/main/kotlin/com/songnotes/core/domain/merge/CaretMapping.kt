package com.songnotes.core.domain.merge

/**
 * Where a caret at [offset] in [oldText] belongs after the text changed to
 * [newText] by an edit made elsewhere (a sync merge landing in the open
 * editor): unchanged text before the change keeps it in place, text after it
 * carries it along, and a caret inside the changed span goes to the end of
 * what replaced it. Twin of the web app's `mapOffsetThroughEdit`
 * (`src/utils/paragraphEditing.js`).
 */
fun mapOffsetThroughEdit(oldText: String, newText: String, offset: Int): Int {
    var start = 0
    while (start < oldText.length && start < newText.length && oldText[start] == newText[start]) start++
    var suffix = 0
    while (suffix < minOf(oldText.length, newText.length) - start &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) suffix++
    if (offset <= start) return minOf(offset, newText.length)
    if (offset >= oldText.length - suffix) return offset + newText.length - oldText.length
    return newText.length - suffix
}
