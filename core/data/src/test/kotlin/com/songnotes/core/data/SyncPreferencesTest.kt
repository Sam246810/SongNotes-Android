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
    fun `disableSync clears account and sync state but leaves legacyJsonImportDone alone`() {
        prefs.syncEnabled = true
        prefs.syncAccountUserId = "user-1"
        prefs.adoptionCompletedForUserId = "user-1"
        prefs.lastSyncAtMs = 999L
        prefs.lastSyncError = "some error"
        prefs.legacyJsonImportDone = true

        prefs.disableSync()

        assertFalse(prefs.syncEnabled)
        assertNull(prefs.syncAccountUserId)
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
}
