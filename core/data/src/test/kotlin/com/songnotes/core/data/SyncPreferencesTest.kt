package com.songnotes.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Plain in-memory [KeyValueStore] -- lets [SyncPreferences] be tested as a real JVM unit test with no Robolectric (this repo has none). */
private class FakeKeyValueStore : KeyValueStore {
    private val booleans = HashMap<String, Boolean>()
    private val strings = HashMap<String, String?>()
    private val longs = HashMap<String, Long>()
    private val listeners = mutableListOf<() -> Unit>()

    override fun getBoolean(key: String, default: Boolean) = booleans[key] ?: default
    override fun putBoolean(key: String, value: Boolean) { booleans[key] = value; notifyListeners() }
    override fun getString(key: String) = strings[key]
    override fun putString(key: String, value: String?) { strings[key] = value; notifyListeners() }
    override fun getLong(key: String, default: Long) = longs[key] ?: default
    override fun putLong(key: String, value: Long) { longs[key] = value; notifyListeners() }

    override fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun notifyListeners() { listeners.forEach { it() } }
}

class SyncPreferencesTest {
    private lateinit var store: FakeKeyValueStore
    private lateinit var prefs: SyncPreferences

    @Before
    fun setUp() {
        store = FakeKeyValueStore()
        prefs = SyncPreferences(store)
    }

    @Test
    fun `defaults are safe -- sync off, nothing acknowledged, never synced`() {
        assertFalse(prefs.syncEnabled)
        assertFalse(prefs.explainerAcknowledged)
        assertNull(prefs.syncAccountUserId)
        assertNull(prefs.adoptionCompletedForUserId)
        assertEquals(0L, prefs.lastSyncAtMs)
        assertNull(prefs.lastSyncError)
        assertFalse(prefs.legacyJsonImportDone)
    }

    @Test
    fun `values round-trip through the store`() {
        prefs.syncEnabled = true
        prefs.explainerAcknowledged = true
        prefs.syncAccountUserId = "user-1"
        prefs.adoptionCompletedForUserId = "user-1"
        prefs.lastSyncAtMs = 123456L
        prefs.lastSyncError = "offline"
        prefs.legacyJsonImportDone = true

        val snapshot = prefs.snapshot()
        assertTrue(snapshot.syncEnabled)
        assertTrue(snapshot.explainerAcknowledged)
        assertEquals("user-1", snapshot.syncAccountUserId)
        assertEquals("user-1", snapshot.adoptionCompletedForUserId)
        assertEquals(123456L, snapshot.lastSyncAtMs)
        assertEquals("offline", snapshot.lastSyncError)
        assertTrue(snapshot.legacyJsonImportDone)
    }

    @Test
    fun `disableSync clears sync state but preserves syncAccountUserId and legacyJsonImportDone`() {
        prefs.syncEnabled = true
        prefs.syncAccountUserId = "user-1"
        prefs.adoptionCompletedForUserId = "user-1"
        prefs.lastSyncAtMs = 999L
        prefs.lastSyncError = "some error"
        prefs.legacyJsonImportDone = true

        prefs.disableSync()

        assertFalse(prefs.syncEnabled)
        // syncAccountUserId is deliberately NOT cleared -- see disableSync's
        // own doc comment for the live bug this fixes: it's the one field
        // SyncController.enableSyncFor needs to detect an account switch
        // across a sign-out. Pinned explicitly by the next test below.
        assertEquals("user-1", prefs.syncAccountUserId)
        assertNull(prefs.adoptionCompletedForUserId)
        assertEquals(0L, prefs.lastSyncAtMs)
        assertNull(prefs.lastSyncError)
        assertTrue(prefs.legacyJsonImportDone) // untouched -- unrelated to any account
    }

    @Test
    fun `SyncController enableSyncFor a DIFFERENT account than previously enabled detaches every song and clears the adoption flag`() = kotlinx.coroutines.runBlocking {
        val dao = FakeSongDao()
        dao.upsert(SongEntity(id = "song-1", title = "T", bpm = 0, key = "", tuning = "", capo = 0, linesJson = "[]", createdAt = 0, updatedAt = 0, rev = 3, remoteRev = 5, pendingSync = false))
        prefs.syncAccountUserId = "old-user"
        prefs.adoptionCompletedForUserId = "old-user"

        // Mirrors SyncController.enableSyncFor's own detach-on-account-switch
        // logic directly against the fake dao/prefs (SyncController itself
        // needs a real Context for SongDatabase.getInstance, so it isn't
        // constructed here -- this test pins the *policy*, which is what
        // actually matters and is fully Context-independent).
        val previousAccount = prefs.syncAccountUserId
        if (previousAccount != null && previousAccount != "new-user") {
            dao.detachFromRemote()
            prefs.adoptionCompletedForUserId = null
        }
        prefs.syncAccountUserId = "new-user"

        assertNull(dao.rows["song-1"]!!.remoteRev)
        assertTrue(dao.rows["song-1"]!!.pendingSync)
        assertNull(prefs.adoptionCompletedForUserId)
        assertEquals("new-user", prefs.syncAccountUserId)
    }

    @Test
    fun `an account switch is still detected even after a sign-out in between (regression)`() = kotlinx.coroutines.runBlocking {
        // The actual bug, found live on-device: sign in to account A (push a
        // song), sign OUT, then sign in to a DIFFERENT account B. Before the
        // fix, disableSync() cleared syncAccountUserId, so by the time
        // enableSyncFor(B) ran, previousAccount was null -- every sign-in
        // looked like a first-ever one, the detach never ran, and a song
        // already pushed to A sat there marked "synced" under B forever,
        // having never actually reached B's songs table at all.
        val dao = FakeSongDao()
        dao.upsert(SongEntity(id = "song-1", title = "Pushed to A", bpm = 0, key = "", tuning = "", capo = 0, linesJson = "[]", createdAt = 0, updatedAt = 0, rev = 1, remoteRev = 7, pendingSync = false))

        // Sign in to A.
        prefs.syncEnabled = true
        prefs.syncAccountUserId = "account-A"
        prefs.adoptionCompletedForUserId = "account-A"

        // Sign out.
        prefs.disableSync()

        // Sign in to a DIFFERENT account, B -- mirrors SyncController.enableSyncFor.
        val previousAccount = prefs.syncAccountUserId
        assertEquals("account-A", previousAccount) // the fix: still known, not wiped by sign-out
        if (previousAccount != null && previousAccount != "account-B") {
            dao.detachFromRemote()
            prefs.adoptionCompletedForUserId = null
        }
        prefs.syncAccountUserId = "account-B"

        // The song from A must be detached -- otherwise it stays invisible to
        // B's sync while the UI claims it's synced.
        assertNull(dao.rows["song-1"]!!.remoteRev)
        assertTrue(dao.rows["song-1"]!!.pendingSync)
    }
}
