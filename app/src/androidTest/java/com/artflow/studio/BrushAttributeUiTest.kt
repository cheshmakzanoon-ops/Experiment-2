package com.artflow.studio

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.presentation.ui.components.brush.BrushAttribute
import com.artflow.studio.presentation.ui.components.brush.BrushSettingsWorkspace
import com.artflow.studio.presentation.ui.components.brush.BrushStudioContent
import com.artflow.studio.presentation.ui.theme.ArtFlowThemeFlags
import com.artflow.studio.presentation.ui.theme.LocalArtFlowFlags
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrushAttributeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactNavigationShowsOnlyTheSelectedGroupAndRetainsEdits() {
        var parameters by mutableStateOf(BrushParams())
        var attribute by mutableStateOf(BrushAttribute.ALL)
        compose.setContent {
            MaterialTheme {
                BrushSettingsWorkspace(
                    parameters,
                    { parameters = it },
                    attribute,
                    { attribute = it },
                    Modifier.widthIn(max = 300.dp).height(400.dp),
                )
            }
        }
        compose.onNodeWithTag("brush-attribute-strip").assertIsDisplayed()
        choose("Wet paint")
        compose.onNodeWithContentDescription("Spacing").assertDoesNotExist()
        compose.onNodeWithContentDescription("Wet Mix").performScrollTo().assertIsDisplayed()
        change("Wet Mix", 0.65f)
        choose("Stroke")
        compose.onNodeWithContentDescription("Wet Mix").assertDoesNotExist()
        change("Spacing", 0.35f)
        choose("Wet paint")
        compose.runOnIdle {
            assertEquals(0.65f, parameters.wetMix, 0.0001f)
            assertEquals(0.35f, parameters.spacing, 0.0001f)
        }
        TestEvidence.screenshot("studio-brush-attribute.png")
        choose("All settings")
        compose.onNodeWithContentDescription("Spacing").assertExists()
        compose.onNodeWithContentDescription("Wet Mix").assertExists()
    }

    @Test
    fun newlyReachableDynamicsUpdateOnlyTheMatchingParameters() {
        val original = BrushParams(size = 31f, textureId = "paper", blendTexture = true)
        var parameters by mutableStateOf(original)
        compose.setContent {
            MaterialTheme {
                BrushSettingsWorkspace(
                    parameters,
                    { parameters = it },
                    BrushAttribute.DYNAMICS,
                    {},
                    Modifier.widthIn(max = 300.dp).height(400.dp),
                )
            }
        }
        change("Speed → size", 0.8f)
        change("Speed → opacity", 0.6f)
        change("Speed → hue", 0.4f)
        compose
            .onNodeWithContentDescription("Pressure changes brightness")
            .performScrollTo()
            .performClick()
            .assertIsOn()
        compose.runOnIdle {
            assertEquals(
                original.copy(velocityToSize = 0.8f, velocityToOpacity = 0.6f, velocityToHue = 0.4f, colorPressure = true),
                parameters,
            )
        }
    }

    @Test
    fun studioRetainsTheChosenGroupAcrossTabsAndPublishesOnlyOnUse() {
        val applied = mutableListOf<BrushParams>()
        val original = BrushParams(size = 29f)
        var open by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open) BrushStudioContent(original, { applied.add(it) }, { open = false })
            }
        }
        compose.onNodeWithText("Settings").performClick()
        choose("Speed & colour")
        change("Speed → hue", 0.75f)
        compose.onNodeWithText("Drawing pad").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Speed & colour").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Spacing").assertDoesNotExist()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.onNodeWithText("Use brush").performClick()
        compose.runOnIdle { assertEquals(listOf(original.copy(velocityToHue = 0.75f)), applied) }
    }

    @Test
    fun cancellingAnAttributeEditLeavesThePublishedBrushUntouched() {
        var applied: BrushParams? = null
        var open by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open) BrushStudioContent(BrushParams(), { applied = it }, { open = false })
            }
        }
        compose.onNodeWithText("Settings").performClick()
        choose("Speed & colour")
        change("Speed → size", 1f)
        compose.onNodeWithContentDescription("Cancel brush changes").performClick()
        compose.onNodeWithText("Brush studio").assertDoesNotExist()
        compose.runOnIdle { assertNull(applied) }
    }

    @Test
    fun largerTargetsRemainAvailableInTheCompactNavigator() {
        compose.setContent {
            CompositionLocalProvider(LocalArtFlowFlags provides ArtFlowThemeFlags(largeTouchTargets = true)) {
                MaterialTheme {
                    BrushSettingsWorkspace(
                        BrushParams(),
                        {},
                        BrushAttribute.DYNAMICS,
                        {},
                        Modifier.widthIn(max = 300.dp).height(400.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("All settings").assertHeightIsAtLeast(56.dp)
        compose
            .onNodeWithContentDescription("Pressure changes brightness")
            .performScrollTo()
            .assertHeightIsAtLeast(56.dp)
    }

    private fun choose(label: String) {
        compose
            .onNodeWithText(label)
            .performScrollTo()
            .performClick()
            .assertIsSelected()
    }

    private fun change(
        label: String,
        value: Float,
    ) {
        compose.onNodeWithContentDescription(label).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }
}
