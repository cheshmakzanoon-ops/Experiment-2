package com.artflow.studio.presentation

import androidx.compose.ui.graphics.toArgb
import com.artflow.studio.core.color.StudioPalette
import com.artflow.studio.domain.model.settings.AccentChoice
import com.artflow.studio.presentation.ui.theme.studioColorScheme
import org.junit.Assert.*
import org.junit.Test

class StudioColorSchemeTest {
    @Test
    fun actualMaterialRolesUseTheValidatedPaletteRatherThanDefaultContainers() {
        AccentChoice.entries.forEach { accent ->
            for (dark in listOf(false, true)) {
                for (highContrast in listOf(false, true)) {
                    checkRoles(accent, dark, highContrast)
                }
            }
        }
    }

    private fun checkRoles(
        accent: AccentChoice,
        dark: Boolean,
        highContrast: Boolean,
    ) {
        val scheme = studioColorScheme(accent, dark, highContrast)
        val expected = StudioPalette.surfaces(dark, highContrast)
        val minimum = if (highContrast) 7.0 else 4.5
        assertEquals(expected.highest, scheme.surfaceContainerHighest.toArgb())
        assertEquals(expected.normal, scheme.surfaceContainer.toArgb())
        assertEquals(0f, scheme.surfaceTint.alpha, 0f)
        val pairs =
            listOf(
                scheme.primary to scheme.surfaceContainerHighest,
                scheme.onPrimary to scheme.primary,
                scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surfaceVariant,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiaryContainer to scheme.tertiaryContainer,
                scheme.onError to scheme.error,
                scheme.onErrorContainer to scheme.errorContainer,
                scheme.inversePrimary to scheme.inverseSurface,
                scheme.inverseOnSurface to scheme.inverseSurface,
            )
        pairs.forEach { (text, background) ->
            assertEquals(1f, background.alpha, 0f)
            assertTrue(
                "$accent dark=$dark highContrast=$highContrast: $text on $background",
                StudioPalette.contrast(text.toArgb(), background.toArgb()) >= minimum,
            )
        }
    }
}
