package com.artflow.studio.presentation.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.artflow.studio.core.color.StudioPalette
import com.artflow.studio.domain.model.settings.AccentChoice

/** Complete neutral surface roles: no default lavender containers or translucent accent backgrounds. */
fun studioColorScheme(
    choice: AccentChoice,
    dark: Boolean,
    highContrast: Boolean,
): ColorScheme {
    val tones = StudioPalette.surfaces(dark, highContrast)
    val accent = StudioPalette.accent(choice.seed.toInt(), dark, highContrast)
    val target = if (highContrast) 7.1 else 4.6
    val error = StudioPalette.accent(0xFFB3261E.toInt(), dark, highContrast)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val inverse = StudioPalette.surfaces(!dark, highContrast)
    return base.copy(
        primary = Color(accent.primary),
        onPrimary = Color(accent.onPrimary),
        primaryContainer = Color(accent.container),
        onPrimaryContainer = Color(accent.onContainer),
        secondary = Color(accent.primary),
        onSecondary = Color(accent.onPrimary),
        secondaryContainer = Color(tones.highest),
        onSecondaryContainer = Color(tones.text),
        tertiary = Color(accent.primary),
        onTertiary = Color(accent.onPrimary),
        tertiaryContainer = Color(accent.container),
        onTertiaryContainer = Color(accent.onContainer),
        background = Color(tones.background),
        onBackground = Color(tones.text),
        surface = Color(tones.surface),
        onSurface = Color(tones.text),
        surfaceVariant = Color(tones.variant),
        onSurfaceVariant = Color(tones.secondaryText),
        surfaceTint = Color.Transparent,
        surfaceDim = Color(tones.background),
        surfaceBright = Color(if (dark) tones.highest else tones.lowest),
        surfaceContainerLowest = Color(tones.lowest),
        surfaceContainerLow = Color(tones.low),
        surfaceContainer = Color(tones.normal),
        surfaceContainerHigh = Color(tones.high),
        surfaceContainerHighest = Color(tones.highest),
        outline = Color(StudioPalette.readable(0xFF888888.toInt(), tones.highest, if (highContrast) 4.6 else 3.1)),
        outlineVariant = Color(StudioPalette.mix(tones.highest, tones.text, if (highContrast) 0.5 else 0.22)),
        inverseSurface = Color(inverse.surface),
        inverseOnSurface = Color(inverse.text),
        inversePrimary = Color(StudioPalette.readable(choice.seed.toInt(), inverse.surface, target)),
        error = Color(error.primary),
        onError = Color(error.onPrimary),
        errorContainer = Color(error.container),
        onErrorContainer = Color(error.onContainer),
        scrim = Color.Black,
    )
}
