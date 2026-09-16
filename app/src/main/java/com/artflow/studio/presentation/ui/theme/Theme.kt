package com.artflow.studio.presentation.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.artflow.studio.domain.model.settings.AccentChoice
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.ThemeMode

/** Accessibility and motion flags the whole UI can read without threading them through props. */
data class ArtFlowThemeFlags(
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val largeTouchTargets: Boolean = false,
)

val LocalArtFlowFlags = staticCompositionLocalOf { ArtFlowThemeFlags() }

/**
 * Dark scheme. The studio is a dark room: chrome recedes, artwork stays bright.
 */
private fun darkScheme(
    accent: Color,
    highContrast: Boolean,
) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = if (highContrast) 0.42f else 0.28f),
    onPrimaryContainer = Color.White,
    secondary = ArtFlowSecondary,
    onSecondary = Color.White,
    secondaryContainer = ArtFlowSecondaryVariant,
    onSecondaryContainer = Color.White,
    tertiary = ArtFlowSecondary,
    onTertiary = Color.White,
    background = if (highContrast) Color(0xFF0E0E10) else ArtFlowBackground,
    onBackground = if (highContrast) Color.White else TextPrimary,
    surface = if (highContrast) Color(0xFF1C1C20) else ArtFlowSurface,
    onSurface = if (highContrast) Color.White else TextPrimary,
    surfaceVariant = if (highContrast) Color(0xFF303036) else ArtFlowSurfaceVariant,
    onSurfaceVariant = if (highContrast) Color(0xFFEDEDED) else TextSecondary,
    outline = if (highContrast) Color(0xFFBFBFBF) else Color(0xFF6E6E76),
    outlineVariant = if (highContrast) Color(0xFF8A8A92) else Color(0xFF4A4A52),
    error = Error,
    onError = Color.White,
)

private fun lightScheme(
    accent: Color,
    highContrast: Boolean,
) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = if (highContrast) 0.30f else 0.18f),
    onPrimaryContainer = Color(0xFF16181D),
    secondary = ArtFlowSecondary,
    onSecondary = Color.White,
    secondaryContainer = ArtFlowSecondaryVariant.copy(alpha = 0.25f),
    onSecondaryContainer = Color(0xFF16181D),
    tertiary = ArtFlowSecondary,
    onTertiary = Color.White,
    background = CanvasWhite,
    onBackground = Color(0xFF14161B),
    surface = Color(0xFFF7F7F9),
    onSurface = Color(0xFF14161B),
    surfaceVariant = Color(0xFFE7E7EC),
    onSurfaceVariant = if (highContrast) Color(0xFF2A2D34) else Color(0xFF4A4E57),
    outline = if (highContrast) Color(0xFF3A3D44) else Color(0xFF8A8D95),
    outlineVariant = Color(0xFFC5C7CE),
    error = Error,
    onError = Color.White,
)

/** Accent seed (`0xFFRRGGBB`) to a Compose colour. */
fun AccentChoice.composeColor(): Color = Color(seed.toInt())

/**
 * ArtFlow theme.
 *
 * Applies the user's theme mode, accent, contrast, motion and text-scale preferences. Passing
 * [settings] keeps the theme reactive: changing a preference in Settings restyles the app instantly.
 */
@Composable
fun ArtFlowTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (settings.themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    val accent = settings.accent.composeColor()
    val colorScheme =
        if (darkTheme) {
            darkScheme(accent, settings.highContrast)
        } else {
            lightScheme(accent, settings.highContrast)
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val baseDensity = LocalDensity.current
    val uiScale = settings.uiScale.coerceIn(0.85f, 1.6f)
    CompositionLocalProvider(
        LocalArtFlowFlags provides
            ArtFlowThemeFlags(
                highContrast = settings.highContrast,
                reduceMotion = settings.reduceMotion,
                largeTouchTargets = settings.largeTouchTargets,
            ),
        LocalDensity provides
            Density(
                density = baseDensity.density * uiScale,
                fontScale = baseDensity.fontScale * uiScale,
            ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
