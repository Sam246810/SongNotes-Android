package com.songnotes.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory

@Database(entities = [SongEntity::class], version = 1, exportSchema = false)
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
            ).openHelperFactory(SupportFactory(dbKey)).build()
        }
    }
}
