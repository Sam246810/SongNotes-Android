package com.songnotes.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.ChordBarre
import com.songnotes.core.domain.ChordVoicing
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import com.songnotes.core.domain.SongMeta
import org.json.JSONArray
import org.json.JSONObject

/**
 * Room's persisted shape for [Song] -- one row per song, same "one JSON document
 * per song" granularity `:app`'s (pre-Phase-6) `SongStorage.kt` used, just as a
 * SQLCipher-encrypted table row instead of a plaintext file. [linesJson] stores
 * `lines` (with their nested per-chord anchors) as a single JSON blob rather than
 * a normalized child table -- lines are always read/written as a whole song, never
 * queried independently, so normalizing them would add join complexity with no
 * actual query benefit. `meta`'s fields are still flattened into real columns
 * (not folded into the blob) since a later pass filtering/sorting by, say, `key`
 * or `bpm` is plausible and those should be real indexable columns when that
 * happens, unlike the lines.
 *
 * [rev]/[deletedAt] (schema v2, Phase 7) mirror the remote `songs` table's own
 * `rev`/`deleted_at` columns exactly -- this row's last known position in the
 * same optimistic-concurrency scheme the desktop web app already uses (see
 * `docs/handoff/PHASE-07.md`), so [SupabaseSongsAdapter.updateWithRevCheck] can
 * be called with the value already sitting on this row, no separate lookup.
 * [pendingSync] marks a row with a local edit not yet confirmed pushed --
 * deliberately a single flag per row rather than the plan's literal "sync_queue"
 * table: with one outstanding local edit possible per song at a time (the UI
 * always edits the CURRENT row in place, same as the web app's own debounce-
 * per-song-id map, not an event log), a queue table would track information a
 * boolean already captures, for no real benefit at this scale.
 *
 * [remoteRev] is null until the first successful push confirms this song
 * actually exists on the server -- what tells [SyncWorker] whether to `insert`
 * (never pushed before) or `updateWithRevCheck` (exists remotely at this rev)
 * for a given pending row, without needing to infer it from a failed insert's
 * error response (a real Postgres constraint-violation exception is not a
 * reasonable substitute for tracked state on the routine "this song already
 * exists" path).
 */
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val bpm: Int,
    val key: String,
    val tuning: String,
    val capo: Int,
    val linesJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val rev: Int = 1,
    val deletedAt: Long? = null,
    val pendingSync: Boolean = false,
    val remoteRev: Int? = null,
    /** [Song.customChords], JSON-encoded the same way [linesJson] is -- always read/written whole, never queried. */
    val customChordsJson: String = "{}",
) {
    fun toDomain(): Song = Song(
        id = id,
        title = title,
        meta = SongMeta(bpm = bpm, key = key, tuning = tuning, capo = capo),
        lines = parseLinesJson(linesJson),
        customChords = parseCustomChordsJson(customChordsJson),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        /**
         * Builds a row from a domain [Song] with fresh sync bookkeeping (`rev = 1`,
         * not pending). Callers updating an EXISTING song should read the current
         * row first and increment its `rev`/set `pendingSync = true` themselves
         * (see `SongRepository.upsert`) -- this alone is only correct for a
         * genuinely new song.
         */
        fun fromDomain(song: Song): SongEntity = SongEntity(
            id = song.id,
            title = song.title,
            bpm = song.meta.bpm,
            key = song.meta.key,
            tuning = song.meta.tuning,
            capo = song.meta.capo,
            linesJson = linesToJson(song.lines),
            customChordsJson = customChordsToJson(song.customChords),
            createdAt = song.createdAt,
            updatedAt = song.updatedAt,
        )

        private fun customChordsToJson(customChords: Map<String, ChordVoicing>): String {
            val obj = JSONObject()
            for ((name, voicing) in customChords) {
                val voicingJson = JSONObject()
                    .put("frets", JSONArray(voicing.frets))
                    .put("baseFret", voicing.baseFret)
                voicing.barre?.let {
                    voicingJson.put("barre", JSONObject().put("fret", it.fret).put("fromString", it.fromString).put("toString", it.toString))
                }
                obj.put(name, voicingJson)
            }
            return obj.toString()
        }

        private fun parseCustomChordsJson(json: String): Map<String, ChordVoicing> {
            val obj = JSONObject(json)
            val result = LinkedHashMap<String, ChordVoicing>()
            for (name in obj.keys()) {
                val voicingJson = obj.getJSONObject(name)
                val fretsJson = voicingJson.getJSONArray("frets")
                val frets = (0 until fretsJson.length()).map { fretsJson.getInt(it) }
                val barreJson = voicingJson.optJSONObject("barre")
                val barre = barreJson?.let {
                    ChordBarre(fret = it.getInt("fret"), fromString = it.getInt("fromString"), toString = it.getInt("toString"))
                }
                result[name] = ChordVoicing(frets = frets, baseFret = voicingJson.getInt("baseFret"), barre = barre)
            }
            return result
        }

        private fun linesToJson(lines: List<SongLine>): String {
            val arr = JSONArray()
            for (line in lines) {
                val chordsJson = JSONArray()
                for (chord in line.chords) chordsJson.put(JSONObject().put("i", chord.i).put("c", chord.c))
                arr.put(JSONObject().put("id", line.id).put("lyrics", line.lyrics).put("chords", chordsJson))
            }
            return arr.toString()
        }

        private fun parseLinesJson(json: String): List<SongLine> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val lineJson = arr.getJSONObject(i)
                val chordsJson = lineJson.getJSONArray("chords")
                val chords = (0 until chordsJson.length()).map { c ->
                    val chordJson = chordsJson.getJSONObject(c)
                    ChordAnchor(i = chordJson.getInt("i"), c = chordJson.getString("c"))
                }
                SongLine(id = lineJson.getString("id"), lyrics = lineJson.getString("lyrics"), chords = chords)
            }
        }
    }
}
