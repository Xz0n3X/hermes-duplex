package org.hermes.duplex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The palette.
 *
 * Dark is the design this app was rebuilt around: the near-black slate, the
 * cyan-to-blue orb and the purple / sky action colours. The semantic names
 * (state_hearing, status_warning...) are the ones the XML app used, so a
 * colour still means the same thing here as it did there. Light keeps the
 * original AgentKit tokens for people who run their phone that way.
 */
@Immutable
data class HermesPalette(
    val bgApp: Color,
    val bgPanel: Color,
    val bgRaised: Color,
    val bgOverlay: Color,
    val borderDefault: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentMuted: Color,
    val statusRunning: Color,
    val statusSuccess: Color,
    val statusFailed: Color,
    val statusWarning: Color,
    val stateHearing: Color,
    val stateHeld: Color,
    val stateSent: Color,
    val stateThinking: Color,
    val stateSpeaking: Color,
    val stateOffline: Color,
    val stateError: Color,
    val ideaBackground: Color,
    val userBubble: Color,
    val onAccent: Color,
    val dark: Boolean,
)

val DarkPalette = HermesPalette(
    bgApp = Color(0xFF030712),
    bgPanel = Color(0xFF0B1220),
    bgRaised = Color(0xFF111827),
    bgOverlay = Color(0xFF1B2027),
    borderDefault = Color(0x1AFFFFFF),
    textPrimary = Color(0xFFE7EBF0),
    textSecondary = Color(0xFF94A3B8),
    textTertiary = Color(0xFF6B7684),
    accent = Color(0xFF5891FF),
    accentMuted = Color(0x295891FF),
    statusRunning = Color(0xFF4AA3FF),
    statusSuccess = Color(0xFF34D399),
    statusFailed = Color(0xFFF2555A),
    statusWarning = Color(0xFFE8A33D),
    stateHearing = Color(0xFF4AA3FF),
    stateHeld = Color(0xFFE8A33D),
    stateSent = Color(0xFF34D399),
    stateThinking = Color(0xFF8A7FD4),
    stateSpeaking = Color(0xFF4FB3C9),
    stateOffline = Color(0xFF6B7684),
    stateError = Color(0xFFF2555A),
    ideaBackground = Color(0xFF263B5A),
    userBubble = Color(0xFF1E293B),
    onAccent = Color.White,
    dark = true,
)

val LightPalette = HermesPalette(
    bgApp = Color(0xFFFFFFFF),
    bgPanel = Color(0xFFFAFBFC),
    bgRaised = Color(0xFFF4F6F8),
    bgOverlay = Color(0xFFFFFFFF),
    borderDefault = Color(0x1F000000),
    textPrimary = Color(0xFF14181D),
    textSecondary = Color(0xFF545E6B),
    textTertiary = Color(0xFF838D99),
    accent = Color(0xFF296CE0),
    accentMuted = Color(0x1A296CE0),
    statusRunning = Color(0xFF1F6FD0),
    statusSuccess = Color(0xFF1A8F56),
    statusFailed = Color(0xFFCF2D33),
    statusWarning = Color(0xFFB3720D),
    stateHearing = Color(0xFF1F6FD0),
    stateHeld = Color(0xFFB3720D),
    stateSent = Color(0xFF1A8F56),
    stateThinking = Color(0xFF6355AB),
    stateSpeaking = Color(0xFF2B8EA3),
    stateOffline = Color(0xFF838D99),
    stateError = Color(0xFFCF2D33),
    ideaBackground = Color(0xFF263B5A),
    userBubble = Color(0xFFE8EEF9),
    onAccent = Color.White,
    dark = false,
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Shorthand: `Palette.accent` anywhere inside [HermesTheme]. */
val Palette: HermesPalette
    @Composable get() = LocalPalette.current

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = if (palette.dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.bgApp,
            onBackground = palette.textPrimary,
            surface = palette.bgPanel,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.bgRaised,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.borderDefault,
            error = palette.statusFailed,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.bgApp,
            onBackground = palette.textPrimary,
            surface = palette.bgPanel,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.bgRaised,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.borderDefault,
            error = palette.statusFailed,
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
