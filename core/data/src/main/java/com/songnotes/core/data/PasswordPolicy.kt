package com.songnotes.core.data

/**
 * Account password policy, matching the web app's `src/auth/passwordPolicy.js`
 * value-for-value.
 *
 * In a zero-knowledge product the account password is not merely a login
 * credential -- it is a cryptographic parameter. The server holds
 * `user_keys.envelope`, which contains the account DEK wrapped by a KEK derived
 * straight from this password (see [AccountKeys]). Anyone who obtains a copy of
 * that table -- a database compromise, a leaked backup, a misissued
 * `service_role` key -- can attack the wrap offline, with no rate limiting and
 * unlimited attempts. That makes password length the real floor under the whole
 * "unreadable even to someone with full database access" claim: Argon2id at
 * 64 MiB makes each guess genuinely expensive and is the right choice, but it
 * cannot rescue a keyspace that small from an attacker willing to rent GPUs.
 * The recovery code is ~100 bits and was never the weak link; this was.
 *
 * **Enforcement, and its limits.** This is CLIENT-side and therefore advisory:
 * anyone can bypass it by calling the API directly. It exists to steer real
 * users, not to stop attackers. The binding control is the server-side minimum
 * in the Supabase dashboard (Authentication -> Providers -> minimum password
 * length), which has to be set to the same value separately.
 *
 * **Why 8 and not 12.** A pure security review of this architecture argues for
 * a longer minimum. 8 is a deliberate, informed compromise the product owner
 * chose over that recommendation, trading some margin for staying close to a
 * length people don't fight the form over. Carried over here rather than
 * re-litigated, because the number has to match the web app exactly -- see the
 * next paragraph for what happens when it doesn't.
 *
 * **Why this file exists at all.** `passwordPolicy.js` explicitly called out
 * the gap it could not close from the web repo: *"Android's own signup UI still
 * shows a 6-character minimum (a separate repo, not changed here) -- so if the
 * Supabase dashboard minimum is raised to match this module's 8, an Android
 * user could type a 6-or-7-character password that its own UI accepts and have
 * the server reject it."* In fact Android had **no** client-side length check
 * whatsoever, so the mismatch was wider than the web app assumed. This closes
 * it.
 *
 * [validateNewPassword] applies only when a password is being **set**. Sign-IN
 * deliberately never checks length, exactly as `LoginPage` doesn't -- every
 * existing account, including any created on Android with a short password,
 * keeps working everywhere.
 */
object PasswordPolicy {
    /** Minimum length for a newly set account password. Must match `passwordPolicy.js`'s `MIN_PASSWORD_LENGTH`. */
    const val MIN_PASSWORD_LENGTH = 8

    /** Shown under password fields so the requirement reads as a reason, not a rule. */
    const val HELP_TEXT =
        "At least $MIN_PASSWORD_LENGTH characters. This password encrypts your songs — " +
            "a longer passphrase of a few words is both stronger and easier to remember."

    /** @return an error message to show, or `null` if [password] is acceptable. */
    fun validateNewPassword(password: String?): String? =
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            "Password must be at least $MIN_PASSWORD_LENGTH characters."
        } else {
            null
        }
}
