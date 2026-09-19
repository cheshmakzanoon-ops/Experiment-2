package com.artflow.studio.presentation.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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
    val colorScheme =
        remember(settings.accent, darkTheme, settings.highContrast) {
            studioColorScheme(settings.accent, darkTheme, settings.highContrast)
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
    val uiScale = settings.uiScale.takeIf { it.isFinite() }?.coerceIn(0.85f, 1.6f) ?: 1f
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
                // Density already scales sp as well as dp. Preserve the independent system text preference.
                fontScale = baseDensity.fontScale,
            ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
