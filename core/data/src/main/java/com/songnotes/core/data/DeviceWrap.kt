package com.songnotes.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The account DEK's per-device unlock method -- the plan's "Keystore device wrap
 * + BiometricPrompt" -- adds a `"device"` entry to envelope v2's `wraps[]` list
 * (see Envelope.kt) so a user can unlock their account with a fingerprint/face
 * instead of typing their passphrase every time, without minting new key
 * material or touching the passphrase/recovery-code wraps at all.
 *
 * Deliberately NOT the same key as [KeystoreDbKeyProvider]'s DB-at-rest key --
 * that one gates local storage and must open non-interactively on app start;
 * this one gates the *account DEK* and is meant to require a fresh biometric
 * check every time (`setUserAuthenticationParameters(0, ...)` -- a 0-second
 * validity window, not a grace period), matching how sensitive the DEK is.
 *
 * BiometricPrompt itself needs a `FragmentActivity`/`Fragment` host and is a
 * callback-driven UI API, so it deliberately does NOT live in this module
 * (`:core:data` has no UI/Activity dependency) -- callers in `:app` build a
 * [Cipher] here, get it authorized via `BiometricPrompt.CryptoObject`, then
 * pass the *authorized* cipher back into [wrapDekWithAuthorizedCipher]/
 * [unwrapDekWithAuthorizedCipher] to actually move DEK bytes through it.
 */
object DeviceWrap {
    const val WRAP_TYPE = "device"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "songnotes.account_dek_device_wrap"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** True once a device-wrap key has been generated on this device (regardless of any envelope's own state). */
    fun hasKey(): Boolean = keyStore().getKey(KEY_ALIAS, null) != null

    /**
     * Generates (or returns the existing) device-wrap Keystore key. Requires
     * biometric auth on every use (`setUserAuthenticationParameters(0, ...)`,
     * not a grace-period timeout) -- this key gates account crypto, not merely
     * local storage, so it should re-prompt every time it's used, unlike
     * [KeystoreDbKeyProvider]'s key.
     */
    fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** A fresh ENCRYPT [Cipher] against the device-wrap key -- pass to `BiometricPrompt.CryptoObject` to authorize it. */
    fun buildEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher
    }

    /** A DECRYPT [Cipher] against `iv` (from a wrap's stored IV) -- pass to `BiometricPrompt.CryptoObject` to authorize it. */
    fun buildDecryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }

    /**
     * Wraps [dek] with a [cipher] already authorized by a successful
     * `BiometricPrompt` result (i.e. `result.cryptoObject.cipher`, built via
     * [buildEncryptCipher]) -- an unauthorized cipher throws here.
     */
    fun wrapDekWithAuthorizedCipher(cipher: Cipher, dek: ByteArray): WrapEntry {
        val ct = cipher.doFinal(dek)
        return WrapEntry(id = "device", type = WRAP_TYPE, kdf = null, iv = cipher.iv, ct = ct)
    }

    /**
     * Unwraps a `"device"` wrap's ciphertext with a [cipher] already authorized
     * by a successful `BiometricPrompt` result (built via [buildDecryptCipher]
     * using that wrap's own stored `iv`).
     */
    fun unwrapDekWithAuthorizedCipher(cipher: Cipher, wrap: WrapEntry): ByteArray {
        require(wrap.type == WRAP_TYPE) { "Not a device wrap (type=${wrap.type})" }
        return cipher.doFinal(wrap.ct)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
}
