package com.songnotes.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * v1 -> v2 (Phase 7): adds `rev`/`deletedAt`/`pendingSync` to `songs` for the
 * local half of the same rev-based optimistic-concurrency/tombstone sync
 * scheme the desktop web app already uses (see `SongEntity.kt`'s doc comment,
 * `docs/handoff/PHASE-07.md`). Existing rows default to `rev = 1`,
 * `deletedAt = NULL`, `pendingSync = 0` -- i.e. "not deleted, not yet known to
 * need a push," which is exactly correct for data that predates this column
 * existing at all: nothing on the device is newly out of sync just because the
 * bookkeeping columns were added.
 *
 * `exportSchema = false` (not the usual "add real schema snapshots once a real
 * migration exists" advice) -- tried it: Room 2.8.4's KSP schema-bundle export
 * needs a kotlinx-serialization-core new enough to have
 * `GeneratedSerializer.typeParametersSerializers()`, but every
 * kotlinx-serialization release with that method is also compiled against a
 * newer Kotlin than this project's pinned 2.0.21 (same class of conflict as
 * `supabase-kt`'s own version pin -- see `core/data/build.gradle.kts`), so the
 * export step itself crashes with an `AbstractMethodError`. Since this project
 * has no `androidx.test`/instrumented-test infra to run `MigrationTestHelper`
 * against those snapshots anyway (same gap noted in `docs/handoff/PHASE-06.md`),
 * the exported JSON wouldn't have been used for anything -- this migration is
 * verified for real instead, against the actual pre-Phase-7 database already on
 * the physical test device (see `docs/handoff/PHASE-07.md`).
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN rev INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE songs ADD COLUMN deletedAt INTEGER")
        db.execSQL("ALTER TABLE songs ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE songs ADD COLUMN remoteRev INTEGER")
    }
}

/** v2 -> v3 (Phase 8): adds `customChordsJson` for [Song.customChords]. Existing rows default to `"{}"` -- no song has any custom voicings until Phase 8's editor lets someone add one. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN customChordsJson TEXT NOT NULL DEFAULT '{}'")
    }
}

@Database(entities = [SongEntity::class], version = 3, exportSchema = false)
abstract class SongDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    companion object {
        private const val DB_NAME = "songs.db"

        @Volatile private var INSTANCE: SongDatabase? = null

        /**
         * Opens (creating if needed) the SQLCipher-encrypted song database.
         * [dbKey] is the raw passphrase bytes SQLCipher encrypts the whole file
         * with -- callers get it from [KeystoreDbKeyProvider], never hardcode or
         * derive it from anything user-typed. This key is unrelated to the
         * account DEK (see :core:data's Envelope.kt/AccountKeys.kt) -- per the
         * plan, "keyed by a random DB key wrapped in Keystore, not by the DEK, so
         * the DB opens before unlock" -- a locked-out user can still open the app
         * and see their (DEK-encrypted, so still unreadable) song list rather
         * than the whole database being inaccessible until they type a password.
         *
         * Prefer [getInstance] in app code -- this is kept public for tests (and
         * as the one place [getInstance] itself calls through to) but building a
         * second `RoomDatabase` over the same file is exactly the bug [getInstance]
         * exists to prevent; see its own doc comment.
         */
        fun open(context: Context, dbKey: ByteArray): SongDatabase {
            // `net.zetetic:sqlcipher-android` (the maintained artifact, which
            // replaced the deprecated `android-database-sqlcipher` here) drops
            // the old `SQLiteDatabase.loadLibs(context)` helper -- loading the
            // native library is a plain System.loadLibrary call now, and it
            // needs no Context at all. The on-disk database format is unchanged
            // across that switch, so an existing songs.db opens normally.
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(
                context.applicationContext,
                SongDatabase::class.java,
                DB_NAME,
            )
                .openHelperFactory(SupportOpenHelperFactory(dbKey))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        /**
         * The process-wide singleton every real call site should use (Phase 13).
         * Before this, `SongListScreen`, `SongEditorScreen`, `DiagnosticsScreen`
         * and `SongSyncWorker` each called [open] independently, building four
         * separate `RoomDatabase` instances over one physical file. Room's
         * `InvalidationTracker` -- what makes a `Flow<List<SongEntity>>` re-emit
         * after a write -- is scoped per-instance, so a write through instance A
         * never woke up a `Flow` collected from instance B. That was merely a
         * leak/inefficiency before Phase 13's unsynced-count banner; a banner fed
         * by a `Flow` bound to the wrong instance would just silently never
         * update, so this stopped being optional. WorkManager runs `SongSyncWorker`
         * in this app's own process (no `android:process` override in the
         * manifest), so a process-wide singleton is correct there too, not just
         * a convenience.
         */
        fun getInstance(context: Context): SongDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildInstance(context.applicationContext).also { INSTANCE = it }
            }

        /**
         * Resolves the DB key and, if the Keystore key was lost, discards the
         * database it can no longer open before handing the fresh key to [open].
         *
         * Without this step the app is bricked rather than degraded: the key
         * returned after a Keystore loss cannot decrypt the `songs.db` sitting
         * on disk, so Room throws on the first query of every launch, forever,
         * with no in-app way out. Deleting the orphaned file is not destroying
         * recoverable data -- that data was already unrecoverable the moment the
         * Keystore entry went away; the only question is whether the app also
         * stops working. It shouldn't.
         *
         * The loss IS still real for any song that was never synced, so it's
         * recorded in [LocalDataResetStore] for the UI to surface rather than
         * swallowed.
         */
        private fun buildInstance(context: Context): SongDatabase {
            val keyResult = KeystoreDbKeyProvider(context).getOrCreateDbKey()
            if (keyResult is DbKeyResult.RecreatedAfterKeyLoss) {
                val hadDatabase = context.getDatabasePath(DB_NAME).exists()
                // deleteDatabase() also removes the -wal/-shm sidecars, which a
                // bare File.delete() on the main file would strand -- SQLCipher
                // would then find leftover journal state for a database that no
                // longer exists.
                context.deleteDatabase(DB_NAME)
                // Only flag it if there was actually something to lose. A fresh
                // install whose Keystore entry was somehow unusable reaches this
                // branch too, and telling that user their songs were reset would
                // be a lie.
                if (hadDatabase) LocalDataResetStore(context).markReset()
            }
            return open(context, keyResult.key)
        }
    }
}
