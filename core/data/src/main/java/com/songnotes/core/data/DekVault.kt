package com.songnotes.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the account DEK across app restarts so sync can run when the app
 * opens, without asking for the password each time (docs/SYNC-MERGE.md,
 * "Android auto-sync and the account key").
 *
 * The DEK is stored wrapped by an Android Keystore AES-GCM key that never
 * leaves the device's secure hardware, needs no fingerprint (unlike
 * [DeviceWrap], which gates an interactive unlock), and only works while the
 * phone itself is unlocked (`setUnlockedDeviceRequired`). The wrapped bytes
 * live in private SharedPreferences, which backup and device transfer
 * exclude (`data_extraction_rules.xml`), so they can't follow the user to
 * another device -- and the Keystore key couldn't either.
 *
 * Saved after a successful sync, when [SyncPreferences.keepSyncUnlocked] is
 * on; cleared on sign-out / disabling sync, when the account key rotates
 * elsewhere, and whenever the Keystore key turns out unusable (e.g.
 * invalidated by a lock-screen change). Clearing just means the next sync
 * asks for the password again.
 *
 * Tradeoff: someone using this unlocked phone can sync the account's songs
 * without the password -- the same boundary that already protects the songs
 * stored on the phone (the SQLCipher key is Keystore-held too).
 */
class DekVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Stores [dek] for [userId], replacing anything stored before. Never throws. */
    fun save(userId: String, dek: ByteArray, dekId: String?) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(dek)
            prefs.edit()
                .putString(KEY_USER, userId)
                .putString(KEY_DEK_ID, dekId)
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't store the account key for auto-sync", e)
            clear()
        }
    }

    /**
     * Puts the stored DEK into [KeySession] if it belongs to [userId]. False
     * when there's none, it's another account's, or it can't be read (then
     * it's cleared). Never throws.
     */
    fun restoreInto(userId: String): Boolean {
        if (prefs.getString(KEY_USER, null) != userId) return false
        return try {
            val iv = Base64.decode(prefs.getString(KEY_IV, null) ?: return false, Base64.NO_WRAP)
            val ct = Base64.decode(prefs.getString(KEY_CT, null) ?: return false, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return false, GCMParameterSpec(GCM_TAG_BITS, iv))
            KeySession.establish(cipher.doFinal(ct), prefs.getString(KEY_DEK_ID, null))
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't read the stored account key; clearing it", e)
            clear()
            false
        }
    }

    /** Forgets the stored DEK and its Keystore key. Never throws. */
    fun clear() {
        prefs.edit().clear().commit() // synchronous: a crash right after mustn't leave the key on disk
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't delete the auto-sync Keystore key", e)
        }
    }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val TAG = "DekVault"
        const val PREFS_NAME = "songnotes_dek_vault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "songnotes.sync_dek_vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_USER = "user_id"
        const val KEY_DEK_ID = "dek_id"
        const val KEY_IV = "iv"
        const val KEY_CT = "ct"
    }
}
