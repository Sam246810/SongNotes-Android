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

    /** Returns the raw 256-bit DB key, generating and wrapping a new one on first call. */
    fun getOrCreateDbKey(): ByteArray {
        if (!wrappedKeyFile.exists()) return createAndPersistDbKey()
        return try {
            unwrapDbKey()
        } catch (e: Exception) {
            // The Keystore key vanished (factory reset protection, backup restored to a
            // different device, etc.) while the wrapped file survived -- the wrapped bytes
            // are permanently unreadable at that point. Treat it the same as first run:
            // mint a fresh key. (This does mean any existing SQLCipher DB file becomes
            // unreadable too -- callers needing to distinguish "fresh install" from "lost
            // key" should check DB file existence themselves before calling this.)
            createAndPersistDbKey()
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
        (keyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }
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
