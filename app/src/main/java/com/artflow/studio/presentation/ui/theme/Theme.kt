package com.artflow.studio.presentation.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
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

/** Neutral surfaces keep the artwork, not a default Material tint, at the centre of the studio. */
private fun darkScheme(highContrast: Boolean) = darkColorScheme(
    background = if (highContrast) Color(0xFF0E0E10) else ArtFlowBackground,
    onBackground = Color.White,
    surface = if (highContrast) Color(0xFF1C1C20) else ArtFlowSurface,
    onSurface = Color.White,
    surfaceVariant = if (highContrast) Color(0xFF303036) else ArtFlowSurfaceVariant,
    onSurfaceVariant = if (highContrast) Color(0xFFEDEDED) else Color(0xFFB8B8C0),
    surfaceDim = if (highContrast) Color(0xFF0E0E10) else ArtFlowBackground,
    surfaceBright = if (highContrast) Color(0xFF303036) else ArtFlowSurfaceVariant,
    surfaceContainerLowest = if (highContrast) Color(0xFF0E0E10) else ArtFlowBackground,
    surfaceContainerLow = if (highContrast) Color(0xFF16161A) else Color(0xFF252525),
    surfaceContainer = if (highContrast) Color(0xFF1C1C20) else ArtFlowSurface,
    surfaceContainerHigh = if (highContrast) Color(0xFF26262C) else Color(0xFF343434),
    surfaceContainerHighest = if (highContrast) Color(0xFF303036) else ArtFlowSurfaceVariant,
    inverseSurface = Color(0xFFE7E7EC),
    inverseOnSurface = Color(0xFF14161B),
    outlineVariant = if (highContrast) Color(0xFF8A8A92) else Color(0xFF62626A),
)

private fun lightScheme(highContrast: Boolean) = lightColorScheme(
    background = CanvasWhite,
    onBackground = Color(0xFF14161B),
    surface = Color(0xFFF7F7F9),
    onSurface = Color(0xFF14161B),
    surfaceVariant = Color(0xFFE7E7EC),
    onSurfaceVariant = if (highContrast) Color(0xFF2A2D34) else Color(0xFF4A4E57),
    surfaceDim = Color(0xFFE7E7EC),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F9),
    surfaceContainer = Color(0xFFF1F1F4),
    surfaceContainerHigh = Color(0xFFECECF0),
    surfaceContainerHighest = Color(0xFFE7E7EC),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color.White,
    outlineVariant = if (highContrast) Color(0xFF686B73) else Color(0xFFC5C7CE),
)

/** Checked UI roles, separate from the unchanged colour seeds displayed by the accent picker. */
internal fun studioColorScheme(dark: Boolean, accent: Color, highContrast: Boolean): ColorScheme {
    val base = if (dark) darkScheme(highContrast) else lightScheme(highContrast)
    val minimum = if (highContrast) 7.0 else 4.5
    // The darkest light-mode / lightest dark-mode surface is the limiting text background.
    fun readable(seed: Color) = Color(ThemeContrast.fit(seed.toArgb(), base.surfaceVariant.toArgb(), minimum))
    fun content(color: Color) = Color(ThemeContrast.contentOn(color.toArgb()))
    fun container(seed: Color) = Color(ThemeContrast.mix(base.surface.toArgb(), seed.toArgb(), if (highContrast) 0.10 else 0.18))
    val primary = readable(accent)
    val primaryContainer = container(accent)
    val secondary = readable(ArtFlowSecondary)
    val secondaryContainer = container(ArtFlowSecondary)
    val error = readable(Error)
    val errorContainer = container(Error)
    return base.copy(
        primary = primary,
        onPrimary = content(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = content(primaryContainer),
        secondary = secondary,
        onSecondary = content(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = content(secondaryContainer),
        tertiary = secondary,
        onTertiary = content(secondary),
        tertiaryContainer = secondaryContainer,
        onTertiaryContainer = content(secondaryContainer),
        error = error,
        onError = content(error),
        errorContainer = errorContainer,
        onErrorContainer = content(errorContainer),
        inversePrimary = Color(ThemeContrast.fit(accent.toArgb(), base.inverseSurface.toArgb(), minimum)),
        outline = Color(ThemeContrast.fit(base.onSurfaceVariant.toArgb(), base.surfaceVariant.toArgb(), if (highContrast) 4.5 else 3.0)),
        // Elevation is expressed through neutral containers, not a hue shift behind artwork.
        surfaceTint = base.surface,
    )
}

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
    val colorScheme = remember(darkTheme, accent, settings.highContrast) {
        studioColorScheme(darkTheme, accent, settings.highContrast)
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
                // uiScale is an extra TEXT scale. Scaling density as well would apply it
                // twice to text and shrink the usable layout width on accessible settings.
                density = baseDensity.density,
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
