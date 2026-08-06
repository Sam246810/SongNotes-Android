package com.songnotes.core.data

import android.os.Build
import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import com.songnotes.core.domain.SongMeta
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The outbox push + incremental pull the plan's "Phase 7" section names --
 * the Kotlin twin of the desktop web app's `CloudSongsRepository`
 * (`songsRepository.js`), same rev-based optimistic concurrency, same
 * tombstone/conflict-copy handling (see `docs/handoff/PHASE-07.md`). Deals in
 * plain suspend functions independent of WorkManager so it's testable without
 * a real background job -- `SongSyncWorker` (in `:app`) is a thin
 * `CoroutineWorker` wrapper around [sync].
 *
 * Song content JSON matches exactly what `_buildRow`/`_decryptRow` produce in
 * `songsRepository.js`: `{title, lines:[{id,lyrics,chords:[{i,c}]}], bpm, key,
 * tuning, capo, customChords?, createdAt, updatedAt}` -- `lines[].chords` is
 * already `:core:domain`'s native `ChordAnchor` shape, so unlike the web app
 * (which converts from its own padded-string model), no conversion is needed
 * on this side at all.
 */
class SyncEngine(
    private val dao: SongDao,
    private val adapter: SongsRemoteAdapter = SupabaseSongsAdapter(),
) {
    suspend fun sync(userId: String, dek: ByteArray) {
        pushPending(userId, dek)
        pull(userId, dek)
    }

    private suspend fun pushPending(userId: String, dek: ByteArray) {
        for (entity in dao.getPendingSync()) {
            val preservedCustomChords = entity.remoteRev?.let { fetchCustomChords(entity.id, dek) }
            val row = buildRow(entity, userId, dek, preservedCustomChords)

            if (entity.remoteRev == null) {
                val inserted = adapter.insert(row)
                dao.upsert(entity.copy(pendingSync = false, remoteRev = inserted.rev))
            } else {
                when (val result = adapter.updateWithRevCheck(entity.id, row, entity.remoteRev)) {
                    is UpdateResult.Success -> dao.upsert(entity.copy(pendingSync = false, remoteRev = result.row.rev))
                    is UpdateResult.Conflict -> {
                        writeConflictCopy(entity)
                        // The original row lost the race -- leave it pointing at whatever's now
                        // remotely canonical; the next pull() picks up the winner's real content.
                        dao.upsert(entity.copy(pendingSync = false))
                    }
                }
            }
        }
    }

    private suspend fun pull(userId: String, dek: ByteArray) {
        for (row in adapter.list(userId)) {
            val local = dao.getByIdIncludingDeleted(row.id)
            if (local != null && (local.rev >= row.rev || local.pendingSync)) continue // local is at least as new, or has an edit not yet resolved

            if (row.deleted_at != null) {
                if (local != null) dao.upsert(local.copy(deletedAt = parseIso(row.deleted_at), rev = row.rev, remoteRev = row.rev, pendingSync = false))
                continue
            }

            val contentJson = JSONObject(decryptContentJson(dek, row.content.toContentEnvelope()))
            val song = songFromContentJson(row.id, contentJson)
            dao.upsert(SongEntity.fromDomain(song).copy(rev = row.rev, deletedAt = null, pendingSync = false, remoteRev = row.rev))
        }
    }

    /** Reads the current remote row's `customChords` (if any) so a push from a device with no customChords UI never destroys it. */
    private suspend fun fetchCustomChords(id: String, dek: ByteArray): JSONObject? {
        val remote = adapter.getById(id) ?: return null
        val contentJson = JSONObject(decryptContentJson(dek, remote.content.toContentEnvelope()))
        return contentJson.optJSONObject("customChords")
    }

    private fun buildRow(entity: SongEntity, userId: String, dek: ByteArray, preservedCustomChords: JSONObject?): SongRow {
        val contentJson = JSONObject()
            .put("title", entity.title)
            .put("lines", linesToJson(entity.toDomain().lines))
            .put("bpm", entity.bpm)
            .put("key", entity.key)
            .put("tuning", entity.tuning)
            .put("capo", entity.capo)
            .put("createdAt", toIso(entity.createdAt))
            .put("updatedAt", toIso(entity.updatedAt))
        if (preservedCustomChords != null) contentJson.put("customChords", preservedCustomChords)

        val envelope = encryptContentJson(dek, contentJson.toString())
        return SongRow(
            id = entity.id,
            user_id = userId,
            encrypted = true,
            content = envelope.toJsonElement(),
            is_locked = false,
            rev = entity.rev,
            deleted_at = entity.deletedAt?.let(::toIso),
            created_at = toIso(entity.createdAt),
            updated_at = toIso(entity.updatedAt),
        )
    }

    /**
     * A lost optimistic-concurrency race means someone else's write already
     * landed with the rev this device expected -- same "keep both, never
     * silently drop an edit" answer as the web app's own conflict-copy path.
     * `Build.MODEL` (e.g. "SM-F956W") gives a genuinely specific device name,
     * matching the plan's own example ("conflict copy — Pixel 8, 14:22")
     * literally, unlike the web app's coarser "Chrome on Windows" (a browser
     * has no single device identity to report).
     */
    private suspend fun writeConflictCopy(entity: SongEntity) {
        val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val title = entity.title.ifBlank { "Untitled" }
        val conflictEntity = entity.copy(
            id = UUID.randomUUID().toString(),
            title = "$title (conflict copy — ${Build.MODEL}, $timeLabel)",
            rev = 1,
            deletedAt = null,
            pendingSync = true,
            remoteRev = null,
        )
        dao.upsert(conflictEntity)
    }

    private fun linesToJson(lines: List<SongLine>): JSONArray {
        val arr = JSONArray()
        for (line in lines) {
            val chordsJson = JSONArray()
            for (chord in line.chords) chordsJson.put(JSONObject().put("i", chord.i).put("c", chord.c))
            arr.put(JSONObject().put("id", line.id).put("lyrics", line.lyrics).put("chords", chordsJson))
        }
        return arr
    }

    private fun songFromContentJson(id: String, json: JSONObject): Song {
        val linesJson = json.getJSONArray("lines")
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
            id = id,
            title = json.optString("title", ""),
            meta = SongMeta(
                bpm = json.optInt("bpm", 0),
                key = json.optString("key", ""),
                tuning = json.optString("tuning", ""),
                capo = json.optInt("capo", 0),
            ),
            lines = lines,
            createdAt = parseIso(json.getString("createdAt")),
            updatedAt = parseIso(json.getString("updatedAt")),
        )
    }
}

private fun toIso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

// Postgrest returns timestamptz columns as "+00:00"-offset text (e.g.
// "2026-08-06T22:43:22.991+00:00"), not the "Z"-suffixed form toIso() writes --
// OffsetDateTime.parse's default ISO_OFFSET_DATE_TIME format accepts both.
private fun parseIso(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
