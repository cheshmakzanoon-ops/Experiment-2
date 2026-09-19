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
import com.artflow.studio.presentation.ui.components.brush.AdvancedBrushSettingsPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrushSettingsAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsAcrossTheWholeBrushPanelRemainReachableInABoundedViewport() {
        var parameters by mutableStateOf(BrushParams(pressureCurve = BrushParams.PressureCurve.CUSTOM))
        compose.setContent {
            MaterialTheme {
                AdvancedBrushSettingsPanel(parameters, { parameters = it }, Modifier.widthIn(max = 300.dp).height(260.dp))
            }
        }
        change("Spacing", 0.3f)
        change("End Taper", 0.4f)
        change("Pressure → Size", 0.8f)
        change("Output at 25% pressure", 0.2f)
        change("Output at 50% pressure", 0.6f)
        change("Output at 75% pressure", 0.9f)
        compose.onNodeWithTag("brush-grain-options").performScrollTo()
        compose.onNodeWithText("Paper").performScrollTo().performClick()
        change("Grain scale", 2f)
        change("Count", 3f)
        change("Brightness Jitter", 0.2f)
        change("Rotation", 90f)
        change("Wet Mix", 0.65f)
        compose.runOnIdle {
            assertEquals(0.3f, parameters.spacing, 0.001f)
            assertEquals(0.4f, parameters.taperEnd, 0.001f)
            assertEquals(0.8f, parameters.pressureToSize, 0.001f)
            assertEquals(0.2f, parameters.customPressure.low, 0.001f)
            assertEquals(0.6f, parameters.customPressure.middle, 0.001f)
            assertEquals(0.9f, parameters.customPressure.high, 0.001f)
            assertTrue(parameters.blendTexture)
            assertEquals("paper", parameters.textureId)
            assertEquals(2f, parameters.textureScale, 0.001f)
            assertEquals(3, parameters.count)
            assertEquals(0.2f, parameters.brightnessJitter, 0.001f)
            assertEquals(90f, parameters.rotation, 0.001f)
            assertEquals(0.65f, parameters.wetMix, 0.001f)
        }
        TestEvidence.screenshot("studio-brush-options.png")
    }

    @Test
    fun collapsingASectionPreservesEditedParametersAndScrollAccess() {
        var parameters by mutableStateOf(BrushParams())
        compose.setContent {
            MaterialTheme {
                AdvancedBrushSettingsPanel(parameters, { parameters = it }, Modifier.widthIn(max = 300.dp).height(260.dp))
            }
        }
        change("Wet Mix", 0.75f)
        compose.onNodeWithContentDescription("Collapse Wet Paint").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Wet Mix").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand Wet Paint").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Wet Mix").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0.75f, parameters.wetMix, 0.001f) }
        change("Spacing", 0.2f)
        compose.runOnIdle { assertEquals(0.75f, parameters.wetMix, 0.001f) }
    }

    private fun change(
        label: String,
        value: Float,
    ) {
        compose.onNodeWithContentDescription(label).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }
}
