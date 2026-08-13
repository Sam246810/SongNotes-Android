package com.songnotes.android

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.songnotes.core.data.EnvelopeKeyMismatchException
import com.songnotes.core.data.SongSyncWorker
import com.songnotes.core.data.SupabaseAuthRepository
import com.songnotes.core.data.SupabaseClientProvider
import kotlinx.coroutines.launch

/**
 * Email+password sign-in/sign-up, matching the desktop web app's own auth
 * exactly (see `SupabaseAuthRepository`'s doc comment) -- same Supabase
 * project, same account works from either client. On success, enqueues a
 * one-time [SongSyncWorker] pass so signing in actually pulls the account's
 * existing songs immediately, not just on whatever the next scheduled sync
 * happens to be (there is no periodic background sync yet -- see
 * docs/handoff/PHASE-07.md).
 */
@Composable
fun AuthScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val authRepo = remember { SupabaseAuthRepository() }
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSignUp by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }
    // Set on a sign-in whose auth succeeded but whose envelope doesn't match this
    // password (EnvelopeKeyMismatchException) -- routes to recovery-code entry
    // instead of just showing a raw error with no way forward.
    var showRecoveryUnlock by remember { mutableStateOf(false) }
    // Set when signUp() returns null -- this Supabase project requires email
    // confirmation before granting a session, so there's no encryption key to
    // show yet (see signUp's doc comment). Confirmed live against the real
    // project, not a hypothetical.
    var needsEmailConfirmation by remember { mutableStateOf(false) }

    if (!SupabaseClientProvider.isConfigured) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Accounts aren't configured", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "supabase.url / supabase.anonKey are missing from local.properties. " +
                    "See core/data/build.gradle.kts for what's expected.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDone) { Text("Back") }
        }
        return
    }

    if (showRecoveryUnlock) {
        RecoveryUnlockScreen(
            newPassword = password,
            onDone = onDone,
            onCancel = { showRecoveryUnlock = false; errorText = null },
        )
        return
    }

    if (needsEmailConfirmation) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Check your email", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Confirm your account from the link we just sent, then sign in. Your recovery " +
                    "code will be created and shown the first time you do.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("OK") }
        }
        return
    }

    // A recovery code is shown exactly once, right after sign-up (or the first
    // sign-in for an account that never had one -- see signIn's doc comment) --
    // the user must save it now, there's no way to see it again later.
    if (recoveryCode != null) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Save your recovery code", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "This is the only way to recover your songs if you forget your password. " +
                    "It will not be shown again.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(recoveryCode!!, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("I've saved it — continue") }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (isSignUp) "Sign up" else "Sign in", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        Button(
            enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                isLoading = true
                errorText = null
                scope.launch {
                    try {
                        if (isSignUp) {
                            val keys = authRepo.signUp(email, password)
                            if (keys != null) {
                                SongSyncWorker.enqueueOneTime(context)
                                recoveryCode = keys.recoveryCode
                            } else {
                                needsEmailConfirmation = true
                            }
                        } else {
                            val freshRecoveryCode = authRepo.signIn(email, password)
                            SongSyncWorker.enqueueOneTime(context)
                            if (freshRecoveryCode != null) {
                                // First sign-in for an account with no envelope yet --
                                // a code was just minted and has never been shown.
                                recoveryCode = freshRecoveryCode
                            } else {
                                onDone()
                            }
                        }
                    } catch (e: EnvelopeKeyMismatchException) {
                        showRecoveryUnlock = true
                    } catch (e: Exception) {
                        errorText = e.message ?: "Something went wrong"
                    } finally {
                        isLoading = false
                    }
                }
            },
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
            } else {
                Text(if (isSignUp) "Create account" else "Sign in")
            }
        }

        errorText?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { isSignUp = !isSignUp; errorText = null }) {
            Text(if (isSignUp) "Already have an account? Sign in" else "Need an account? Sign up")
        }
        if (!isSignUp) {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEB_FORGOT_PASSWORD_URL)))
            }) {
                Text("Forgot your password?")
            }
        }
    }
}
