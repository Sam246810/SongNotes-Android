package com.songnotes.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Phase 13: shown once, before the sign-up form actually submits, whenever
 * [com.songnotes.core.data.SyncPreferences.explainerAcknowledged] is still
 * false -- explains what syncing actually does before the user commits to
 * creating an account for it. Two things must land, both required by the
 * product decision behind Phase 13 (see `docs/handoff/PHASE-13-local-first.md`):
 * syncing unlocks editing the same songs from the web app, and the
 * end-to-end encryption that makes that safe also means losing BOTH the
 * account password and the recovery code makes the account's songs
 * permanently unrecoverable -- by anyone, including the developer.
 */
@Composable
fun SyncOptInExplainer(onAcknowledge: () -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Before you sync", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "Syncing lets you open and edit these same songs from the web app -- one account, " +
                "two places to write.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Your songs are encrypted on this device before they ever leave it -- we can't read " +
                "them, and neither can anyone else who isn't you. That also means if you lose BOTH " +
                "your account password AND your recovery code, the songs on your account cannot be " +
                "recovered. Not by you, not by us.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth()) {
            Text("I understand, continue")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

/**
 * Shown once, right after a successful sign-up (after the recovery-code
 * screen has been acknowledged) -- Phase 13 made sync strictly manual, so the
 * very first thing a new account holder does after creating an account must
 * not be "nothing happens automatically and I have no idea why." Also
 * reachable conceptually for a first sign-in to an existing account, though
 * `AuthScreen` currently only routes here from sign-up (a returning user
 * signing back in already knows sync is manual from their first time).
 */
@Composable
fun ManualSyncNotice(onContinue: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("One more thing", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "Your songs stay on this phone until you press Sync. Nothing uploads automatically -- " +
                "use the Sync button on your song list whenever you want your latest changes on the " +
                "web app.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Got it")
        }
    }
}
