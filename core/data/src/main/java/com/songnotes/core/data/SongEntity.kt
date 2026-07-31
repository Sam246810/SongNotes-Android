package com.songnotes.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.songnotes.core.domain.ChordAnchor
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
) {
    fun toDomain(): Song = Song(
        id = id,
        title = title,
        meta = SongMeta(bpm = bpm, key = key, tuning = tuning, capo = capo),
        lines = parseLinesJson(linesJson),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(song: Song): SongEntity = SongEntity(
            id = song.id,
            title = song.title,
            bpm = song.meta.bpm,
            key = song.meta.key,
            tuning = song.meta.tuning,
            capo = song.meta.capo,
            linesJson = linesToJson(song.lines),
            createdAt = song.createdAt,
            updatedAt = song.updatedAt,
        )

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
