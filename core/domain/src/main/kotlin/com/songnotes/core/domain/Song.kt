package com.songnotes.core.domain

/**
 * The Android app's song data model — shaped to match `docs/WIRE-FORMAT-v2.md`
 * §4's song document exactly (field names, per-chord anchors instead of a
 * padded-string chords track) even though Phase 5.5 doesn't touch sync or
 * encryption yet. Getting the shape right now means Phase 6/7's data layer
 * wraps this model in Room/crypto rather than having to redesign it —
 * `docs/PLAN.md`'s own locked-in decision: "Chord binding: Per-chord
 * anchors `{i, c}` — not the padded parallel string."
 *
 * [customChords] (wire-format §4's user-authored voicing overrides) is
 * deliberately not modeled yet — nothing in Phase 5.5 renders a chord
 * diagram, that's Phase 8, so there's nothing yet that would read it. Add
 * it when Phase 8 needs it, same "front-load only when a phase's own
 * criterion needs it" judgment call already applied elsewhere in this
 * project.
 */

/** One chord placed at character index [i] of a [SongLine]'s `lyrics`, wire-format §4. */
data class ChordAnchor(val i: Int, val c: String)

/**
 * One line of a song. [chords] is sorted ascending by [ChordAnchor.i] and
 * may legitimately contain an anchor with `i > lyrics.length` (a chord
 * placed past a short lyric line's end, or on an instrumental-only line
 * with empty `lyrics`) — valid per wire-format §4, never clamped.
 */
data class SongLine(val id: String, val lyrics: String, val chords: List<ChordAnchor> = emptyList())

/** `bpm`/`capo` of 0 mean "unset" (wire-format §4's sentinel, not null). `key`/`tuning` of "" mean "unset". */
data class SongMeta(val bpm: Int = 0, val key: String = "", val tuning: String = "", val capo: Int = 0)

data class Song(
    val id: String,
    val title: String,
    val meta: SongMeta = SongMeta(),
    val lines: List<SongLine> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)
