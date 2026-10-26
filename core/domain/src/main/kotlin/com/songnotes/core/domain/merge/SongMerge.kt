package com.songnotes.core.domain.merge

import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.ChordVoicing
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import com.songnotes.core.domain.SongMeta

/**
 * Three-way merge of two devices' versions of one song against the version
 * both started from (docs/SYNC-MERGE.md has the rules in plain words and
 * worked examples; WIRE-FORMAT-v2.md section 5 the algorithm).
 *
 * [base] may be null when it isn't known (songs edited before bases were
 * stored): then nothing can be recognised as deleted, so everything either
 * side has is kept.
 *
 * Pure and deterministic. Kotlin twin of the web app's `src/sync/songMerge.js`
 * -- keep them in step, function for function; spec/song-merge.json (generated
 * by the web repo, run here by SongMergeGoldenFixtureTest) pins both to the
 * same results.
 */
fun mergeSongs(base: Song?, local: Song, remote: Song): Song {
    val fieldEditedAt = LinkedHashMap<String, Long>()
    val values = HashMap<String, Any>()
    for (f in STAMPED_FIELDS) {
        val l = fieldValue(local, f)
        val r = fieldValue(remote, f)
        val pick = merge3(
            hasBase = base != null,
            b = base?.let { fieldValue(it, f) },
            l = l,
            r = r,
            equals = { x, y -> x == y },
            lStamp = fieldStamp(local, f),
            rStamp = fieldStamp(remote, f),
            canon = { canonicalValue(it!!) },
        )
        values[f] = if (pick == Pick.R) r else l
        chosenStamp(pick, validStamp(local.fieldEditedAt[f]), validStamp(remote.fieldEditedAt[f]))?.let { fieldEditedAt[f] = it }
    }
    val (customChords, customChordsEditedAt) = mergeCustomChords(base, local, remote)

    return local.copy(
        title = values.getValue("title") as String,
        meta = SongMeta(
            bpm = values.getValue("bpm") as Int,
            key = values.getValue("key") as String,
            tuning = values.getValue("tuning") as String,
            capo = values.getValue("capo") as Int,
        ),
        lines = mergeLines(base, local, remote),
        customChords = customChords,
        createdAt = minOf(local.createdAt, remote.createdAt),
        updatedAt = maxOf(local.updatedAt, remote.updatedAt),
        fieldEditedAt = fieldEditedAt,
        customChordsEditedAt = customChordsEditedAt,
    )
}

// -- Values (fields, custom chords) ----------------------------------------

private enum class Pick { L, R, SAME }

/**
 * Which side's value a three-way merge keeps. Only one side changed it -> that
 * side. Both changed it differently -> the larger stamp, then the greater
 * canonical form.
 */
private fun <T> merge3(
    hasBase: Boolean,
    b: T?,
    l: T?,
    r: T?,
    equals: (T?, T?) -> Boolean,
    lStamp: Long,
    rStamp: Long,
    canon: (T?) -> String,
): Pick {
    if (equals(l, r)) return Pick.SAME
    if (hasBase && equals(l, b)) return Pick.R
    if (hasBase && equals(r, b)) return Pick.L
    if (lStamp != rStamp) return if (lStamp > rStamp) Pick.L else Pick.R
    return if (canon(l) > canon(r)) Pick.L else Pick.R
}

private fun chosenStamp(pick: Pick, l: Long?, r: Long?): Long? = when (pick) {
    Pick.L -> l
    Pick.R -> r
    Pick.SAME -> if (l == null) r else if (r == null) l else maxOf(l, r)
}

private fun mergeCustomChords(base: Song?, local: Song, remote: Song): Pair<Map<String, ChordVoicing>, Map<String, Long>> {
    val b = base?.customChords ?: emptyMap()
    val l = local.customChords
    val r = remote.customChords
    val names = (b.keys + l.keys + r.keys).toSortedSet()
    val customChords = LinkedHashMap<String, ChordVoicing>()
    val stamps = HashMap<String, Long>()
    for (name in names) {
        val pick = if (base == null && (l[name] == null) != (r[name] == null)) {
            // No base: a voicing only one side has can't be told apart from one
            // the other side removed -- keep it.
            if (l[name] == null) Pick.R else Pick.L
        } else {
            merge3(
                hasBase = base != null,
                b = b[name],
                l = l[name],
                r = r[name],
                equals = { x, y -> x == y },
                lStamp = chordStamp(local, name),
                rStamp = chordStamp(remote, name),
                canon = ::canonicalVoicing,
            )
        }
        val value = if (pick == Pick.R) r[name] else l[name]
        if (value != null) customChords[name] = value
        chosenStamp(pick, validStamp(local.customChordsEditedAt[name]), validStamp(remote.customChordsEditedAt[name]))
            ?.let { stamps[name] = it }
    }
    // Removal stamps for names no side has any more still have to survive.
    for (song in listOf(local, remote)) {
        for ((name, stamp) in song.customChordsEditedAt) {
            if (name !in names && validStamp(stamp) != null) stamps[name] = maxOf(stamps[name] ?: 0L, stamp)
        }
    }
    return customChords to stamps.toSortedMap()
}

// -- Lines -----------------------------------------------------------------

private const val HEAD = "\u0000head"

private class LineContext(
    val local: Song,
    val remote: Song,
    val lById: Map<String, SongLine>,
    val rById: Map<String, SongLine>,
    val taken: MutableSet<String>,
)

private fun mergeLines(base: Song?, local: Song, remote: Song): List<SongLine> {
    val lLines = local.lines
    val rLines = remote.lines
    val lById = lLines.associateBy { it.id }
    val rById = rLines.associateBy { it.id }
    val bById = base?.lines?.associateBy { it.id }
    // Without a base, the lines both sides have stand in for it (in local's
    // order) -- with no way to tell a deletion from an addition.
    val baseIds = base?.lines?.map { it.id } ?: lLines.filter { it.id in rById }.map { it.id }
    val baseIdSet = baseIds.toSet()

    val ctx = LineContext(local, remote, lById, rById, (baseIds + lById.keys + rById.keys).toMutableSet())

    // Each surviving base line, and the losing version of a clash right after it.
    val resolved = HashMap<String, List<SongLine>>()
    for (id in baseIds) {
        val l = lById[id]
        val r = rById[id]
        val b = bById?.get(id)
        if (l != null && r != null) resolved[id] = mergeLineContent(b, l, r, ctx)
        else if (l != null && !lineContentEquals(l, b)) resolved[id] = listOf(l) // deleted remotely, edited here
        else if (r != null && !lineContentEquals(r, b)) resolved[id] = listOf(r) // deleted here, edited remotely
    }

    // Lines added on one side, grouped by the base line they follow there.
    val lGroups = addedGroups(lLines, baseIdSet)
    val rGroups = addedGroups(rLines, baseIdSet)
    // An added id on both sides is one line, merged without a base.
    val addedOnBoth = HashMap<String, List<SongLine>>()
    for (line in lLines) {
        val other = rById[line.id]
        if (line.id !in baseIdSet && other != null) addedOnBoth[line.id] = mergeLineContent(null, line, other, ctx)
    }

    val out = ArrayList<SongLine>()
    val emitted = HashSet<String>()
    fun emit(lines: List<SongLine>) {
        for (line in lines) if (emitted.add(line.id)) out.add(line)
    }
    fun emitGroups(anchor: String) {
        val gl = lGroups[anchor] ?: emptyList()
        val gr = rGroups[anchor] ?: emptyList()
        var first = gl
        var second = gr
        if (gl.isNotEmpty() && gr.isNotEmpty()) {
            val sl = lineStamp(local, gl[0])
            val sr = lineStamp(remote, gr[0])
            val remoteFirst = if (sl != sr) sr < sl else gr[0].id < gl[0].id
            if (remoteFirst) {
                first = gr
                second = gl
            }
        }
        for (line in first + second) emit(addedOnBoth[line.id] ?: listOf(line))
    }

    emitGroups(HEAD)
    for (id in baseIds) {
        resolved[id]?.let { emit(it) }
        emitGroups(id)
    }
    return out
}

private fun addedGroups(lines: List<SongLine>, baseIdSet: Set<String>): Map<String, List<SongLine>> {
    val groups = LinkedHashMap<String, MutableList<SongLine>>()
    var anchor = HEAD
    for (line in lines) {
        if (line.id in baseIdSet) anchor = line.id
        else groups.getOrPut(anchor) { ArrayList() }.add(line)
    }
    return groups
}

/** A line both sides have: [merged] or, on a true clash, [winner, loser]. */
private fun mergeLineContent(b: SongLine?, l: SongLine, r: SongLine, ctx: LineContext): List<SongLine> {
    if (b != null && lineContentEquals(l, b)) return listOf(r)
    if (b != null && lineContentEquals(r, b)) return listOf(l)
    val stamp = chosenStamp(Pick.SAME, validStamp(l.editedAt), validStamp(r.editedAt))
    if (lineContentEquals(l, r)) return listOf(l.copy(editedAt = stamp))

    val lStamp = lineStamp(ctx.local, l)
    val rStamp = lineStamp(ctx.remote, r)
    if (b != null) {
        mergeLineText(b, l, r, lStamp, rStamp)?.let { (lyrics, chords) ->
            return listOf(SongLine(id = l.id, lyrics = lyrics, chords = chords, editedAt = stamp))
        }
    }

    // True clash: keep both. The newer edit keeps the id and goes first.
    val lWins = when {
        lStamp != rStamp -> lStamp > rStamp
        l.lyrics != r.lyrics -> l.lyrics > r.lyrics
        else -> canonicalChords(sortedChords(l.chords)) > canonicalChords(sortedChords(r.chords))
    }
    val winner = if (lWins) l else r
    val loser = if (lWins) r else l
    val loserId = clashLoserId(l.id, loser, ctx) ?: return listOf(winner) // already present as its own line
    return listOf(winner, loser.copy(id = loserId))
}

/**
 * The id the losing version of a clash gets. Derived from its content, so two
 * devices resolving the same clash produce the same line rather than two.
 * Null if a line with that id and content already exists on either side.
 */
private fun clashLoserId(id: String, loser: SongLine, ctx: LineContext): String? {
    val root = id.substringBefore('~')
    val canonical = "$root~${fnv1a32("${loser.lyrics}\u0000${canonicalChords(sortedChords(loser.chords))}")}"
    for (existing in listOf(ctx.lById[canonical], ctx.rById[canonical])) {
        if (existing != null && lineContentEquals(existing, loser)) return null
    }
    var candidate = canonical
    var n = 2
    while (candidate in ctx.taken) candidate = "$canonical~${n++}"
    ctx.taken.add(candidate)
    return candidate
}

/** FNV-1a, 32-bit, over UTF-16 code units; 8 lowercase hex digits. */
fun fnv1a32(text: String): String {
    var h = 0x811c9dc5L
    for (ch in text) {
        h = h xor ch.code.toLong()
        h = (h * 0x01000193L) and 0xffffffffL
    }
    return h.toString(16).padStart(8, '0')
}

// -- One line's words and chords -------------------------------------------

/**
 * Word-level merge of a line both sides edited, with each chord carried along
 * with the word it sits on. Null when both sides changed the same words
 * differently (or the line is too long to diff) -- a clash.
 */
private fun mergeLineText(b: SongLine, l: SongLine, r: SongLine, lStamp: Long, rStamp: Long): Pair<String, List<ChordAnchor>>? {
    val tB = tokenize(b.lyrics)
    val tL = tokenize(l.lyrics)
    val tR = tokenize(r.lyrics)
    val chunks = diff3(tB, tL, tR) ?: return null
    if (chunks.any { !it.stable && it.take == null }) return null

    val sides = mapOf(
        Side.B to sideInfo(b, tB, chunks, Side.B),
        Side.L to sideInfo(l, tL, chunks, Side.L),
        Side.R to sideInfo(r, tR, chunks, Side.R),
    )
    val newer: Side? = if (lStamp != rStamp) (if (lStamp > rStamp) Side.L else Side.R) else null
    fun pickChordSet(vb: String, vl: String, vr: String): String = when {
        vl == vr -> vl
        vl == vb -> vr
        vr == vb -> vl
        newer != null -> if (newer == Side.L) vl else vr
        else -> if (vl > vr) vl else vr
    }

    val lyrics = StringBuilder()
    val chords = ArrayList<ChordAnchor>()
    chunks.forEachIndexed { k, chunk ->
        val from = if (chunk.stable) Side.L else chunk.take!!
        val tokens = if (from == Side.L) tL else tR
        val range = chunk.on(from)
        val text = tokens.subList(range.start, range.end).joinToString("")
        val outStart = lyrics.length
        lyrics.append(text)

        if (chunk.stable) {
            // Same words on all three sides: merge chord by chord position.
            val keys = sides.getValue(Side.B).chunks[k].keys + sides.getValue(Side.L).chunks[k].keys + sides.getValue(Side.R).chunks[k].keys
            for (offset in keys) {
                val set = pickChordSet(
                    sides.getValue(Side.B).chunks[k][offset] ?: "",
                    sides.getValue(Side.L).chunks[k][offset] ?: "",
                    sides.getValue(Side.R).chunks[k][offset] ?: "",
                )
                pushChords(chords, outStart + offset, set)
            }
            return@forEachIndexed
        }

        // Words changed here: the changing side's chords come with its words,
        // unless the other side also changed chords in this span -- then the
        // newer edit's chords win, kept inside the span.
        val other = if (from == Side.L) Side.R else Side.L
        val regionB = regionKey(sides.getValue(Side.B).chunks[k])
        val regionX = regionKey(sides.getValue(from).chunks[k])
        val regionY = regionKey(sides.getValue(other).chunks[k])
        var winner = from
        if (regionY != regionB && regionY != regionX) {
            winner = newer ?: if (regionX > regionY) from else other
        }
        val clampTo = maxOf(text.length - 1, 0)
        for ((offset, set) in sides.getValue(winner).chunks[k]) {
            pushChords(chords, outStart + if (winner == from) offset else minOf(offset, clampTo), set)
        }
    }

    // Chords past the end of the lyrics (a chord-only tail): by overshoot.
    val endKeys = sides.getValue(Side.B).end.keys + sides.getValue(Side.L).end.keys + sides.getValue(Side.R).end.keys
    for (overshoot in endKeys) {
        val set = pickChordSet(
            sides.getValue(Side.B).end[overshoot] ?: "",
            sides.getValue(Side.L).end[overshoot] ?: "",
            sides.getValue(Side.R).end[overshoot] ?: "",
        )
        pushChords(chords, lyrics.length + overshoot, set)
    }

    return lyrics.toString() to sortedChords(chords)
}

// Chord names at one position, as one comparable string ("" = none).
private fun chordSetKey(names: List<String>): String = names.sorted().joinToString("\u0001")

private fun pushChords(out: MutableList<ChordAnchor>, i: Int, set: String) {
    if (set.isEmpty()) return
    for (c in set.split('\u0001')) out.add(ChordAnchor(i = i, c = c))
}

private fun regionKey(map: Map<Int, String>): String =
    map.entries.sortedBy { it.key }.joinToString("\u0002") { "${it.key}:${it.value}" }

private class SideChords(val chunks: List<Map<Int, String>>, val end: Map<Int, String>)

/**
 * One side's chords, bucketed per chunk as offset-within-chunk -> chord set,
 * plus chords past the end of the lyrics as overshoot -> chord set.
 */
private fun sideInfo(line: SongLine, tokens: List<String>, chunks: List<Chunk>, side: Side): SideChords {
    val starts = IntArray(tokens.size + 1)
    for (k in tokens.indices) starts[k + 1] = starts[k] + tokens[k].length
    val ranges = chunks.map { val r = it.on(side); starts[r.start] to starts[r.end] }
    val byChunk = chunks.map { LinkedHashMap<Int, MutableList<String>>() }
    val end = LinkedHashMap<Int, MutableList<String>>()
    val length = line.lyrics.length
    for (anchor in line.chords) {
        if (anchor.i >= length) {
            end.getOrPut(anchor.i - length) { ArrayList() }.add(anchor.c)
            continue
        }
        val k = ranges.indexOfFirst { (s, e) -> anchor.i in s until e }
        byChunk[k].getOrPut(anchor.i - ranges[k].first) { ArrayList() }.add(anchor.c)
    }
    fun finish(map: Map<Int, List<String>>): Map<Int, String> = map.mapValues { chordSetKey(it.value) }
    return SideChords(byChunk.map(::finish), finish(end))
}
