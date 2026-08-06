package com.songnotes.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SupportFactory

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
         */
        fun open(context: Context, dbKey: ByteArray): SongDatabase {
            net.sqlcipher.database.SQLiteDatabase.loadLibs(context.applicationContext)
            return Room.databaseBuilder(
                context.applicationContext,
                SongDatabase::class.java,
                DB_NAME,
            )
                .openHelperFactory(SupportFactory(dbKey))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }
}
