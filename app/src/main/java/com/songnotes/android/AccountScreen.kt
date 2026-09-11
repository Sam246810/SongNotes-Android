package com.songnotes.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.songnotes.core.data.KeySession
import com.songnotes.core.data.PasswordPolicy
import com.songnotes.core.data.SupabaseAuthRepository
import kotlinx.coroutines.launch

/**
 * Account settings -- the Android counterpart of the web app's
 * `src/auth/AccountPage.jsx`: regenerate the recovery code, and change the
 * account password.
 *
 * Both operations already existed in `:core:data` (`regenerateRecoveryWrap` and
 * `rewrapWithNewPassphrase` in `AccountKeys.kt`, ported alongside the rest of
 * the crypto) but neither had a single call site on Android -- so a user who
 * never saved the recovery code shown once at sign-up had no way to mint
 * another, and no way to change their password without going through the web
 * app. This is the UI those functions were written for.
 *
 * Both require an unlocked [KeySession], because both rewrap the live DEK. That
 * is also what makes them safe for the case they exist to serve: neither
 * derives anything from the old recovery code, so both work for someone who has
 * lost it entirely.
 */
@Composable
fun AccountScreen(onDone: () -> Unit) {
    val authRepo = remember { SupabaseAuthRepository() }
    val scope = rememberCoroutineScope()

    var newRecoveryCode by remember { mutableStateOf<String?>(null) }
    var regenerating by remember { mutableStateOf(false) }
    var regenerateError by remember { mutableStateOf<String?>(null) }

    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var changingPassword by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var passwordChanged by remember { mutableStateOf(false) }

    // Both actions rewrap the DEK, so both need it in memory. KeySession is
    // memory-only and doesn't survive process death, so this is a normal state
    // to land in, not an error -- say what to do about it rather than showing
    // two permanently broken forms.
    val unlocked = KeySession.isUnlocked()

    // A freshly minted code is shown exactly once, same as at sign-up, and gets
    // the same screenshot protection -- see SecureScreen.
    if (newRecoveryCode != null) {
        SecureScreen()
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
            Text("Your new recovery code", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Save it now — it won't be shown again. Your previous recovery code no longer works.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(newRecoveryCode!!, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { newRecoveryCode = null }, modifier = Modifier.fillMaxWidth()) {
                Text("I've saved it")
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text("Account", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            authRepo.currentUserEmail.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!unlocked) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Enter your password from the song list (press Sync) to unlock your encryption key, " +
                    "then come back here — changing your recovery code or password both need it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            return@Column
        }

        Spacer(Modifier.height(32.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        Text("Recovery code", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Generates a new code and invalidates the old one. Your songs aren't re-encrypted and " +
                "your password keeps working — useful if you never saved the code you were shown " +
                "when you signed up.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !regenerating,
            onClick = {
                regenerateError = null
                regenerating = true
                scope.launch {
                    try {
                        newRecoveryCode = authRepo.regenerateRecoveryCode()
                    } catch (e: Exception) {
                        regenerateError = reportAuthFailure("Generating a new recovery code", e)
                    } finally {
                        regenerating = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (regenerating) CircularProgressIndicator(modifier = Modifier.height(20.dp)) else Text("Generate a new recovery code")
        }
        regenerateError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(32.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        Text("Change password", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            PasswordPolicy.HELP_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it; passwordChanged = false },
            label = { Text("New password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it; passwordChanged = false },
            label = { Text("Confirm new password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !changingPassword && newPassword.isNotBlank(),
            onClick = {
                passwordError = null
                passwordChanged = false
                if (newPassword != confirmPassword) {
                    passwordError = "Passwords do not match."
                    return@Button
                }
                PasswordPolicy.validateNewPassword(newPassword)?.let {
                    passwordError = it
                    return@Button
                }
                changingPassword = true
                scope.launch {
                    try {
                        authRepo.changePassword(newPassword)
                        passwordChanged = true
                        newPassword = ""
                        confirmPassword = ""
                    } catch (e: Exception) {
                        passwordError = reportAuthFailure("Changing your password", e)
                    } finally {
                        changingPassword = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (changingPassword) CircularProgressIndicator(modifier = Modifier.height(20.dp)) else Text("Change password")
        }
        passwordError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (passwordChanged) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Password changed. Your recovery code is unchanged and still works.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
