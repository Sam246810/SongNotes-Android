package com.songnotes.core.data

/**
 * In-memory-only account DEK session, matching the desktop web app's
 * `src/crypto/keyManager.js` core behavior: the DEK lives in memory only, for
 * this process's lifetime, established on sign-in/sign-up and wiped on sign-out.
 *
 * Deliberately does NOT port `keyManager.js`'s sessionStorage persistence (surviving
 * a page reload without re-entering the password) -- there's no Android equivalent
 * of "the same tab reloaded"; Phase 6's Keystore device wrap (`DeviceWrap.kt`) is
 * the intended answer to "unlock without retyping the passphrase" on Android, but
 * it isn't wired into a real sign-in flow yet (see docs/handoff/PHASE-07.md). Until
 * then, the DEK simply doesn't survive the process being killed, same as any
 * other in-memory-only secret.
 */
object KeySession {
    @Volatile private var dek: ByteArray? = null

    fun establish(newDek: ByteArray) {
        dek = newDek
    }

    fun current(): ByteArray? = dek

    fun isUnlocked(): Boolean = dek != null

    fun clear() {
        dek = null
    }
}
