package com.songnotes.android

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The editor's "paper" palette -- the warm parchment look the song editor,
 * chord tokens and meta fields are built around. Kept as its own type rather
 * than folded into [MaterialTheme.colorScheme] because these are specific
 * document-surface roles ("the colour of a chord", "the ruled line on the
 * page") with no honest Material equivalent; mapping `chord` onto `primary`
 * would just make both harder to reason about.
 */
data class SongNotesColors(
    val parchment: Color,
    val chord: Color,
    val lyric: Color,
    val muted: Color,
    val paperLine: Color,
    val voicingSheet: Color,
    val scrim: Color,
)

/**
 * Warm Light -- the palette the app shipped with, unchanged. These exact values
 * were previously hardcoded as private vals in `SongEditorScreen.kt`.
 */
private val WarmLightColors = SongNotesColors(
    parchment = Color(0xFFF7F1E6),
    chord = Color(0xFFB45309),
    lyric = Color(0xFF2A221B),
    muted = Color(0xFF8A7663),
    paperLine = Color(0xFFB45309).copy(alpha = 0.16f),
    voicingSheet = Color.White,
    scrim = Color.Black.copy(alpha = 0.35f),
)

/**
 * Cozy Dark -- ported value-for-value from the web app's own dark theme
 * (`src/styles/global.css`'s `body.cozy-dark` block), so the two clients look
 * like the same product rather than two independent guesses at "dark mode".
 * `paper-bg` -> [parchment], `chord-color` -> [chord], `lyric-color` -> [lyric],
 * `text-muted` -> [muted], `paper-line` -> [paperLine].
 *
 * Note the chord colour is deliberately NOT the same amber as light mode: the
 * web app lightens it (`#B45309` -> `#F59E0B`) because the darker amber loses
 * contrast against a dark page. Copying the light value here would have been
 * the obvious mistake.
 */
private val CozyDarkColors = SongNotesColors(
    parchment = Color(0xFF211C18),
    chord = Color(0xFFF59E0B),
    lyric = Color(0xFFE4D8CB),
    muted = Color(0xFF998471),
    paperLine = Color(0xFFD97706).copy(alpha = 0.22f),
    voicingSheet = Color(0xFF2F2923),
    scrim = Color.Black.copy(alpha = 0.55f),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFB45309),
    onPrimary = Color.White,
    background = Color(0xFFF7F1E6),
    onBackground = Color(0xFF2A221B),
    surface = Color(0xFFF7F1E6),
    onSurface = Color(0xFF2A221B),
    onSurfaceVariant = Color(0xFF8A7663),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF59E0B),
    onPrimary = Color(0xFF12100E),
    background = Color(0xFF12100E),
    onBackground = Color(0xFFF5EEE6),
    surface = Color(0xFF1A1613),
    onSurface = Color(0xFFF5EEE6),
    onSurfaceVariant = Color(0xFF998471),
)

val LocalSongNotesColors = staticCompositionLocalOf { WarmLightColors }

/** The document palette for the current theme. `SongNotesTheme.colors` at any call site. */
object SongNotesTheme {
    val colors: SongNotesColors
        @Composable @ReadOnlyComposable get() = LocalSongNotesColors.current
}

/** What the user picked, independent of what the system is currently doing. */
enum class ThemeMode {
    /** Follow the device's own light/dark setting. The default. */
    SYSTEM,
    LIGHT,
    DARK,
    ;

    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]

    val label: String
        get() = when (this) {
            SYSTEM -> "Theme: system"
            LIGHT -> "Theme: light"
            DARK -> "Theme: dark"
        }
}

/**
 * Persisted theme choice. SharedPreferences, matching `EditorSessionStore` and
 * `SyncPreferences` -- this repo has no DataStore dependency and one enum
 * doesn't justify adding one.
 */
class ThemePreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("songnotes_theme", Context.MODE_PRIVATE)

    var mode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_MODE, null) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    private companion object {
        const val KEY_MODE = "theme_mode"
    }
}

/**
 * App theme. Replaces the bare `MaterialTheme { }` in `MainActivity`, which
 * silently pinned every screen to Material's default light scheme regardless of
 * the device setting -- the app had no dark mode at all, while the web app has
 * had a Cozy Dark / Warm Light toggle for a long time. On mobile that gap is
 * more conspicuous than on desktop: dark mode is a system-level setting users
 * expect apps to honour, and this is an app people use in dim rooms and on
 * stage.
 *
 * [ThemeMode.SYSTEM] is the default because following the OS is the platform
 * convention; the explicit override exists because the web app offers one and
 * because "the whole phone is dark but I want the page to look like paper" is a
 * reasonable thing to want from a songwriting app.
 */
@Composable
fun SongNotesTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalSongNotesColors provides if (dark) CozyDarkColors else WarmLightColors) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
