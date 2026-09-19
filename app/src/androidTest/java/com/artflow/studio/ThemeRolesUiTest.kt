package com.artflow.studio

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.domain.model.settings.AccentChoice
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import com.artflow.studio.presentation.ui.theme.ThemeContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Check the actual provided Material roles, not an unused palette beside the real theme. */
@RunWith(AndroidJUnit4::class)
class ThemeRolesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allAccentsSupplyReadableRolesInBothModesAndContrastPreferences() {
        var settings by mutableStateOf(AppSettings(themeMode = ThemeMode.DARK))
        lateinit var provided: ColorScheme
        compose.setContent {
            ArtFlowTheme(settings) {
                val colors = MaterialTheme.colorScheme
                SideEffect { provided = colors }
                Text("The studio", color = colors.primary)
            }
        }
        for (accent in AccentChoice.entries) {
            for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
                checkScheme(accent, mode, false, { settings = it }, { provided })
                checkScheme(accent, mode, true, { settings = it }, { provided })
            }
        }
    }

    private fun checkScheme(
        accent: AccentChoice,
        mode: ThemeMode,
        enhanced: Boolean,
        update: (AppSettings) -> Unit,
        provided: () -> ColorScheme,
    ) {
        compose.runOnIdle { update(AppSettings(themeMode = mode, accent = accent, highContrast = enhanced)) }
        compose.runOnIdle {
            val colors = provided()
            val minimum = if (enhanced) 7.0 else 4.5
            val surfaces =
                listOf(
                    colors.background,
                    colors.surface,
                    colors.surfaceVariant,
                    colors.surfaceContainerLowest,
                    colors.surfaceContainerLow,
                    colors.surfaceContainer,
                    colors.surfaceContainerHigh,
                    colors.surfaceContainerHighest,
                    colors.surfaceDim,
                    colors.surfaceBright,
                )
            for (surface in surfaces) {
                checkForegrounds(colors, surface, minimum)
            }
            val filledPairs =
                listOf(
                    colors.onPrimary to colors.primary,
                    colors.onPrimaryContainer to colors.primaryContainer,
                    colors.onSecondary to colors.secondary,
                    colors.onSecondaryContainer to colors.secondaryContainer,
                    colors.onTertiary to colors.tertiary,
                    colors.onTertiaryContainer to colors.tertiaryContainer,
                    colors.onError to colors.error,
                    colors.onErrorContainer to colors.errorContainer,
                    colors.inverseOnSurface to colors.inverseSurface,
                    colors.inversePrimary to colors.inverseSurface,
                )
            filledPairs.forEach { (text, background) -> checkPair(text, background, minimum) }
        }
    }

    private fun checkForegrounds(
        colors: ColorScheme,
        surface: Color,
        minimum: Double,
    ) {
        listOf(colors.primary, colors.secondary, colors.tertiary, colors.error, colors.onSurface, colors.onSurfaceVariant)
            .forEach { checkPair(it, surface, minimum) }
        checkPair(colors.outline, surface, 3.0)
    }

    private fun checkPair(
        text: Color,
        background: Color,
        minimum: Double,
    ) {
        assertEquals(1f, text.alpha, 0f)
        assertEquals(1f, background.alpha, 0f)
        val ratio = ThemeContrast.ratio(text.toArgb(), background.toArgb())
        assertTrue("$text on $background: $ratio must be >= $minimum", ratio >= minimum)
    }
}
