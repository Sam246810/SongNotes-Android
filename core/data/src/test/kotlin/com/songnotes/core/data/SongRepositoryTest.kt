package com.songnotes.core.data

import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import com.songnotes.core.domain.SongMeta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for the Phase 13 fixes to [SongRepository.upsert] --
 * see `docs/handoff/PHASE-13-local-first.md`'s "Part 0" for why each of these
 * was a real, verified defect, not a hypothetical: under Phase 7's always-on
 * background sync they were survivable (an automatic retry eventually papered
 * over them); under Phase 13's strictly-manual sync, none of them get a
 * second chance without the user noticing something is wrong.
 */
class SongRepositoryTest {
    private lateinit var dao: FakeSongDao
    private lateinit var repo: SongRepository

    @Before
    fun setUp() {
        dao = FakeSongDao()
        repo = SongRepository(dao)
    }

    private fun song(id: String = "song-1", title: String = "My Song", createdAt: Long = 1000L, updatedAt: Long = 2000L): Song =
        Song(id = id, title = title, meta = SongMeta(), lines = listOf(SongLine(id = "line-1", lyrics = "hello")), createdAt = createdAt, updatedAt = updatedAt)

    @Test
    fun `upsert on an already-synced song preserves remoteRev instead of resetting it to null`() = runBlocking {
        // The headline defect: SongEntity.fromDomain never carries remoteRev at
        // all, so without SongRepository restoring it explicitly, every edit to
        // an already-synced song silently un-links it from the server. The
        // sync engine would then attempt an INSERT on an id that already
        // exists remotely -- a Postgres unique-violation that made the worker
        // fail (previously retry) forever on that one song.
        repo.upsert(song())
        dao.upsert(dao.rows["song-1"]!!.copy(remoteRev = 7, pendingSync = false)) // simulate a confirmed push

        repo.upsert(song(title = "Edited Title"))

        assertEquals(7, dao.rows["song-1"]!!.remoteRev)
        assertEquals(true, dao.rows["song-1"]!!.pendingSync)
    }

    @Test
    fun `upsert on a tombstoned row does not resurrect it`() = runBlocking {
        repo.upsert(song())
        repo.delete(song()) // tombstones
        assertNotNull(dao.rows["song-1"]!!.deletedAt)

        // Anything that calls upsert on this id afterward (e.g. the legacy-JSON
        // importer re-running) must not silently undelete it.
        repo.upsert(song(title = "Touched Again"))

        assertNotNull(dao.rows["song-1"]!!.deletedAt)
    }

    @Test
    fun `upsert preserves the real createdAt even when handed a domain object with createdAt = 0`() = runBlocking {
        // SongEditorScreen.emptySong() used to hand this a Song with
        // createdAt = 0L for a song id not yet loaded -- without preserving
        // the existing row's createdAt, that 0L would get pushed to the server
        // as a 1970 timestamp on the very next edit.
        repo.upsert(song(createdAt = 5_000_000L))

        repo.upsert(song(createdAt = 0L, title = "Edited"))

        assertEquals(5_000_000L, dao.rows["song-1"]!!.createdAt)
    }

    @Test
    fun `upsert bumps rev by exactly 1 and flags pendingSync`() = runBlocking {
        repo.upsert(song())
        assertEquals(1, dao.rows["song-1"]!!.rev)

        repo.upsert(song(title = "Second Edit"))
        assertEquals(2, dao.rows["song-1"]!!.rev)
        assertEquals(true, dao.rows["song-1"]!!.pendingSync)
    }

    @Test
    fun `deleteRespectingSync hard-deletes a song that was never pushed`() = runBlocking {
        repo.upsert(song()) // remoteRev stays null -- never synced

        repo.deleteRespectingSync(song())

        assertNull(dao.rows["song-1"]) // gone entirely, no tombstone left behind
    }

    @Test
    fun `deleteRespectingSync tombstones a song that has reached the server`() = runBlocking {
        repo.upsert(song())
        dao.upsert(dao.rows["song-1"]!!.copy(remoteRev = 3, pendingSync = false))

        repo.deleteRespectingSync(song())

        val row = dao.rows["song-1"]
        assertNotNull(row) // still present locally, as a tombstone
        assertNotNull(row!!.deletedAt)
        assertEquals(true, row.pendingSync) // queued so the sync engine can propagate the delete
    }

    @Test
    fun `observeAllWithSyncState reports isOnAccount from remoteRev`() = runBlocking {
        repo.upsert(song(id = "never-synced"))
        repo.upsert(song(id = "on-account", title = "Synced Song"))
        dao.upsert(dao.rows["on-account"]!!.copy(remoteRev = 1, pendingSync = false))

        val items = repo.observeAllWithSyncState().let { flow ->
            var result: List<SongListItem> = emptyList()
            flow.collect { result = it }
            result
        }

        val neverSynced = items.first { it.song.id == "never-synced" }
        val onAccount = items.first { it.song.id == "on-account" }
        assertEquals(false, neverSynced.isOnAccount)
        assertTrue(onAccount.isOnAccount)
        assertEquals(false, onAccount.pendingSync)
    }

    // -- Sync merge bookkeeping (docs/SYNC-MERGE.md) --------------------------

    @Test
    fun `the first edit of a synced song keeps the server version as its base, and later edits keep the same base`() = runBlocking {
        dao.upsert(SongEntity.fromDomain(song(title = "On The Server")).copy(rev = 3, remoteRev = 3, pendingSync = false))

        repo.upsert(song(title = "First Edit"))
        val base = dao.rows["song-1"]!!.baseDocJson
        assertNotNull(base)
        assertTrue(base!!.contains("On The Server"))

        repo.upsert(song(title = "Second Edit"))
        assertEquals(base, dao.rows["song-1"]!!.baseDocJson)
    }

    @Test
    fun `a song never pushed has no base`() = runBlocking {
        repo.upsert(song())
        assertNull(dao.rows["song-1"]!!.baseDocJson)
    }

    @Test
    fun `edits are stamped per line and per field`() = runBlocking {
        val clock = longArrayOf(10_000L)
        val stamped = SongRepository(dao) { clock[0] }
        stamped.upsert(song())
        clock[0] = 20_000L
        stamped.upsert(song(title = "Renamed"))

        val renamed = dao.rows["song-1"]!!.toDomain()
        assertEquals(mapOf("title" to 20_000L), renamed.fieldEditedAt)
        // A first save has nothing to compare with, so no stamp: it counts as edited at updatedAt.
        assertNull(renamed.lines[0].editedAt)

        clock[0] = 30_000L
        stamped.upsert(renamed.copy(lines = listOf(SongLine(id = "line-1", lyrics = "hello again"))))
        val edited = dao.rows["song-1"]!!.toDomain()
        assertEquals(30_000L, edited.lines[0].editedAt)
        assertEquals(mapOf("title" to 20_000L), edited.fieldEditedAt)
    }

    @Test
    fun `saveEdit merges with a version a sync wrote while the editor had the song open`() = runBlocking {
        val loaded = song().copy(lines = listOf(SongLine("line-1", "hello"), SongLine("line-2", "world")))
        dao.upsert(SongEntity.fromDomain(loaded).copy(rev = 3, remoteRev = 3, pendingSync = false))
        // A sync pulls another device's edit of line 2 while the editor is open.
        val pulled = loaded.copy(lines = listOf(loaded.lines[0], SongLine("line-2", "world, from the web")))
        dao.upsert(SongEntity.fromDomain(pulled).copy(rev = 4, remoteRev = 4, pendingSync = false))

        // The editor saves its edit of line 1, made on the version it loaded.
        val stored = repo.saveEdit(loaded.copy(lines = listOf(SongLine("line-1", "hello, from the phone"), loaded.lines[1])), basedOn = loaded)

        assertEquals(listOf("hello, from the phone", "world, from the web"), stored.lines.map { it.lyrics })
        assertEquals(stored.lines.map { it.lyrics }, dao.rows["song-1"]!!.toDomain().lines.map { it.lyrics })
    }

    @Test
    fun `saveEdit of a real edit brings back a song deleted elsewhere, but an unchanged save doesn't`() = runBlocking {
        val loaded = song()
        dao.upsert(SongEntity.fromDomain(loaded).copy(rev = 4, remoteRev = 4, pendingSync = false, deletedAt = 9_000L))

        repo.saveEdit(loaded, basedOn = loaded)
        assertNotNull(dao.rows["song-1"]!!.deletedAt)

        repo.saveEdit(loaded.copy(title = "Still Writing"), basedOn = loaded)
        assertNull(dao.rows["song-1"]!!.deletedAt)
        assertEquals("Still Writing", dao.rows["song-1"]!!.title)
    }
}
