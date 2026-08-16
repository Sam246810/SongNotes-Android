package com.songnotes.core.data

import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coverage for `SyncEngine`'s Phase 13 adoption pass (`sync(adopt = true)`) --
 * the reconciliation that runs the first time a device enables sync (or
 * switches accounts), folding every existing local song onto the account
 * without duplicating or damaging anything already there. See
 * `docs/handoff/PHASE-13-local-first.md` for the full algorithm writeup.
 */
class SyncEngineAdoptionTest {
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

    private fun localSong(id: String, title: String = "Local Song", deletedAt: Long? = null, remoteRev: Int? = null): SongEntity =
        SongEntity(
            id = id, title = title, bpm = 0, key = "", tuning = "", capo = 0,
            linesJson = """[{"id":"line-1","lyrics":"hello","chords":[]}]""",
            createdAt = 1000L, updatedAt = 2000L, rev = 1, deletedAt = deletedAt,
            pendingSync = true, remoteRev = remoteRev,
        )

    // Faithfully encodes entity's REAL lines (not a hardcoded empty array) --
    // otherwise "identical remote content" tests below would never actually
    // be identical (local always has one real line from localSong()), and
    // the "different content" test would pass for the wrong reason (an
    // accidental lines mismatch, not the intended title mismatch).
    private fun remoteRowFor(entity: SongEntity, rev: Int = 1, userId: String = this.userId): SongRow {
        val domain = entity.toDomain()
        val linesJson = org.json.JSONArray()
        for (line in domain.lines) {
            val chordsJson = org.json.JSONArray()
            for (chord in line.chords) chordsJson.put(JSONObject().put("i", chord.i).put("c", chord.c))
            linesJson.put(JSONObject().put("id", line.id).put("lyrics", line.lyrics).put("chords", chordsJson))
        }
        val content = JSONObject()
            .put("title", entity.title).put("lines", linesJson)
            .put("bpm", 0).put("key", "").put("tuning", "").put("capo", 0)
            .put("createdAt", "2026-01-01T00:00:00.000Z").put("updatedAt", "2026-01-01T00:00:00.000Z")
        val envelope = encryptContentJson(dek, content.toString())
        return SongRow(
            id = entity.id, user_id = userId, encrypted = true, content = envelope.toJsonElement(),
            is_locked = false, rev = rev, deleted_at = null,
            created_at = "2026-01-01T00:00:00.000Z", updated_at = "2026-01-01T00:00:00.000Z",
        )
    }

    @Test
    fun `adoption inserts every local song when nothing exists remotely`() = runBlocking {
        dao.upsert(localSong("song-1", title = "First"))
        dao.upsert(localSong("song-2", title = "Second"))
        dao.upsert(localSong("song-3", title = "Third"))

        val outcome = engine.sync(userId, dek, adopt = true)

        assertEquals(3, outcome.pushed)
        assertEquals(3, adapter.insertCalls)
        for (id in listOf("song-1", "song-2", "song-3")) {
            assertNotNull(dao.rows[id]!!.remoteRev)
            assertEquals(false, dao.rows[id]!!.pendingSync)
        }
    }

    @Test
    fun `adoption of many local songs drops none of them`() = runBlocking {
        repeat(50) { i -> dao.upsert(localSong("song-$i", title = "Song $i")) }

        engine.sync(userId, dek, adopt = true)

        assertEquals(50, adapter.rows.size)
        assertEquals(50, dao.rows.values.count { it.remoteRev != null })
    }

    @Test
    fun `a local id colliding with IDENTICAL remote content adopts the remote lineage without any network write`() = runBlocking {
        val local = localSong("song-1", title = "Same Song")
        dao.upsert(local)
        adapter.rows["song-1"] = remoteRowFor(local, rev = 9)

        engine.sync(userId, dek, adopt = true)

        assertEquals(0, adapter.insertCalls)
        assertEquals(0, adapter.updateCalls)
        val result = dao.rows["song-1"]!!
        assertEquals(9, result.remoteRev)
        assertEquals(9, result.rev)
        assertEquals(false, result.pendingSync)
    }

    @Test
    fun `a local id colliding with DIFFERENT remote content re-ids the local copy and never touches the remote row`() = runBlocking {
        val local = localSong("song-1", title = "My Phone's Version")
        dao.upsert(local)
        val remoteEntityForContent = localSong("song-1", title = "Someone Else's Version")
        adapter.rows["song-1"] = remoteRowFor(remoteEntityForContent, rev = 4)

        engine.sync(userId, dek, adopt = true)

        // Remote row byte-for-byte untouched.
        assertEquals(0, adapter.updateCalls)
        assertEquals("Someone Else's Version", decryptTitle(adapter.rows["song-1"]!!))

        // Local song survived under a NEW id, inserted as a genuinely new row.
        val reIded = dao.rows.values.first { it.title.startsWith("My Phone's Version") }
        assertNotEquals("song-1", reIded.id)
        assertNotNull(reIded.remoteRev)
        assertTrue(adapter.rows.containsKey(reIded.id))

        // The old id "song-1" was reclaimed locally during the re-id, but the
        // sync() call's own pull() -- running after the push, same as any
        // ordinary sync -- legitimately re-materializes it: it's the
        // account's real, pre-existing song, now correctly visible on this
        // device too as a SEPARATE song from the re-ided local copy.
        assertEquals("Someone Else's Version", dao.rows["song-1"]!!.title)
    }

    @Test
    fun `a tombstoned song that was never pushed is reclaimed, not pushed as a delete`() = runBlocking {
        dao.upsert(localSong("song-1", deletedAt = 5000L, remoteRev = null))

        val outcome = engine.sync(userId, dek, adopt = true)

        assertEquals(0, outcome.pushed)
        assertEquals(0, adapter.insertCalls)
        assertNull(dao.rows["song-1"])
    }

    @Test
    fun `adoption is idempotent -- running it twice makes zero remote writes the second time`() = runBlocking {
        dao.upsert(localSong("song-1", title = "First"))
        dao.upsert(localSong("song-2", title = "Second"))

        engine.sync(userId, dek, adopt = true)
        val insertsAfterFirst = adapter.insertCalls
        val updatesAfterFirst = adapter.updateCalls

        engine.sync(userId, dek, adopt = true)

        assertEquals(insertsAfterFirst, adapter.insertCalls)
        assertEquals(updatesAfterFirst, adapter.updateCalls)
    }

    private fun decryptTitle(row: SongRow): String {
        val envelope = ContentEnvelope.fromJson(JSONObject(row.content.toString()))
        return JSONObject(decryptContentJson(dek, envelope)).getString("title")
    }
}
