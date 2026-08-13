package com.songnotes.android

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.songnotes.core.data.RecoveryCodeMismatchException
import com.songnotes.core.data.SupabaseAuthRepository
import kotlinx.coroutines.launch

/**
 * Recovery-code redemption -- the Android side of the web app's `keyMismatch`
 * flow (LoginPage.jsx). Reached when Supabase Auth accepted a password but the
 * stored envelope doesn't (`EnvelopeKeyMismatchException`), whether that's
 * because the account password was changed via the web app's forgot-password
 * flow, or [KeySession] is simply empty after the process was killed and the
 * cached password no longer matches for some other reason.
 *
 * Only the non-destructive path (Path A: recover via code, rewrap for
 * [newPassword]) lives here -- the destructive "lost the code, start fresh and
 * purge everything" path is deliberately web-only for now (see docs/PLAN.md's
 * forgot-password phase entry), so "lost your code too" links out to the web
 * app instead of reimplementing it.
 */
@Composable
fun RecoveryUnlockScreen(newPassword: String, onDone: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val authRepo = remember { SupabaseAuthRepository() }
    val scope = rememberCoroutineScope()

    var recoveryCode by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Recover your account", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "You're signed in, but your saved encryption key doesn't match this password. " +
                "Enter your recovery code (shown once when you signed up) to get back every " +
                "encrypted song under the original key -- nothing is lost.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = recoveryCode,
            onValueChange = { recoveryCode = it },
            label = { Text("Recovery Code") },
            placeholder = { Text("XXXXX-XXXXX-XXXXX-XXXXX") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Button(
            enabled = !isLoading && recoveryCode.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                isLoading = true
                errorText = null
                scope.launch {
                    try {
                        // Normalization (case/hyphen/whitespace-insensitive matching)
                        // happens inside recoverWithRecoveryCode's crypto layer.
                        authRepo.recoverWithRecoveryCode(recoveryCode, newPassword)
                        onDone()
                    } catch (e: RecoveryCodeMismatchException) {
                        errorText = e.message
                    } catch (e: Exception) {
                        errorText = e.message ?: "Something went wrong"
                    } finally {
                        isLoading = false
                    }
                }
            },
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.height(20.dp)) else Text("Recover access")
        }

        errorText?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEB_FORGOT_PASSWORD_URL)))
        }) {
            Text("Don't have your recovery code either?")
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}
