package com.songnotes.android

import android.content.Context
import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import com.songnotes.core.domain.SongMeta
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists [Song]s as one JSON file per song under `filesDir/songs/<id>.json` —
 * same "small scoped storage class, no front-loaded data layer" precedent
 * as [com.songnotes.core.audio.CalibrationStore] and
 * [com.songnotes.core.audio.MultitrackProjectStorage], just for song
 * documents instead of calibration numbers or audio clips. Deliberately
 * plain, unencrypted local JSON — matches Phase 5.5's own scope ("local
 * only, no audio, no sync"). Room + SQLCipher (Phase 6) will replace this
 * storage mechanism, not the [Song] shape itself, which is already wire-
 * format-v2-compatible (see `Song.kt`'s doc comment) specifically so that
 * migration is a storage-layer swap, not a data-model rewrite.
 */
class SongStorage(context: Context) {
    private val songsDir = File(context.filesDir, "songs").also { it.mkdirs() }

    /** All saved songs, most recently updated first. Reads and parses every file — fine at this scale. */
    fun list(): List<Song> = songsDir.listFiles { f -> f.extension == "json" }
        ?.mapNotNull { runCatching { readSong(it) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        ?: emptyList()

    fun load(id: String): Song? {
        val file = File(songsDir, "$id.json")
        if (!file.exists()) return null
        return runCatching { readSong(file) }.getOrNull()
    }

    fun save(song: Song) {
        songsDir.mkdirs() // idempotent — guards against the directory having been removed since construction
        File(songsDir, "${song.id}.json").writeText(writeSong(song).toString())
    }

    fun delete(id: String) {
        File(songsDir, "$id.json").delete()
    }

    fun newSongId(): String = UUID.randomUUID().toString()

    private fun writeSong(song: Song): JSONObject {
        val linesJson = JSONArray()
        for (line in song.lines) {
            val chordsJson = JSONArray()
            for (chord in line.chords) {
                chordsJson.put(JSONObject().put("i", chord.i).put("c", chord.c))
            }
            linesJson.put(
                JSONObject()
                    .put("id", line.id)
                    .put("lyrics", line.lyrics)
                    .put("chords", chordsJson),
            )
        }
        return JSONObject()
            .put("id", song.id)
            .put("title", song.title)
            .put(
                "meta",
                JSONObject()
                    .put("bpm", song.meta.bpm)
                    .put("key", song.meta.key)
                    .put("tuning", song.meta.tuning)
                    .put("capo", song.meta.capo),
            )
            .put("lines", linesJson)
            .put("createdAt", song.createdAt)
            .put("updatedAt", song.updatedAt)
    }

    private fun readSong(file: File): Song {
        val root = JSONObject(file.readText())
        val metaJson = root.optJSONObject("meta")
        val linesJson = root.getJSONArray("lines")
        val lines = (0 until linesJson.length()).map { i ->
            val lineJson = linesJson.getJSONObject(i)
            val chordsJson = lineJson.getJSONArray("chords")
            val chords = (0 until chordsJson.length()).map { c ->
                val chordJson = chordsJson.getJSONObject(c)
                ChordAnchor(i = chordJson.getInt("i"), c = chordJson.getString("c"))
            }
            SongLine(id = lineJson.getString("id"), lyrics = lineJson.getString("lyrics"), chords = chords)
        }
        return Song(
            id = root.getString("id"),
            title = root.getString("title"),
            meta = SongMeta(
                bpm = metaJson?.optInt("bpm", 0) ?: 0,
                key = metaJson?.optString("key", "") ?: "",
                tuning = metaJson?.optString("tuning", "") ?: "",
                capo = metaJson?.optInt("capo", 0) ?: 0,
            ),
            lines = lines,
            createdAt = root.getLong("createdAt"),
            updatedAt = root.getLong("updatedAt"),
        )
    }
}
