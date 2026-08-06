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
 */
@Serializable
private data class UserKeysRow(val user_id: String, val envelope: JsonElement)

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
     * @throws IllegalStateException if a `user_keys` row already exists for this
     * user -- i.e. `signUpWith(Email)` unexpectedly succeeded (established a
     * session) for an email that already has an account, rather than erroring
     * the way a genuinely new signup normally would. **Never overwrite an
     * existing envelope here** -- doing so mints a brand new DEK and silently
     * orphans the old one, permanently destroying access to every song
     * encrypted under it. This is not a hypothetical: it happened during this
     * phase's own on-device verification (see docs/handoff/PHASE-07.md) when a
     * sign-up attempt landed on an already-registered email.
     */
    suspend fun signUp(email: String, accountPassword: String): AccountKeys {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = accountPassword
        }
        val userId = requireNotNull(currentUserId) { "signUpWith(Email) succeeded but no current user" }

        check(!hasStoredUserKeys(userId)) {
            "An account already exists for this user -- refusing to overwrite its encryption key. " +
                "Sign in instead, or use a different email to create a new account."
        }

        val keys = createAccountKeys(accountPassword)
        upsertUserKeys(userId, keys.envelope)
        KeySession.establish(keys.dek)
        return keys
    }

    /**
     * Signs in an existing user, then unlocks (or, for a user who genuinely has
     * no stored envelope yet, creates) their account DEK. An existing envelope
     * is unlocked with [accountPassword] via [unlockWithPassphrase]; a missing
     * one means this account predates any envelope ever being written, so one
     * is minted now instead of leaving the account permanently unable to
     * encrypt anything. If a row exists but fails to parse (e.g. a v1 envelope
     * -- Kotlin only reads v2, see `AccountKeys.kt`'s doc comment), this throws
     * rather than falling through to the "create a new one" branch -- an
     * unparseable envelope is emphatically not the same as no envelope, and
     * must never be treated as license to overwrite it.
     */
    suspend fun signIn(email: String, accountPassword: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = accountPassword
        }
        val userId = requireNotNull(currentUserId) { "signInWith(Email) succeeded but no current user" }

        val existingRow = fetchUserKeysRow(userId)
        if (existingRow == null) {
            val keys = createAccountKeys(accountPassword)
            upsertUserKeys(userId, keys.envelope)
            KeySession.establish(keys.dek)
        } else {
            val envelope = EnvelopeV2.fromJson(JSONObject(existingRow.envelope.toString()))
            val dek = unlockWithPassphrase(envelope, accountPassword)
            KeySession.establish(dek)
        }
    }

    suspend fun signOut() {
        client.auth.signOut()
        KeySession.clear()
    }

    private suspend fun hasStoredUserKeys(userId: String): Boolean = fetchUserKeysRow(userId) != null

    private suspend fun fetchUserKeysRow(userId: String): UserKeysRow? =
        client.postgrest.from("user_keys").select { filter { eq("user_id", userId) } }.decodeSingleOrNull<UserKeysRow>()

    private suspend fun upsertUserKeys(userId: String, envelope: EnvelopeV2) {
        val envelopeJson = Json.parseToJsonElement(envelope.toJson().toString())
        client.postgrest.from("user_keys").upsert(UserKeysRow(user_id = userId, envelope = envelopeJson))
    }
}
