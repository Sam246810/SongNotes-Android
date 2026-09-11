package com.songnotes.android

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Marks the composable that hosts this as screenshot- and
 * recents-thumbnail-excluded for as long as it's on screen, via
 * [WindowManager.LayoutParams.FLAG_SECURE].
 *
 * Deliberately scoped to individual screens rather than set once on the
 * Activity: the whole app being FLAG_SECURE would stop people screenshotting
 * their own lyrics to send to a bandmate, which is a perfectly reasonable thing
 * to do with this app and not something worth breaking. What actually warrants
 * it is the recovery code -- ~100 bits of account master key, shown exactly once
 * with "It will not be shown again", and otherwise sitting in the app-switcher
 * thumbnail until the process dies.
 *
 * The flag is cleared again on dispose, so leaving the screen restores normal
 * behavior for the rest of the app.
 */
@Composable
fun SecureScreen() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
