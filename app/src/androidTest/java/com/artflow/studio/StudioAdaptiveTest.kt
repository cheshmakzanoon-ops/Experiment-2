package com.artflow.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.render.BrushPractice
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.presentation.ui.components.brush.BrushStudioContent
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioAdaptiveTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun interfaceScaleIsAppliedOnceAndSystemTextScaleIsPreserved() {
        var scale by mutableFloatStateOf(1.6f)
        var measuredDensity = 0f
        var measuredFontScale = 0f
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                ArtFlowTheme(AppSettings(themeMode = ThemeMode.DARK, uiScale = scale)) {
                    val current = LocalDensity.current
                    SideEffect {
                        measuredDensity = current.density
                        measuredFontScale = current.fontScale
                    }
                    Box(Modifier.size(48.dp))
                }
            }
        }
        compose.runOnIdle {
            assertEquals(3.2f, measuredDensity, 0.001f)
            assertEquals(1.3f, measuredFontScale, 0.001f)
            scale = 0.5f
        }
        compose.runOnIdle {
            assertEquals(1.7f, measuredDensity, 0.001f)
            assertEquals(1.3f, measuredFontScale, 0.001f)
            scale = Float.NaN
        }
        compose.runOnIdle {
            assertEquals(2f, measuredDensity, 0.001f)
            assertEquals(1.3f, measuredFontScale, 0.001f)
        }
    }

    @Test
    fun studioAdaptsToTheDeviceAndKeepsDraftsAndPracticeAcrossThemeChanges() {
        var dark by mutableStateOf(false)
        var tablet = false
        var applied: BrushParams? = null
        compose.setContent {
            val configuration = LocalConfiguration.current
            SideEffect { tablet = configuration.screenWidthDp >= 800 && configuration.screenHeightDp >= 700 }
            ArtFlowTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                BrushStudioContent(BrushParams(), { applied = it }, {})
            }
        }
        compose.onNodeWithText("Search brushes").assertIsDisplayed()
        if (tablet) {
            compose.onNodeWithTag("studio-split-layout").assertIsDisplayed()
            compose.onNodeWithTag("brush-practice-pad").assertIsDisplayed()
        } else {
            compose.onNodeWithTag("studio-split-layout").assertDoesNotExist()
            compose.onNodeWithText("Drawing pad").performClick()
        }
        compose.onNodeWithTag("brush-practice-pad").performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.5f), Offset(width * 0.8f, height * 0.5f))
        }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithContentDescription("Spacing").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0.32f) }
        if (tablet) {
            compose.onNodeWithTag("brush-practice-pad").assertIsDisplayed()
        } else {
            compose.onNodeWithText("Drawing pad").performClick()
        }
        assertRetainedStroke()
        awaitVisibleStroke()
        val suffix = if (tablet) "tablet" else "compact"
        TestEvidence.screenshot("studio-$suffix-light.png")
        compose.runOnIdle { dark = true }
        assertRetainedStroke()
        awaitVisibleStroke()
        TestEvidence.screenshot("studio-$suffix-dark.png")
        compose.onNodeWithText("Use brush").performClick()
        compose.runOnIdle { assertEquals(0.32f, requireNotNull(applied).spacing, 0.001f) }
    }

    private fun awaitVisibleStroke() {
        compose.waitUntil(10_000) {
            TestEvidence.centreRowColors("Brush practice pad")?.any { it != BrushPractice.PAPER } == true
        }
    }

    private fun assertRetainedStroke() {
        compose
            .onNodeWithTag("brush-practice-pad")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 test strokes; not part of artwork"))
    }
}
