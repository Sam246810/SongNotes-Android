package com.songnotes.core.data

import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// FakeSongDao / FakeSongsAdapter moved to TestFakes.kt (Phase 13) so
// SongRepositoryTest and SyncEngineAdoptionTest can share them.

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
    fun `push stamps dek_id from the dekId passed to sync`() = runBlocking {
        dao.upsert(localSong())
        engine.sync(userId, dek, "dek-abc")

        assertEquals("dek-abc", adapter.rows["song-1"]!!.dek_id)
    }

    @Test
    fun `push leaves dek_id null when sync was called without one (pre-Phase-12 caller shape)`() = runBlocking {
        dao.upsert(localSong())
        engine.sync(userId, dek) // no dekId argument -- must still compile and work

        assertNull(adapter.rows["song-1"]!!.dek_id)
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

    // -- Lost races merge (docs/SYNC-MERGE.md) -------------------------------

    private val baseSong = Song(
        id = "song-1", title = "My Song",
        lines = listOf(SongLine("line-1", "hello", listOf(ChordAnchor(0, "G"))), SongLine("line-2", "world")),
        createdAt = 1000L, updatedAt = 2000L,
    )

    /** A clean row of [song] confirmed at [rev], then [edit]ed locally the way SongRepository would (pending, base kept). */
    private fun pendingEdit(song: Song, rev: Int, edit: (Song) -> Song): SongEntity =
        SongEntity.fromDomain(edit(song)).copy(
            rev = 7, remoteRev = rev, pendingSync = true, baseDocJson = songToDocument(song).toString(),
        )

    private fun remoteRowFor(song: Song, rev: Int, deletedAtIso: String? = null): SongRow = SongRow(
        id = song.id, user_id = userId, encrypted = true,
        content = encryptContentJson(dek, songToDocument(song).toString()).toJsonElement(),
        is_locked = false, rev = rev, deleted_at = deletedAtIso,
        created_at = toIso(song.createdAt), updated_at = toIso(song.updatedAt),
    )

    private fun serverSong(id: String = "song-1"): Song =
        songFromDocument(id, decryptContent(adapter.rows[id]!!), toIso(0), toIso(0))

    @Test
    fun `a lost race merges both edits and pushes the merge, with no conflict copy`() = runBlocking {
        adapter.rows["song-1"] = remoteRowFor(baseSong.copy(lines = listOf(baseSong.lines[0], SongLine("line-2", "world, from the web"))), rev = 4)
        dao.upsert(pendingEdit(baseSong, rev = 3) { it.copy(title = "My Edit") })

        val outcome = engine.sync(userId, dek)

        assertEquals(1, outcome.merged)
        assertEquals(1, dao.rows.size)
        assertEquals(1, adapter.rows.size)
        val merged = serverSong()
        assertEquals("My Edit", merged.title)
        assertEquals(listOf("hello", "world, from the web"), merged.lines.map { it.lyrics })
        val local = dao.rows["song-1"]!!
        assertEquals(false, local.pendingSync)
        assertEquals(5, local.remoteRev)
        assertNull(local.baseDocJson)
        assertEquals("My Edit", local.title)
    }

    @Test
    fun `an edit beats a delete made on another device`() = runBlocking {
        adapter.rows["song-1"] = remoteRowFor(baseSong, rev = 4, deletedAtIso = "2026-02-01T00:00:00.000Z")
        dao.upsert(pendingEdit(baseSong, rev = 3) { it.copy(title = "Still Writing This") })

        engine.sync(userId, dek)

        assertNull(adapter.rows["song-1"]!!.deleted_at)
        assertEquals("Still Writing This", serverSong().title)
        assertNull(dao.rows["song-1"]!!.deletedAt)
    }

    @Test
    fun `a delete gives way to an edit made on another device, and the song is restored`() = runBlocking {
        adapter.rows["song-1"] = remoteRowFor(baseSong.copy(title = "Edited On The Web"), rev = 4)
        dao.upsert(pendingEdit(baseSong, rev = 3) { it }.copy(deletedAt = 5000L))

        val outcome = engine.sync(userId, dek)

        assertEquals(1, outcome.restored)
        assertNull(adapter.rows["song-1"]!!.deleted_at)
        val local = dao.rows["song-1"]!!
        assertNull(local.deletedAt)
        assertEquals(false, local.pendingSync)
        assertEquals("Edited On The Web", local.title)
    }

    @Test
    fun `a delete still goes through when the other device changed nothing`() = runBlocking {
        adapter.rows["song-1"] = remoteRowFor(baseSong, rev = 4) // only the rev moved
        dao.upsert(pendingEdit(baseSong, rev = 3) { it }.copy(deletedAt = 5000L))

        engine.sync(userId, dek)

        assertNotNull(adapter.rows["song-1"]!!.deleted_at)
        assertEquals(false, dao.rows["song-1"]!!.pendingSync)
    }

    @Test
    fun `an edit saved while a push is out stays pending instead of being marked synced`() = runBlocking {
        val repository = SongRepository(dao)
        adapter.rows["song-1"] = remoteRowFor(baseSong, rev = 3)
        dao.upsert(pendingEdit(baseSong, rev = 3) { it.copy(title = "Pushed") })
        val racing = object : SongsRemoteAdapter by adapter {
            var once = true
            override suspend fun updateWithRevCheck(id: String, row: SongRow, expectedRev: Int): UpdateResult {
                if (once) {
                    once = false
                    // The editor saves while the push is on the network.
                    repository.upsert(dao.rows[id]!!.toDomain().copy(title = "Typed During The Push"))
                }
                return adapter.updateWithRevCheck(id, row, expectedRev)
            }
        }

        SyncEngine(dao, racing).sync(userId, dek)

        // The second (bounded) push pass sends the later edit in the same sync.
        assertEquals("Typed During The Push", serverSong().title)
        assertEquals(false, dao.rows["song-1"]!!.pendingSync)
    }

    @Test
    fun `a song already pending before bases existed merges without one, keeping both sides`() = runBlocking {
        adapter.rows["song-1"] = remoteRowFor(baseSong.copy(lines = listOf(baseSong.lines[0], SongLine("line-2", "world, from the web"))), rev = 4)
        val phoneEdit = pendingEdit(baseSong, rev = 3) {
            it.copy(lines = listOf(SongLine("line-1", "hello, from the phone", listOf(ChordAnchor(0, "G"))), it.lines[1]))
        }
        dao.upsert(phoneEdit.copy(baseDocJson = null))

        engine.sync(userId, dek)

        // Without a base, each line both sides have in different versions is kept twice.
        assertEquals(
            listOf("hello", "hello, from the phone", "world", "world, from the web").sorted(),
            serverSong().lines.map { it.lyrics }.sorted(),
        )
    }

    @Test
    fun `pull takes a newer server version even when the local edit counter is higher`() = runBlocking {
        // rev is a local write counter; only remoteRev says what the server confirmed.
        dao.upsert(localSong(rev = 10, remoteRev = 3, pendingSync = false, title = "Old"))
        adapter.rows["song-1"] = buildRemoteRow("song-1", rev = 4, title = "Newer On The Server")

        engine.sync(userId, dek)

        assertEquals("Newer On The Server", dao.rows["song-1"]!!.title)
        assertEquals(4, dao.rows["song-1"]!!.remoteRev)
    }

    @Test
    fun `a failed rev check where the remote row is simply gone re-queues for insert instead of merging`() = runBlocking {
        // Simulates a DEK rotation's rotateAndPurge deleting every remote row:
        // the local entity still points at a remoteRev that no longer exists.
        dao.upsert(localSong(rev = 4, remoteRev = 3, title = "Orphaned Edit"))

        val outcome = engine.sync(userId, dek)

        assertEquals(0, outcome.merged)
        assertEquals(1, dao.rows.size)
        assertEquals(1, adapter.insertCalls) // re-queued row went out via a clean insert instead
        val result = dao.rows["song-1"]!!
        assertEquals(false, result.pendingSync)
        assertNotNull(result.remoteRev)
    }

    @Test
    fun `editing an already-synced song pushes an update, never an insert (regression for the remoteRev-drop defect)`() = runBlocking {
        // The historical bug: SongRepository.upsert() used to silently reset
        // remoteRev to null on every edit, which made this scenario take the
        // INSERT branch against an id that already existed remotely --
        // repository.upsert()'s own fix is covered by SongRepositoryTest;
        // this proves the sync-engine side of the same story end-to-end.
        adapter.rows["song-1"] = buildRemoteRow("song-1", rev = 3, title = "Old Title")
        dao.upsert(localSong(rev = 4, remoteRev = 3, title = "Edited After Sync"))

        engine.sync(userId, dek)

        assertEquals(0, adapter.insertCalls)
        assertEquals(1, adapter.updateCalls)
        assertEquals("Edited After Sync", decryptContent(adapter.rows["song-1"]!!).getString("title"))
    }

    @Test
    fun `an insert collision the adoption precheck missed is re-ided and retried instead of failing the whole push`() = runBlocking {
        // Simulates the real SupabaseSongsAdapter.getById() hole: its query
        // has no explicit user_id filter, so whether it can see another
        // account's row depends entirely on server-side RLS -- which hides
        // it. simulatedCurrentUserId pins that down explicitly (its absence
        // was itself a real bug: an unscoped fake let an earlier version of
        // this test pass with the production fallback logic backwards --
        // see FakeSongsAdapter's own doc comment and
        // docs/handoff/PHASE-13-local-first.md). list(userId) -- which
        // pull() below uses -- correctly stays scoped to this account
        // regardless, same as real RLS on that query. If this row belonged
        // to `userId` instead, pull() would legitimately re-materialize it
        // locally and this test would be testing the wrong thing.
        adapter.simulatedCurrentUserId = userId
        adapter.rows["song-1"] = buildRemoteRow("song-1", rev = 1, title = "Someone Else's Song").copy(user_id = "other-user")
        dao.upsert(localSong(id = "song-1", rev = 1, remoteRev = null, title = "My New Song"))
        dao.upsert(localSong(id = "song-2", rev = 1, remoteRev = null, title = "A Different Song"))

        val outcome = engine.sync(userId, dek)

        assertEquals(1, outcome.reIded)
        assertEquals(2, outcome.pushed) // both rows made it out despite the collision
        assertNull(dao.rows["song-1"]) // old id reclaimed locally
        assertEquals("Someone Else's Song", decryptContent(adapter.rows["song-1"]!!).getString("title")) // untouched
        val reIdedEntity = dao.rows.values.first { it.title == "My New Song" }
        assertNotEquals("song-1", reIdedEntity.id)
        assertNotNull(reIdedEntity.remoteRev)
        assertEquals(false, reIdedEntity.pendingSync)
        val secondEntity = dao.rows.values.first { it.title == "A Different Song" }
        assertEquals(false, secondEntity.pendingSync)
        assertNotNull(secondEntity.remoteRev)
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
    fun `pull skips a row that fails to decrypt instead of aborting the whole pass`() = runBlocking {
        // Simulates a row encrypted under a DIFFERENT DEK than this session's --
        // e.g. synced from another device in the gap between a DEK rotation and
        // that device's own purge running (see the web app's accountRecovery.js
        // rotateAndPurge). A prior implementation had no try/catch here at all,
        // so the first such row aborted the pull entirely, leaving every OTHER
        // row un-synced too -- found during Phase 12 design review, not live.
        val otherDek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val badContent = JSONObject()
            .put("title", "Undecryptable").put("lines", org.json.JSONArray())
            .put("bpm", 0).put("key", "").put("tuning", "").put("capo", 0)
            .put("createdAt", "2026-01-01T00:00:00.000Z").put("updatedAt", "2026-01-01T00:00:00.000Z")
        val badEnvelope = encryptContentJson(otherDek, badContent.toString())
        adapter.rows["bad-song"] = SongRow(
            id = "bad-song", user_id = userId, encrypted = true, content = badEnvelope.toJsonElement(),
            is_locked = false, dek_id = "some-other-dek-id", rev = 1, deleted_at = null,
            created_at = "2026-01-01T00:00:00.000Z", updated_at = "2026-01-01T00:00:00.000Z",
        )
        adapter.rows["good-song"] = buildRemoteRow("good-song", rev = 1, title = "Still Readable")

        val outcome = engine.sync(userId, dek)

        assertNull(dao.rows["bad-song"]) // skipped, not upserted with garbage
        assertEquals("Still Readable", dao.rows["good-song"]!!.title) // the OTHER row still made it through
        assertEquals(1, outcome.unreadable) // ...and counted, so the banner can say so instead of the song just never appearing
    }

    @Test
    fun `pull reads a legacy web row -- padded-string chords, a line with no id, string bpm -- instead of skipping it`() = runBlocking {
        // Old web rows not re-saved since the web's anchor conversion still hold
        // v1 shapes. This reader used to throw on them and pull() skipped the
        // row, so the song never reached this device while the web showed it.
        val legacy = JSONObject()
            .put("title", "Old Web Song")
            .put(
                "lines",
                org.json.JSONArray()
                    .put(JSONObject().put("id", "line-a").put("lyrics", "a lyric from before anchors").put("chords", "Am    G       C"))
                    .put(JSONObject().put("lyrics", "no id here").put("chords", org.json.JSONArray())),
            )
            .put("bpm", "").put("key", "").put("tuning", "").put("capo", "3")
            .put("createdAt", "2026-01-01T00:00:00.000Z").put("updatedAt", "2026-01-01T00:00:00.000Z")
        adapter.rows["legacy"] = SongRow(
            id = "legacy", user_id = userId, encrypted = true, content = encryptContentJson(dek, legacy.toString()).toJsonElement(),
            is_locked = false, rev = 1, deleted_at = null,
            created_at = "2026-01-01T00:00:00.000Z", updated_at = "2026-01-01T00:00:00.000Z",
        )

        val outcome = engine.sync(userId, dek)

        val song = dao.rows["legacy"]!!.toDomain()
        assertEquals(0, outcome.unreadable)
        assertEquals(listOf(ChordAnchor(0, "Am"), ChordAnchor(6, "G"), ChordAnchor(14, "C")), song.lines[0].chords)
        assertEquals("legacy-1", song.lines[1].id)
        assertEquals(0, song.meta.bpm)
        assertEquals(3, song.meta.capo)
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
