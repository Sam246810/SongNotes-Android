package com.songnotes.core.data

import java.security.SecureRandom
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory [SongDao] -- same "fake, not a real Room DB" precedent as the JS side's FakeRemoteAdapter. */
private class FakeSongDao : SongDao {
    val rows = LinkedHashMap<String, SongEntity>()

    override fun observeAll() = flow { emit(rows.values.filter { it.deletedAt == null }.sortedByDescending { it.updatedAt }) }

    override suspend fun getByIdIncludingDeleted(id: String) = rows[id]
    override suspend fun getById(id: String) = rows[id]?.takeIf { it.deletedAt == null }
    override suspend fun getPendingSync() = rows.values.filter { it.pendingSync }
    override suspend fun upsert(song: SongEntity) { rows[song.id] = song }
    override suspend fun delete(song: SongEntity) { rows.remove(song.id) }
    override suspend fun deleteById(id: String) { rows.remove(id) }
}

/** In-memory [SongsRemoteAdapter] mirroring songsRepository.test.js's FakeRemoteAdapter's `WHERE id=? AND rev=?` semantics. */
private class FakeSongsAdapter : SongsRemoteAdapter {
    val rows = LinkedHashMap<String, SongRow>()
    var insertCalls = 0
    var updateCalls = 0

    override suspend fun list(userId: String) = rows.values.filter { it.user_id == userId }
    override suspend fun getById(id: String) = rows[id]
    override suspend fun insert(row: SongRow): SongRow {
        insertCalls++
        rows[row.id] = row
        return row
    }

    override suspend fun updateWithRevCheck(id: String, row: SongRow, expectedRev: Int): UpdateResult {
        updateCalls++
        val current = rows[id]
        if (current == null || current.rev != expectedRev) return UpdateResult.Conflict
        rows[id] = row
        return UpdateResult.Success(row)
    }
}

class SyncEngineTest {
    private lateinit var dao: FakeSongDao
    private lateinit var adapter: FakeSongsAdapter
    private lateinit var engine: SyncEngine
    private lateinit var dek: ByteArray
    private val userId = "user-1"

    @Before
    fun setUp() {
        dao = FakeSongDao()
        adapter = FakeSongsAdapter()
        engine = SyncEngine(dao, adapter)
        dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }

    private fun localSong(
        id: String = "song-1", title: String = "My Song", rev: Int = 1, pendingSync: Boolean = true,
        remoteRev: Int? = null, customChordsJson: String = "{}",
    ): SongEntity =
        SongEntity(
            id = id, title = title, bpm = 0, key = "", tuning = "", capo = 0,
            linesJson = """[{"id":"line-1","lyrics":"hello","chords":[{"i":0,"c":"G"}]}]""",
            createdAt = 1000L, updatedAt = 2000L, rev = rev, deletedAt = null,
            pendingSync = pendingSync, remoteRev = remoteRev, customChordsJson = customChordsJson,
        )

    private fun decryptContent(row: SongRow): JSONObject {
        val envelope = ContentEnvelope.fromJson(JSONObject(row.content.toString()))
        return JSONObject(decryptContentJson(dek, envelope))
    }

    @Test
    fun `push inserts a genuinely new song (remoteRev null) and clears pendingSync`() = runBlocking {
        dao.upsert(localSong())
        engine.sync(userId, dek)

        assertEquals(1, adapter.insertCalls)
        assertEquals(0, adapter.updateCalls)
        val pushed = dao.rows["song-1"]!!
        assertEquals(false, pushed.pendingSync)
        assertEquals(1, pushed.remoteRev)
        assertEquals("My Song", decryptContent(adapter.rows["song-1"]!!).getString("title"))
    }

    @Test
    fun `push updates an existing remote song via updateWithRevCheck`() = runBlocking {
        val existingRemote = buildRemoteRow("song-1", rev = 3)
        adapter.rows["song-1"] = existingRemote
        dao.upsert(localSong(rev = 4, remoteRev = 3, title = "Edited Title"))

        engine.sync(userId, dek)

        assertEquals(0, adapter.insertCalls)
        assertEquals(1, adapter.updateCalls)
        val pushed = dao.rows["song-1"]!!
        assertEquals(false, pushed.pendingSync)
        assertEquals(4, pushed.remoteRev)
        assertEquals("Edited Title", decryptContent(adapter.rows["song-1"]!!).getString("title"))
    }

    @Test
    fun `a lost optimistic-concurrency race writes a conflict copy instead of dropping the edit`() = runBlocking {
        // Remote already moved to rev 5 (someone else's write); our local entity still expects rev 3.
        adapter.rows["song-1"] = buildRemoteRow("song-1", rev = 5)
        dao.upsert(localSong(rev = 4, remoteRev = 3, title = "My Edit"))

        engine.sync(userId, dek)

        val original = dao.rows["song-1"]!!
        assertEquals(false, original.pendingSync) // gave up pushing further, not stuck retrying forever

        val conflictCopy = dao.rows.values.first { it.id != "song-1" }
        assertTrue(conflictCopy.title.startsWith("My Edit (conflict copy —"))
        assertEquals(true, conflictCopy.pendingSync) // queued to be pushed as a genuinely new song next cycle
        assertNull(conflictCopy.remoteRev)
        assertEquals(1, conflictCopy.rev)
    }

    @Test
    fun `push includes the local entity's own customChords, barre and all`() = runBlocking {
        val customChordsJson = """{"G":{"frets":[3,5,5,4,3,3],"baseFret":3,"barre":{"fret":3,"fromString":0,"toString":5}}}"""
        dao.upsert(localSong(title = "Has Custom Voicing", customChordsJson = customChordsJson))

        engine.sync(userId, dek)

        val pushedContent = decryptContent(adapter.rows["song-1"]!!)
        assertTrue(pushedContent.has("customChords"))
        val g = pushedContent.getJSONObject("customChords").getJSONObject("G")
        assertEquals(3, g.getInt("baseFret"))
        assertEquals(3, g.getJSONObject("barre").getInt("fret"))
    }

    @Test
    fun `pull applies a remote song's customChords onto the local entity`() = runBlocking {
        val content = JSONObject()
            .put("title", "Has Custom Voicing")
            .put("lines", org.json.JSONArray())
            .put("bpm", 0).put("key", "").put("tuning", "").put("capo", 0)
            .put("customChords", JSONObject().put("Cadd11", JSONObject().put("frets", org.json.JSONArray(listOf(-1, 3, 3, 0, 1, 1))).put("baseFret", 1)))
            .put("createdAt", "2026-01-01T00:00:00.000Z").put("updatedAt", "2026-01-01T00:00:00.000Z")
        val envelope = encryptContentJson(dek, content.toString())
        adapter.rows["song-1"] = SongRow(
            id = "song-1", user_id = userId, encrypted = true, content = envelope.toJsonElement(),
            is_locked = false, rev = 1, deleted_at = null,
            created_at = "2026-01-01T00:00:00.000Z", updated_at = "2026-01-01T00:00:00.000Z",
        )

        engine.sync(userId, dek)

        val pulled = dao.rows["song-1"]!!.toDomain()
        val voicing = pulled.customChords["Cadd11"]!!
        assertEquals(1, voicing.baseFret)
        assertEquals(listOf(-1, 3, 3, 0, 1, 1), voicing.frets)
    }

    @Test
    fun `pull upserts a remote song not present locally`() = runBlocking {
        adapter.rows["song-1"] = buildRemoteRow("song-1", rev = 1, title = "From The Cloud")

        engine.sync(userId, dek)

        val pulled = dao.rows["song-1"]!!
        assertEquals("From The Cloud", pulled.title)
        assertEquals(1, pulled.remoteRev)
        assertEquals(false, pulled.pendingSync)
    }

    @Test
    fun `pull applies a remote tombstone as a local delete, not an upsert of dead content`() = runBlocking {
        dao.upsert(localSong(rev = 1, remoteRev = 1, pendingSync = false))
        adapter.rows["song-1"] = buildRemoteRow("song-1", rev = 2, deletedAtIso = "2026-02-01T00:00:00.000Z")

        engine.sync(userId, dek)

        val local = dao.rows["song-1"]!!
        assertNotNull(local.deletedAt)
        assertEquals(2, local.rev)
    }

    @Test
    fun `pull parses Postgrest's real offset timestamp format for a tombstone, not just the Z-suffixed form this code writes itself`() = runBlocking {
        // Regression test: Postgrest returns timestamptz columns as "+00:00"-offset
        // text (e.g. "2026-08-06T22:43:22.991+00:00"), not the "Z"-suffixed form
        // toIso() writes for outgoing rows. A prior parseIso() implementation used a
        // SimpleDateFormat pattern with a hardcoded literal 'Z', which rejected this
        // real shape outright -- every pull touching a tombstone silently retried
        // forever on-device (found via live testing, not this suite -- see
        // docs/handoff/PHASE-07.md). This fixture uses the exact format Postgrest
        // sends so this class of bug can't reach a live device again undetected.
        dao.upsert(localSong(rev = 1, remoteRev = 1, pendingSync = false))
        adapter.rows["song-1"] = buildRemoteRow("song-1", rev = 2, deletedAtIso = "2026-02-01T00:00:00.991+00:00")

        engine.sync(userId, dek)

        val local = dao.rows["song-1"]!!
        assertNotNull(local.deletedAt)
        assertEquals(2, local.rev)
    }

    @Test
    fun `pull does not clobber a local row that is already at least as new`() = runBlocking {
        dao.upsert(localSong(rev = 5, remoteRev = 5, pendingSync = false, title = "Local Is Newer"))
        adapter.rows["song-1"] = buildRemoteRow("song-1", rev = 3, title = "Stale Remote")

        engine.sync(userId, dek)

        assertEquals("Local Is Newer", dao.rows["song-1"]!!.title)
    }

    private fun buildRemoteRow(id: String, rev: Int, title: String = "Remote Song", deletedAtIso: String? = null): SongRow {
        val content = JSONObject()
            .put("title", title)
            .put("lines", org.json.JSONArray())
            .put("bpm", 0).put("key", "").put("tuning", "").put("capo", 0)
            .put("createdAt", "2026-01-01T00:00:00.000Z").put("updatedAt", "2026-01-01T00:00:00.000Z")
        val envelope = encryptContentJson(dek, content.toString())
        return SongRow(
            id = id, user_id = userId, encrypted = true, content = envelope.toJsonElement(),
            is_locked = false, rev = rev, deleted_at = deletedAtIso,
            created_at = "2026-01-01T00:00:00.000Z", updated_at = "2026-01-01T00:00:00.000Z",
        )
    }
}
