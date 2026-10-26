package com.songnotes.core.domain.merge

import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.ChordVoicing
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine

/**
 * Per-line and per-field "last edited" stamps (docs/SYNC-MERGE.md,
 * WIRE-FORMAT-v2.md section 4): what lets a merge tell which of two devices
 * changed a given line or field more recently, when both changed it.
 *
 * Stamps are epoch milliseconds: [SongLine.editedAt], [Song.fieldEditedAt]
 * (`title`, `bpm`, `key`, `tuning`, `capo`) and [Song.customChordsEditedAt]
 * (removals included). Anything without a stamp counts as edited at the song's
 * `updatedAt`.
 *
 * Kotlin twin of the web app's `src/sync/editStamps.js` -- keep them in step.
 */

val STAMPED_FIELDS = listOf("title", "bpm", "key", "tuning", "capo")

/** JavaScript's Number.MAX_SAFE_INTEGER: the largest stamp both platforms read exactly. */
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

/** A valid stamp, or null: a whole number of ms from 0 to 2^53 - 1. */
fun validStamp(value: Long?): Long? = value?.takeIf { it in 0..MAX_SAFE_INTEGER }

/** The value of [song]'s [field] (one of [STAMPED_FIELDS]). */
fun fieldValue(song: Song, field: String): Any = when (field) {
    "title" -> song.title
    "bpm" -> song.meta.bpm
    "key" -> song.meta.key
    "tuning" -> song.meta.tuning
    "capo" -> song.meta.capo
    else -> error("not a stamped field: $field")
}

fun fieldStamp(song: Song, field: String): Long = validStamp(song.fieldEditedAt[field]) ?: song.updatedAt

fun chordStamp(song: Song, name: String): Long = validStamp(song.customChordsEditedAt[name]) ?: song.updatedAt

fun lineStamp(song: Song, line: SongLine): Long = validStamp(line.editedAt) ?: song.updatedAt

private val anchorOrder = compareBy<ChordAnchor>({ it.i }, { it.c })

/** A line's chords in a canonical order, so equal chord sets compare equal. */
fun sortedChords(chords: List<ChordAnchor>): List<ChordAnchor> = chords.sortedWith(anchorOrder)

fun chordsEqual(a: List<ChordAnchor>, b: List<ChordAnchor>): Boolean =
    a === b || (a.size == b.size && sortedChords(a) == sortedChords(b))

/** Same words and same chords; ignores the id and the stamp. */
fun lineContentEquals(a: SongLine, b: SongLine?): Boolean =
    b != null && (a === b || (a.lyrics == b.lyrics && chordsEqual(a.chords, b.chords)))

/**
 * Same song content: title, meta, custom chords and lines (ids, words,
 * chords, order). Ignores stamps and createdAt/updatedAt -- the "did anything
 * actually change" test every sync decision uses.
 */
fun contentEquals(a: Song?, b: Song?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    if (a.title != b.title || a.meta != b.meta || a.customChords != b.customChords) return false
    if (a.lines.size != b.lines.size) return false
    return a.lines.indices.all { k -> a.lines[k].id == b.lines[k].id && lineContentEquals(a.lines[k], b.lines[k]) }
}

private fun bump(previous: Long?, now: Long): Long = maxOf(now, (validStamp(previous) ?: -1L) + 1)

/**
 * [next] with a fresh stamp on everything that differs from [prev]: each
 * changed field, custom chord (added, changed or removed) and line (new id,
 * or same id with different words/chords). Unchanged lines keep their stamp.
 * Stamps never go backwards on a device -- a changed item gets
 * max(now, its previous stamp + 1) -- so a clock set back can't make a newer
 * edit look older than the one it replaced.
 */
fun stampEdits(prev: Song?, next: Song, now: Long): Song {
    if (prev == null) return next

    val fieldEditedAt = LinkedHashMap(next.fieldEditedAt.ifEmpty { prev.fieldEditedAt })
    for (f in STAMPED_FIELDS) {
        if (fieldValue(prev, f) != fieldValue(next, f)) fieldEditedAt[f] = bump(fieldEditedAt[f], now)
    }

    val customChordsEditedAt = LinkedHashMap(next.customChordsEditedAt.ifEmpty { prev.customChordsEditedAt })
    for (name in prev.customChords.keys + next.customChords.keys) {
        if (prev.customChords[name] != next.customChords[name]) {
            customChordsEditedAt[name] = bump(customChordsEditedAt[name], now)
        }
    }

    val prevById = prev.lines.associateBy { it.id }
    val lines = next.lines.map { line ->
        val before = prevById[line.id]
        if (before != null && lineContentEquals(line, before)) {
            line.copy(editedAt = validStamp(line.editedAt) ?: validStamp(before.editedAt))
        } else {
            line.copy(editedAt = bump(before?.editedAt, now))
        }
    }

    return next.copy(lines = lines, fieldEditedAt = fieldEditedAt, customChordsEditedAt = customChordsEditedAt)
}

// -- Canonical JSON (tie-breaks and clash ids must match the web byte for byte) --

/** A string as JavaScript's JSON.stringify writes it. */
fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (k in s.indices) {
        val ch = s[k]
        when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\b' -> sb.append("\\b")
            ch == '\u000C' -> sb.append("\\f")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch < ' ' -> sb.append("\\u").append(String.format("%04x", ch.code))
            ch.isHighSurrogate() && !(k + 1 < s.length && s[k + 1].isLowSurrogate()) ->
                sb.append("\\u").append(String.format("%04x", ch.code))
            ch.isLowSurrogate() && !(k > 0 && s[k - 1].isHighSurrogate()) ->
                sb.append("\\u").append(String.format("%04x", ch.code))
            else -> sb.append(ch)
        }
    }
    return sb.append('"').toString()
}

/** A field value as JSON.stringify writes it. */
fun canonicalValue(value: Any): String = when (value) {
    is String -> jsonString(value)
    else -> value.toString()
}

/** The web's canonicalJson of a voicing (keys sorted); "" for none. */
fun canonicalVoicing(v: ChordVoicing?): String {
    if (v == null) return ""
    val barre = v.barre?.let { """"barre":{"fret":${it.fret},"fromString":${it.fromString},"toString":${it.toString}},""" } ?: ""
    return """{$barre"baseFret":${v.baseFret},"frets":[${v.frets.joinToString(",")}]}"""
}

/** The web's canonicalJson of a chord list: `[{"c":..,"i":..},...]`. */
fun canonicalChords(chords: List<ChordAnchor>): String =
    chords.joinToString(",", "[", "]") { """{"c":${jsonString(it.c)},"i":${it.i}}""" }
