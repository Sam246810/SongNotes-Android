package com.songnotes.android

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

// Every role is set explicitly. An earlier version set only primary and the
// surfaces, so anything using a container or variant role (cards, chips,
// filled icon buttons -- most of the Scratchpad) fell back to Material's
// default purple/lavender, which is what made it look like a different app.
// Secondary is a warm taupe (ink on paper), tertiary a muted sage, and the
// surface ladder steps from parchment toward a deeper kraft tone.
private val LightScheme = lightColorScheme(
    primary = Color(0xFFB45309),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE3C2),
    onPrimaryContainer = Color(0xFF4A2604),
    inversePrimary = Color(0xFFF59E0B),
    secondary = Color(0xFF7C6A55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE1CE),
    onSecondaryContainer = Color(0xFF2F2418),
    tertiary = Color(0xFF4D7C5B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9E8D5),
    onTertiaryContainer = Color(0xFF17301F),
    background = Color(0xFFF7F1E6),
    onBackground = Color(0xFF2A221B),
    surface = Color(0xFFF7F1E6),
    onSurface = Color(0xFF2A221B),
    surfaceVariant = Color(0xFFEBE0CE),
    onSurfaceVariant = Color(0xFF8A7663),
    surfaceTint = Color(0xFFB45309),
    surfaceBright = Color(0xFFFBF6EE),
    surfaceDim = Color(0xFFE4D8C6),
    surfaceContainerLowest = Color(0xFFFFFBF5),
    surfaceContainerLow = Color(0xFFF3EBDE),
    surfaceContainer = Color(0xFFEEE4D5),
    surfaceContainerHigh = Color(0xFFE8DDCB),
    surfaceContainerHighest = Color(0xFFE2D5C1),
    outline = Color(0xFFB9A891),
    outlineVariant = Color(0xFFDDD0BC),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF362E27),
    inverseOnSurface = Color(0xFFF7EEE3),
    scrim = Color.Black,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF59E0B),
    onPrimary = Color(0xFF12100E),
    primaryContainer = Color(0xFF5A3A0C),
    onPrimaryContainer = Color(0xFFFFDDB0),
    inversePrimary = Color(0xFFB45309),
    secondary = Color(0xFFCDBBA4),
    onSecondary = Color(0xFF2A2119),
    secondaryContainer = Color(0xFF3E342A),
    onSecondaryContainer = Color(0xFFEADCC8),
    tertiary = Color(0xFF9CC5A6),
    onTertiary = Color(0xFF12241A),
    tertiaryContainer = Color(0xFF2C4434),
    onTertiaryContainer = Color(0xFFCDE8D3),
    background = Color(0xFF12100E),
    onBackground = Color(0xFFF5EEE6),
    surface = Color(0xFF1A1613),
    onSurface = Color(0xFFF5EEE6),
    surfaceVariant = Color(0xFF2F2923),
    onSurfaceVariant = Color(0xFF998471),
    surfaceTint = Color(0xFFF59E0B),
    surfaceBright = Color(0xFF3A332C),
    surfaceDim = Color(0xFF12100E),
    surfaceContainerLowest = Color(0xFF0E0C0A),
    surfaceContainerLow = Color(0xFF1E1A16),
    surfaceContainer = Color(0xFF241F1A),
    surfaceContainerHigh = Color(0xFF2D2721),
    surfaceContainerHighest = Color(0xFF37302A),
    outline = Color(0xFF6E5F51),
    outlineVariant = Color(0xFF3E352D),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFEDE3D8),
    inverseOnSurface = Color(0xFF2A221B),
    scrim = Color.Black,
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

    /** Shown in [SettingsScreen]'s theme choices. */
    val displayName: String
        get() = when (this) {
            SYSTEM -> "Match device"
            LIGHT -> "Light"
            DARK -> "Dark"
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
    // Status/navigation bar icons follow the app's theme, not the device's.
    // Nothing set them before, so Android drew white icons on the light
    // parchment (clock, battery all but invisible) -- and with the in-app
    // override, the device's own dark setting can disagree with the page.
    // Screens that paint themselves dark (the piano) flip this while open and
    // restore it on the way out.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            // With 3-button navigation Android lays a translucent grey scrim
            // over the nav bar "for contrast", which is why it never matched
            // the theme. Every screen paints its own background edge to edge
            // and pads its content above the bar, so the scrim isn't needed:
            // the page colour shows through and the icons follow the theme.
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }
    CompositionLocalProvider(LocalSongNotesColors provides if (dark) CozyDarkColors else WarmLightColors) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
