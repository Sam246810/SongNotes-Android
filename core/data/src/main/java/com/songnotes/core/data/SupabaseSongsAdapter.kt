package com.songnotes.core.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The Kotlin twin of the desktop web app's `SupabaseSongsAdapter`
 * (`songsRepository.js`) -- same `songs` table, same rev-based optimistic
 * concurrency, same tombstone-instead-of-DELETE semantics (see
 * `docs/handoff/PHASE-07.md`). Deliberately dumb, same reasoning as the JS
 * version: no encryption, no caching, no reconciliation logic here -- that
 * lives in whatever calls this (the future `SyncWorker`), so this adapter
 * stays independently testable against a fake/in-memory Postgrest response.
 */
@Serializable
data class SongRow(
    val id: String,
    val user_id: String,
    val encrypted: Boolean,
    val content: JsonElement,
    val is_locked: Boolean,
    val rev: Int,
    val deleted_at: String?,
    val created_at: String,
    val updated_at: String,
)

sealed class UpdateResult {
    data class Success(val row: SongRow) : UpdateResult()
    data object Conflict : UpdateResult()
}

/**
 * [SyncEngine] depends on this interface, not [SupabaseSongsAdapter] directly --
 * same reasoning as the JS side's `CloudSongsRepository` taking any adapter
 * exposing `{list, insert, updateWithRevCheck}`: a fake in-memory implementation
 * in tests exercises the real push/pull/conflict logic without a live Postgrest
 * backend, mirroring `songsRepository.test.js`'s own `FakeRemoteAdapter`.
 */
interface SongsRemoteAdapter {
    suspend fun list(userId: String): List<SongRow>
    suspend fun getById(id: String): SongRow?
    suspend fun insert(row: SongRow): SongRow
    suspend fun updateWithRevCheck(id: String, row: SongRow, expectedRev: Int): UpdateResult
}

class SupabaseSongsAdapter(private val client: SupabaseClient = SupabaseClientProvider.client) : SongsRemoteAdapter {

    override suspend fun list(userId: String): List<SongRow> =
        client.postgrest.from("songs").select { filter { eq("user_id", userId) } }.decodeList<SongRow>()

    /** Used before overwriting an existing row, to preserve fields the caller's own domain model doesn't track (e.g. `customChords`). */
    override suspend fun getById(id: String): SongRow? =
        client.postgrest.from("songs").select { filter { eq("id", id) } }.decodeSingleOrNull<SongRow>()

    override suspend fun insert(row: SongRow): SongRow =
        client.postgrest.from("songs").insert(row) { select() }.decodeSingle<SongRow>()

    /**
     * Conditional update -- only writes if the row's current `rev` still
     * matches [expectedRev] (`WHERE id = ? AND rev = ?`, mirroring
     * `songsRepository.js`'s `updateWithRevCheck` exactly). An empty result
     * means nothing matched (someone else already wrote a newer rev), which is
     * an expected, handled outcome ([UpdateResult.Conflict]), not an error.
     */
    override suspend fun updateWithRevCheck(id: String, row: SongRow, expectedRev: Int): UpdateResult {
        val updated = client.postgrest.from("songs").update(row) {
            select()
            filter {
                eq("id", id)
                eq("rev", expectedRev)
            }
        }.decodeList<SongRow>()
        return if (updated.isEmpty()) UpdateResult.Conflict else UpdateResult.Success(updated.first())
    }
}

/** Builds a [SongRow]'s `content` field from a [ContentEnvelope], for callers building rows to insert/update. */
fun ContentEnvelope.toJsonElement(): JsonElement = Json.parseToJsonElement(toJson().toString())

/** Parses a [SongRow]'s `content` field back into a [ContentEnvelope], for callers reading rows. */
fun JsonElement.toContentEnvelope(): ContentEnvelope = ContentEnvelope.fromJson(org.json.JSONObject(toString()))
