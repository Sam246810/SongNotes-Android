package com.songnotes.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.songnotes.core.data.EnvelopeKeyMismatchException
import com.songnotes.core.data.SupabaseAuthRepository
import kotlinx.coroutines.launch

/**
 * Shown whenever the app is signed in but [KeySession] has no DEK -- the
 * common case is simply that the process was killed since the last sign-in:
 * [KeySession] is memory-only by design (see its own doc comment; Phase 6's
 * Keystore device wrap is the eventual answer to "unlock without retyping the
 * password," not wired into a real flow yet). Before this screen existed the
 * app just silently sat there signed-in-but-not-syncing with no explanation
 * (see docs/PLAN.md's forgot-password phase entry) -- [SongSyncWorker] no-ops
 * successfully whenever [KeySession.current] is null, so nothing ever
 * surfaced this state to the user.
 *
 * Also the landing spot right after a WEB password reset: GoTrue revokes
 * other sessions on a password change, so Android's session dies, and its
 * next sign-in attempt throws [EnvelopeKeyMismatchException] -- routed to
 * [RecoveryUnlockScreen] from here exactly like a normal re-unlock failure.
 */
@Composable
fun LockedAccountScreen(onUnlocked: () -> Unit, onSignOut: () -> Unit) {
    val authRepo = remember { SupabaseAuthRepository() }
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showRecovery by remember { mutableStateOf(false) }

    if (showRecovery) {
        RecoveryUnlockScreen(
            newPassword = password,
            onDone = onUnlocked,
            onCancel = { showRecovery = false },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Enter your password", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "You're signed in as ${authRepo.currentUserEmail ?: "this account"}, but your songs are " +
                "locked for this session. Enter your account password to unlock them.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Button(
            enabled = !isLoading && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                isLoading = true
                errorText = null
                scope.launch {
                    try {
                        authRepo.unlockWithPassword(password)
                        onUnlocked()
                    } catch (e: EnvelopeKeyMismatchException) {
                        showRecovery = true
                    } catch (e: Exception) {
                        errorText = e.message ?: "Something went wrong"
                    } finally {
                        isLoading = false
                    }
                }
            },
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.height(20.dp)) else Text("Unlock")
        }

        errorText?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        // An escape hatch matters here: without one, someone who's genuinely
        // forgotten their password is stuck on this screen forever with no way
        // back to the sign-in/sign-up screen (same reasoning as the web app's
        // PrivacyScreen "Forgot your password? Sign out instead").
        TextButton(onClick = {
            scope.launch {
                authRepo.signOut() // also clears KeySession
                onSignOut()
            }
        }) {
            Text("Forgot your password? Sign out instead")
        }
    }
}
