package com.songnotes.core.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.json.JSONObject

/**
 * Email+password auth against the same Supabase project the desktop web app
 * uses, matching `src/auth/AuthProvider.jsx`'s `signUp`/`signIn`/`signOut` flow
 * exactly -- same backend, same `user_keys` table, same envelope v2 format (see
 * `AccountKeys.kt`), so an account created on one client works unchanged on the
 * other. Deliberately plain email+password, not Google Sign-In/Credential
 * Manager -- the web app has no OAuth provider configured, and matching its
 * existing auth method exactly is what actually keeps both clients "in sync on
 * the backend," per the explicit call here (docs/handoff/PHASE-07.md).
 *
 * `envelope_rev` (see `user_keys_history`'s doc comment in supabase/schema.sql)
 * guards every write to this row -- it's the one row in the schema whose loss
 * is unrecoverable-by-construction. `insertUserKeys` uses a real INSERT (throws
 * on a `user_id` conflict rather than silently overwriting a row a concurrent
 * request just created for a DIFFERENT DEK) and `updateUserKeysWithRevCheck`
 * requires the caller's last-known rev to still match. Neither client (web or
 * Android) blind-upserts this table anymore.
 */
@Serializable
private data class UserKeysRow(val user_id: String, val envelope: JsonElement, val envelope_rev: Int = 1)

/** Thrown by [SupabaseAuthRepository.recoverWithRecoveryCode] when the code doesn't unlock the envelope. */
class RecoveryCodeMismatchException(message: String) : Exception(message)

/**
 * Thrown by [SupabaseAuthRepository.signIn] / [SupabaseAuthRepository.unlockWithPassword]
 * when Supabase Auth accepted the password but the stored envelope doesn't --
 * most likely the account password was changed out-of-band (e.g. via the web
 * app's forgot-password flow). Distinguished from a generic failure so the UI
 * can route to recovery-code entry instead of showing a raw, unhelpful error.
 */
class EnvelopeKeyMismatchException(message: String) : Exception(message)

class SupabaseAuthRepository(private val client: SupabaseClient = SupabaseClientProvider.client) {

    val currentUserId: String?
        get() = client.auth.currentUserOrNull()?.id

    val currentUserEmail: String?
        get() = client.auth.currentUserOrNull()?.email

    val isSignedIn: Boolean
        get() = currentUserId != null

    /**
     * Creates a new Supabase Auth user, then mints and stores this account's
     * envelope v2 (wraps the DEK with [accountPassword] and a freshly generated
     * recovery code), establishing the DEK in [KeySession].
     *
     * @return `null` if this Supabase project has "Confirm email" enabled --
     * `signUpWith(Email)` then succeeds (creates the auth user) but grants NO
     * session, so there's nothing to authenticate an envelope write with yet
     * (confirmed live against the real project during Phase 12's on-device
     * verification: this is the actual configuration in use, not a hypothetical
     * edge case). Attempting the write anyway would fail Row Level Security and
     * had been silently swallowed here previously -- the caller must show
     * "check your email to confirm" instead. The envelope + recovery code get
     * created on the first post-confirmation SIGN-IN instead (see `signIn`'s
     * `existingRow == null` branch below), mirroring the web app's identical fix.
     *
     * The `check()` below (only reached once a session DOES exist) is a fast,
     * readable fail for the common case; the REAL guard against clobbering an
     * existing envelope is `insertUserKeys` using a genuine INSERT (throws on a
     * `user_id` conflict) rather than the upsert this used to be -- **never
     * silently overwrite an existing envelope here**, doing so mints a brand
     * new DEK and permanently orphans every song encrypted under the old one.
     * This is not hypothetical: it happened during Phase 7's own on-device
     * verification when a sign-up attempt landed on an already-registered email
     * (see docs/handoff/PHASE-07.md).
     */
    suspend fun signUp(email: String, accountPassword: String): AccountKeys? {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = accountPassword
        }
        val userId = currentUserId ?: return null

        check(!hasStoredUserKeys(userId)) {
            "An account already exists for this user -- refusing to overwrite its encryption key. " +
                "Sign in instead, or use a different email to create a new account."
        }

        val keys = createAccountKeys(accountPassword)
        insertUserKeys(userId, keys.envelope)
        KeySession.establish(keys.dek, keys.envelope.dekId)
        return keys
    }

    /**
     * Signs in an existing user, then unlocks (or, for a user who genuinely has
     * no stored envelope yet, creates) their account DEK.
     *
     * @return a freshly minted recovery code, if this call was the one that
     * created the envelope (first sign-in for an account with none yet) -- this
     * used to be silently discarded; the caller MUST show it, there is no other
     * chance to. `null` for a normal sign-in against an existing envelope.
     *
     * If a row exists but fails to parse (e.g. a v1 envelope -- Kotlin only
     * reads v2, see `AccountKeys.kt`'s doc comment), this throws rather than
     * falling through to the "create a new one" branch -- an unparseable
     * envelope is emphatically not the same as no envelope, and must never be
     * treated as license to overwrite it.
     */
    suspend fun signIn(email: String, accountPassword: String): String? {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = accountPassword
        }
        val userId = requireNotNull(currentUserId) { "signInWith(Email) succeeded but no current user" }

        val existingRow = fetchUserKeysRow(userId)
        if (existingRow == null) {
            // First sign-in for an account with no envelope yet (e.g. a legacy
            // account, or the email-confirmation gap where signUp() couldn't
            // write one). insertUserKeys uses a real INSERT -- if it throws,
            // someone else (another device, a race with our own fetch above)
            // created the row first; re-read and unlock against THEIRS rather
            // than risk having silently clobbered a live envelope.
            val keys = createAccountKeys(accountPassword)
            return try {
                insertUserKeys(userId, keys.envelope)
                KeySession.establish(keys.dek, keys.envelope.dekId)
                keys.recoveryCode
            } catch (e: Exception) {
                val raced = fetchUserKeysRow(userId) ?: throw e
                val racedEnvelope = EnvelopeV2.fromJson(JSONObject(raced.envelope.toString()))
                val racedDek = unlockWithPassphrase(racedEnvelope, accountPassword)
                KeySession.establish(racedDek, racedEnvelope.dekId)
                null
            }
        }

        val envelope = EnvelopeV2.fromJson(JSONObject(existingRow.envelope.toString()))
        val dek = try {
            unlockWithPassphrase(envelope, accountPassword)
        } catch (e: Exception) {
            // Wrong password would already have failed signInWith(Email) above, so
            // this means the stored envelope doesn't match the current password.
            throw EnvelopeKeyMismatchException(
                "Signed in, but your saved encryption key doesn't match this password " +
                    "-- likely because it was changed since encryption was set up."
            )
        }
        KeySession.establish(dek, envelope.dekId)

        // Best-effort: rewrap the passphrase wrap onto the current KDF policy
        // (Argon2id) if it's still on PBKDF2. Never blocks sign-in -- the DEK is
        // already established above regardless of whether this persist succeeds.
        try {
            val (migratedEnvelope, migrated) = migrateWrapIfNeeded(envelope, "passphrase", accountPassword, dek)
            if (migrated) updateUserKeysWithRevCheck(userId, migratedEnvelope, existingRow.envelope_rev)
        } catch (_: Exception) {
            // Swallowed deliberately -- see the web app's identical reasoning
            // in AuthProvider.jsx's signIn.
        }
        return null
    }

    /**
     * Re-derives the account DEK from [password] against the EXISTING stored
     * envelope and establishes it -- no writes, no new key material. For
     * "signed in but the DEK isn't in [KeySession]" (the common case: the
     * process was killed since the last sign-in -- [KeySession] is memory-only,
     * see its own doc comment), not an actually-wrong password.
     */
    suspend fun unlockWithPassword(password: String) {
        val userId = requireNotNull(currentUserId) { "Must be signed in to unlock encryption." }
        val existingRow = fetchUserKeysRow(userId) ?: error("No account encryption key found for this account yet.")
        val envelope = EnvelopeV2.fromJson(JSONObject(existingRow.envelope.toString()))
        val dek = try {
            unlockWithPassphrase(envelope, password)
        } catch (e: Exception) {
            throw EnvelopeKeyMismatchException("That password doesn't match this account's saved encryption key.")
        }
        KeySession.establish(dek, envelope.dekId)
    }

    /**
     * Path A of the forgot-password flow (see the web app's accountRecovery.js
     * for the full design and failure-mode analysis this mirrors): recovers
     * the DEK via [recoveryCode], sets [newPassword] as the current Supabase
     * auth password, and rewraps the envelope for it. Non-destructive -- the
     * DEK never changes, so zero songs are re-encrypted, and the recovery wrap
     * itself is never touched, so the same code keeps working afterward.
     *
     * Ordering matters, same as the web app: unlock (no writes) -> set password
     * -> rewrap+persist -> establish DEK. A failure after the password is set
     * but before the rewrap lands leaves the envelope on the OLD password --
     * self-healing on a retry with the same code and the now-current password,
     * never a permanent lockout for someone who actually has a valid code.
     *
     * @throws RecoveryCodeMismatchException if [recoveryCode] is wrong.
     */
    suspend fun recoverWithRecoveryCode(recoveryCode: String, newPassword: String) {
        val userId = requireNotNull(currentUserId) { "Must be signed in to recover encryption." }
        val existingRow = fetchUserKeysRow(userId) ?: error("No account encryption key found for this account yet.")
        val envelope = EnvelopeV2.fromJson(JSONObject(existingRow.envelope.toString()))

        val dek = try {
            unlockWithRecoveryCode(envelope, recoveryCode)
        } catch (e: Exception) {
            throw RecoveryCodeMismatchException("That recovery code didn't work.")
        }

        client.auth.updateUser { password = newPassword }

        val rewrapped = rewrapWithNewPassphrase(envelope, dek, newPassword)
        updateUserKeysWithRevCheck(userId, rewrapped, existingRow.envelope_rev)

        KeySession.establish(dek, rewrapped.dekId)
    }

    suspend fun signOut() {
        client.auth.signOut()
        KeySession.clear()
    }

    /**
     * The account's current envelope `dekId`, or `null` if no envelope exists.
     * Used by [SongSyncWorker] to detect a DEK rotation that happened elsewhere
     * (a recovery-code-lost reset, see the web app's `accountRecovery.js`
     * `rotateAndPurge`) before syncing anything under a [KeySession] DEK that's
     * already dead.
     */
    suspend fun fetchCurrentDekId(): String? {
        val userId = currentUserId ?: return null
        val row = fetchUserKeysRow(userId) ?: return null
        return EnvelopeV2.fromJson(JSONObject(row.envelope.toString())).dekId
    }

    private suspend fun hasStoredUserKeys(userId: String): Boolean = fetchUserKeysRow(userId) != null

    private suspend fun fetchUserKeysRow(userId: String): UserKeysRow? =
        client.postgrest.from("user_keys").select { filter { eq("user_id", userId) } }.decodeSingleOrNull<UserKeysRow>()

    /**
     * A real INSERT (not upsert) -- throws (Postgrest surfaces the underlying
     * Postgres 23505 unique_violation as an HTTP 409) if a row for this user
     * already exists, rather than silently overwriting a live envelope with a
     * new one wrapping a different DEK.
     */
    private suspend fun insertUserKeys(userId: String, envelope: EnvelopeV2) {
        val envelopeJson = Json.parseToJsonElement(envelope.toJson().toString())
        client.postgrest.from("user_keys").insert(UserKeysRow(user_id = userId, envelope = envelopeJson, envelope_rev = 1))
    }

    /**
     * Conditional update -- only writes if the row's current `envelope_rev`
     * still matches [expectedRev] (mirrors `SupabaseSongsAdapter.updateWithRevCheck`
     * and the web app's `SupabaseUserKeysAdapter.update`). Throws on a conflict
     * (someone else updated first) rather than returning a sentinel -- every
     * caller here treats a lost race as a real failure to surface, not an
     * expected outcome to branch on silently.
     */
    private suspend fun updateUserKeysWithRevCheck(userId: String, envelope: EnvelopeV2, expectedRev: Int) {
        val envelopeJson = Json.parseToJsonElement(envelope.toJson().toString())
        val updated = client.postgrest.from("user_keys").update(
            UserKeysRow(user_id = userId, envelope = envelopeJson, envelope_rev = expectedRev + 1)
        ) {
            select()
            filter {
                eq("user_id", userId)
                eq("envelope_rev", expectedRev)
            }
        }.decodeList<UserKeysRow>()
        if (updated.isEmpty()) {
            error("Account encryption key changed since it was last read (rev conflict) -- please try again.")
        }
    }
}
