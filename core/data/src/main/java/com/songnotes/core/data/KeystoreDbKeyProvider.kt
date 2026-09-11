package com.songnotes.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The outcome of asking for the database key, not just the key itself.
 *
 * The distinction exists because "here is a working key" and "here is a working
 * key, but everything previously written under the old one is now unreadable"
 * are wildly different facts for the caller, and the old `ByteArray` return type
 * could not tell them apart -- see [KeystoreDbKeyProvider.getOrCreateDbKey].
 */
sealed interface DbKeyResult {
    val key: ByteArray

    /** The normal path: a first-run key, or the existing one unwrapped successfully. */
    data class Ready(override val key: ByteArray) : DbKeyResult

    /**
     * The Keystore wrap key was gone while a wrapped-key file (and a database)
     * still existed, so a replacement was minted. Any pre-existing `songs.db` is
     * permanently undecryptable and must be discarded before use.
     */
    data class RecreatedAfterKeyLoss(override val key: ByteArray) : DbKeyResult
}

/**
 * Produces the raw passphrase [SongDatabase.open] encrypts the whole SQLCipher
 * database file with. Per the plan: "SQLCipher, keyed by a random DB key wrapped
 * in Keystore -- not by the DEK, so the DB opens before unlock." This key is
 * deliberately unrelated to the account DEK (Envelope.kt/AccountKeys.kt) --
 * there's no passphrase to type or forget here, only an Android Keystore key that
 * never leaves secure hardware and a small wrapped-key file that's useless
 * without it, so app storage stays encrypted at rest even before (or if never)
 * the user signs in to unlock their actual song content.
 *
 * No biometric gating on this specific key -- that's what the plan's separate
 * "Keystore device wrap + BiometricPrompt" item is for (a *device* unlock method
 * for the account DEK itself, i.e. another `wraps[]` entry — see AccountKeys.kt),
 * not the local DB's own at-rest encryption, which should open non-interactively
 * on app start the same way SQLCipher would with any other key.
 */
class KeystoreDbKeyProvider(private val context: Context) {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val wrappedKeyFile = File(context.filesDir, WRAPPED_KEY_FILE_NAME)

    /**
     * Returns the raw 256-bit DB key, generating and wrapping a new one on first
     * call.
     *
     * The interesting case is the Keystore key vanishing (factory reset
     * protection, a backup restored onto a different device, some OS updates)
     * while the wrapped-key file survives: those wrapped bytes are then
     * permanently unreadable, and so is any database encrypted under them.
     *
     * This used to quietly mint a fresh key and return it, with a comment noting
     * that "callers needing to distinguish 'fresh install' from 'lost key' should
     * check DB file existence themselves before calling this." No caller ever
     * did -- so the real behavior was: hand back a key that cannot open the
     * database that's sitting right there, and let Room throw a cryptic
     * "file is not a database" on the first query, on every launch, forever.
     * A permanent crash loop with no path out except clearing app data.
     *
     * Now the condition is detected here and reported as
     * [DbKeyResult.RecreatedAfterKeyLoss], leaving the (correct) decision about
     * what to do with the orphaned database to [SongDatabase.getInstance], which
     * is the layer that actually knows about database files.
     */
    fun getOrCreateDbKey(): DbKeyResult {
        if (!wrappedKeyFile.exists()) return DbKeyResult.Ready(createAndPersistDbKey())
        return try {
            DbKeyResult.Ready(unwrapDbKey())
        } catch (e: Exception) {
            // Deliberately covers every failure mode of the unwrap -- a missing
            // Keystore entry, a corrupt wrapped-key file, an AEADBadTagException
            // from a key that was regenerated under the same alias. They differ
            // in cause but not in consequence: whatever is on disk cannot be
            // decrypted, and the only way forward is a new key.
            DbKeyResult.RecreatedAfterKeyLoss(createAndPersistDbKey())
        }
    }

    private fun createAndPersistDbKey(): ByteArray {
        val dbKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapKey = getOrCreateWrapKey()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrapKey) }
        val ct = cipher.doFinal(dbKey)
        val iv = cipher.iv
        wrappedKeyFile.writeBytes(iv.size.toByte().let { byteArrayOf(it) } + iv + ct)
        return dbKey
    }

    private fun unwrapDbKey(): ByteArray {
        val bytes = wrappedKeyFile.readBytes()
        val ivLen = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val ct = bytes.copyOfRange(1 + ivLen, bytes.size)
        val wrapKey = requireNotNull(keyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey) {
            "Keystore wrap key missing despite a wrapped-key file existing"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(ct)
    }

    private fun getOrCreateWrapKey(): SecretKey {
        // Not `?.let { return it }` on the existing entry: reaching here from the
        // key-loss path means the old entry is either gone or unusable, and
        // reusing an unusable one would just fail again at the next doFinal.
        // Deleting first makes "mint a fresh key" actually mean that.
        runCatching { keyStore.deleteEntry(WRAP_KEY_ALIAS) }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(WRAP_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // No setUserAuthenticationRequired -- this key gates local storage-at-rest
            // encryption, not account access; it must be usable non-interactively on
            // app start, same reasoning as the class doc comment above.
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAP_KEY_ALIAS = "songnotes.db_key_wrap"
        private const val WRAPPED_KEY_FILE_NAME = "db_key.wrapped"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
