package com.songnotes.core.data

import android.os.Build
import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.ChordBarre
import com.songnotes.core.domain.ChordVoicing
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

/** What a [SyncEngine.sync] call actually did, for the caller to show the user -- Phase 13's manual-only model means a bare "it succeeded" spinner isn't enough. */
data class SyncOutcome(val pushed: Int, val pulled: Int, val conflictCopies: Int, val reIded: Int)

/** Internal accumulator for [SyncEngine.pushPending]'s per-row loop, folded into [SyncOutcome] by [SyncEngine.sync]. */
private data class PushResult(val pushed: Int, val reIded: Int, val conflictCopies: Int, val firstError: Throwable?)

/**
 * The outbox push + incremental pull the plan's "Phase 7" section names --
 * the Kotlin twin of the desktop web app's `CloudSongsRepository`
 * (`songsRepository.js`), same rev-based optimistic concurrency, same
 * tombstone/conflict-copy handling (see `docs/handoff/PHASE-07.md`). Deals in
 * plain suspend functions independent of WorkManager so it's testable without
 * a real background job -- `SongSyncWorker` (in `:app`) is a thin
 * `CoroutineWorker` wrapper around [sync].
 *
 * Phase 13: sync became local-first and strictly manual -- this class's own
 * push/pull/conflict logic barely changed in shape, but three real defects in
 * it went from survivable (an always-on background retry would eventually
 * paper over them) to severe (there is no automatic retry anymore, so a
 * silent failure here is silent forever): the conflict branch couldn't tell
 * "someone else won the race" from "the remote row is just gone" and would
 * duplicate every song on a DEK rotation; one bad row could abort an entire
 * push pass; and a conflict copy created mid-pass wasn't itself pushed until
 * some future sync that strictly-manual mode might never trigger. See
 * `docs/handoff/PHASE-13-local-first.md`.
 *
 * Song content JSON matches exactly what `_buildRow`/`_decryptRow` produce in
 * `songsRepository.js`: `{title, lines:[{id,lyrics,chords:[{i,c}]}], bpm, key,
 * tuning, capo, customChords?, createdAt, updatedAt}` -- `lines[].chords` is
 * already `:core:domain`'s native `ChordAnchor` shape, so unlike the web app
 * (which converts from its own padded-string model), no conversion is needed
 * on this side at all. Since Phase 8, `customChords` is likewise pushed/pulled
 * straight from/to [Song.customChords] -- no special preservation logic needed
 * (an earlier Phase 7 version of this class had to fetch-and-preserve the
 * remote row's customChords before every push, since Android's domain model
 * didn't carry the field at all yet; now that it does, the same
 * optimistic-concurrency check that protects every other field of [Song] from
 * a lost update protects this one too, since a local entity's customChords is
 * only ever stale if its `rev` is too -- which `updateWithRevCheck` already
 * catches).
 */
class SyncEngine(
    private val dao: SongDao,
    private val adapter: SongsRemoteAdapter = SupabaseSongsAdapter(),
) {
    /**
     * @param dekId the envelope `dekId` [dek] came from (see [KeySession]) --
     * optional and defaulting to null purely so existing call sites (this
     * class's own tests included) don't all need updating at once; a null
     * `dekId` just means every pushed row's `dek_id` column comes out null too
     * (same as any pre-Phase-12 row). [SongSyncWorker] is the one real caller
     * that supplies it, having already confirmed it against the account's live
     * envelope before calling this at all.
     * @param adopt Phase 13: true only on the first sync after a device enables
     * sync (or switches accounts). Runs [reconcileForAdoption] before pushing,
     * so every local song this device already has gets folded onto the account
     * without duplicating or overwriting anything already there. `false` for
     * every ordinary manual sync press after that.
     * @return a [SyncOutcome] the caller can show the user, and can throw the
     * first push failure encountered (after still completing the pull, on the
     * theory that "download what I can" beats "nothing happened") -- under
     * always-on background sync a swallowed failure just meant "try again in a
     * few minutes"; under Phase 13's strictly-manual model there is no
     * automatic next attempt, so a failure the user never sees is a failure
     * that never gets fixed.
     */
    suspend fun sync(userId: String, dek: ByteArray, dekId: String? = null, adopt: Boolean = false): SyncOutcome {
        if (adopt) reconcileForAdoption(userId, dek)

        var push = pushPending(userId, dek, dekId)
        // A first pass can leave freshly-pending rows behind that it never
        // itself pushes: writeConflictCopy() inserts a new pendingSync row
        // mid-loop, after the dao.getPendingSync() snapshot the loop already
        // started from; and a "remote row is simply gone" Conflict (see
        // pushPending below) re-queues the same row rather than retrying it
        // inline. Under always-on sync the next automatic pass caught both;
        // under strictly-manual sync (Phase 13) there may never BE a next pass
        // the user triggers. One unconditional, bounded extra pass -- not a
        // loop to a fixpoint -- catches both in the same sync() call. A row
        // this second pass itself re-queues (vanishingly unlikely: it would
        // need a second independent failure on the immediate retry) simply
        // waits for the user's next Sync press, same as any other failure.
        run {
            val second = pushPending(userId, dek, dekId)
            push = PushResult(
                pushed = push.pushed + second.pushed,
                reIded = push.reIded + second.reIded,
                conflictCopies = push.conflictCopies + second.conflictCopies,
                firstError = push.firstError ?: second.firstError,
            )
        }

        val pulled = pull(userId, dek)

        push.firstError?.let { throw it }
        return SyncOutcome(pushed = push.pushed, pulled = pulled, conflictCopies = push.conflictCopies, reIded = push.reIded)
    }

    /**
     * Folds every local song onto [userId]'s account the first time this
     * device enables sync (or re-enables it for a different account -- see
     * `SyncController.enableSyncFor`). Never overwrites or deletes a remote
     * row: a local id that collides with different remote content gets a new
     * local id instead, so both songs survive under the account rather than
     * one silently clobbering the other. See
     * `docs/handoff/PHASE-13-local-first.md` for the full algorithm writeup.
     */
    private suspend fun reconcileForAdoption(userId: String, dek: ByteArray) {
        for (entity in dao.getAllIncludingDeleted()) {
            if (entity.deletedAt != null && entity.remoteRev == null) {
                // Tombstoned locally, but this device never successfully pushed
                // it in the first place -- there is nothing on the account for
                // this delete to propagate to. Reclaim it instead of carrying a
                // permanent, unpushable tombstone.
                dao.deleteById(entity.id)
                continue
            }
            if (entity.remoteRev != null) continue // already has a proven remote lineage; nothing to reconcile

            val remote = adapter.getById(entity.id)
            if (remote == null) {
                continue // no id collision -- pushPending's normal insert path handles this cleanly
            }

            // This local id already exists remotely (RLS may have hidden a
            // DIFFERENT account's row with the same id from the getById() check
            // above -- pushPending's insert-collision fallback is the backstop
            // for that case, not this method). Decide whether it's the SAME
            // song (this device synced before -- a reinstall, or the
            // pre-Phase-13 remoteRev-drop defect erased the local pointer) or a
            // genuinely different song that merely shares a UUID.
            val isSameSong = try {
                val remoteJson = JSONObject(decryptContentJson(dek, remote.content.toContentEnvelope()))
                val remoteSong = songFromContentJson(remote.id, remoteJson)
                sameSongContent(entity.toDomain(), remoteSong)
            } catch (e: Exception) {
                false // undecryptable (different dek_id, corrupt) -- never assume it's safe to adopt
            }

            if (isSameSong) {
                dao.upsert(entity.copy(rev = remote.rev, remoteRev = remote.rev, pendingSync = false))
            } else {
                // Never touch the remote row. Give the local copy a new identity
                // instead, so both the existing account song and this device's
                // song survive -- pushPending inserts it as a brand-new row.
                dao.deleteById(entity.id)
                dao.upsert(
                    entity.copy(
                        id = UUID.randomUUID().toString(),
                        title = "${entity.title.ifBlank { "Untitled" }} (from this phone)",
                        rev = 1,
                        remoteRev = null,
                        pendingSync = true,
                    ),
                )
            }
        }
    }

    private fun sameSongContent(a: Song, b: Song): Boolean =
        a.title == b.title && a.lines == b.lines && a.meta == b.meta && a.customChords == b.customChords

    private suspend fun pushPending(userId: String, dek: ByteArray, dekId: String?): PushResult {
        var pushed = 0
        var reIded = 0
        var conflictCopies = 0
        var firstError: Throwable? = null

        for (entity in dao.getPendingSync()) {
            try {
                val row = buildRow(entity, userId, dek, dekId)

                if (entity.remoteRev == null) {
                    try {
                        val inserted = adapter.insert(row)
                        dao.upsert(entity.copy(pendingSync = false, remoteRev = inserted.rev))
                        pushed++
                    } catch (insertFailure: Exception) {
                        // Ask the server directly whether this id already exists,
                        // rather than string-matching a unique-violation error
                        // whose exact shape varies by driver/environment. A real
                        // collision here means adoption's own getById() precheck
                        // missed it -- most likely RLS made a DIFFERENT account's
                        // row with the same id invisible to that check. Re-id and
                        // retry once; a persistent failure for any other reason
                        // still propagates.
                        if (adapter.getById(entity.id) != null) {
                            val reIdEntity = entity.copy(id = UUID.randomUUID().toString(), rev = 1, remoteRev = null, pendingSync = true)
                            dao.deleteById(entity.id)
                            val inserted = adapter.insert(buildRow(reIdEntity, userId, dek, dekId))
                            dao.upsert(reIdEntity.copy(pendingSync = false, remoteRev = inserted.rev))
                            reIded++
                            pushed++
                        } else {
                            throw insertFailure
                        }
                    }
                } else {
                    when (val result = adapter.updateWithRevCheck(entity.id, row, entity.remoteRev)) {
                        is UpdateResult.Success -> {
                            dao.upsert(entity.copy(pendingSync = false, remoteRev = result.row.rev))
                            pushed++
                        }
                        is UpdateResult.Conflict -> {
                            // A failed rev check has two different real causes that
                            // must NOT be handled the same way -- ask which one this is.
                            if (adapter.getById(entity.id) == null) {
                                // The remote row is genuinely gone (e.g. the web app's
                                // rotateAndPurge deleted every row during a DEK
                                // rotation) -- this was never a lost race against
                                // another writer, it's simply a row that no longer has
                                // a remote counterpart. Writing a conflict copy here
                                // would duplicate every affected song on the device;
                                // instead mark it unpushed so the next pass inserts it
                                // as new.
                                dao.upsert(entity.copy(pendingSync = true, remoteRev = null))
                            } else {
                                // A genuine lost race -- someone else's write landed
                                // first. Keep both edits, never silently drop one.
                                writeConflictCopy(entity)
                                conflictCopies++
                                // Reset the loser's rev to 0, not just remoteRev to
                                // null. pull()'s skip rule below is
                                // `local.rev >= row.rev` -- an inflated local rev
                                // (Phase 13 fixed the debounce that used to cause
                                // this, but old data can still carry one) would make
                                // this song silently un-pullable forever otherwise.
                                dao.upsert(entity.copy(pendingSync = false, rev = 0, remoteRev = null))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // One bad row must not block every other pending row from
                // pushing -- mirrors pull()'s own per-row try/catch below.
                // Surface the first failure to sync()'s caller once every row
                // has had its turn, rather than aborting the whole batch here.
                if (firstError == null) firstError = e
            }
        }
        return PushResult(pushed, reIded, conflictCopies, firstError)
    }

    private suspend fun pull(userId: String, dek: ByteArray): Int {
        var pulled = 0
        for (row in adapter.list(userId)) {
            val local = dao.getByIdIncludingDeleted(row.id)
            if (local != null && (local.rev >= row.rev || local.pendingSync)) continue // local is at least as new, or has an edit not yet resolved

            if (row.deleted_at != null) {
                if (local != null) {
                    dao.upsert(local.copy(deletedAt = parseIso(row.deleted_at), rev = row.rev, remoteRev = row.rev, pendingSync = false))
                    pulled++
                }
                continue
            }

            // A single undecryptable row -- most likely encrypted under a
            // different DEK than this session's (dek_id mismatch; see
            // SongSyncWorker's pre-sync check for the common cause), or
            // genuinely corrupt -- must not abort the whole pull and leave
            // every OTHER row un-synced. Matches the web app's per-row
            // _placeholderSong fallback in songsRepository.js's list(). Local
            // state for this one row is simply left untouched (whatever it was
            // before this pass, including "doesn't exist locally yet").
            val song = try {
                val contentJson = JSONObject(decryptContentJson(dek, row.content.toContentEnvelope()))
                songFromContentJson(row.id, contentJson)
            } catch (e: Exception) {
                continue
            }
            dao.upsert(SongEntity.fromDomain(song).copy(rev = row.rev, deletedAt = null, pendingSync = false, remoteRev = row.rev))
            pulled++
        }
        return pulled
    }

    private fun buildRow(entity: SongEntity, userId: String, dek: ByteArray, dekId: String?): SongRow {
        val domain = entity.toDomain()
        val contentJson = JSONObject()
            .put("title", entity.title)
            .put("lines", linesToJson(domain.lines))
            .put("bpm", entity.bpm)
            .put("key", entity.key)
            .put("tuning", entity.tuning)
            .put("capo", entity.capo)
            .put("createdAt", toIso(entity.createdAt))
            .put("updatedAt", toIso(entity.updatedAt))
        if (domain.customChords.isNotEmpty()) contentJson.put("customChords", customChordsToJson(domain.customChords))

        val envelope = encryptContentJson(dek, contentJson.toString())
        return SongRow(
            id = entity.id,
            user_id = userId,
            encrypted = true,
            content = envelope.toJsonElement(),
            is_locked = false,
            dek_id = dekId,
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

    private fun customChordsToJson(customChords: Map<String, ChordVoicing>): JSONObject {
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
        return obj
    }

    private fun customChordsFromJson(json: JSONObject?): Map<String, ChordVoicing> {
        if (json == null) return emptyMap()
        val result = LinkedHashMap<String, ChordVoicing>()
        for (name in json.keys()) {
            val voicingJson = json.getJSONObject(name)
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
            customChords = customChordsFromJson(json.optJSONObject("customChords")),
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
