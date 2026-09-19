package com.artflow.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.canvas.LayerTransform
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import com.artflow.studio.presentation.ui.components.editor.TransformSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransformUiRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun draftControlsDoNotMutateUntilApplyAndUseUniformScale() {
        var result: LayerTransform.Parameters? = null
        var closed = 0
        compose.setContent {
            MaterialTheme {
                TransformSheet("Ink layer", false, {
                    result = it
                    true
                }, { closed++ })
            }
        }
        compose.onNodeWithText("Width %").performScrollTo().performTextReplacement("75")
        compose.onNodeWithText("Rotation °").performScrollTo().performTextReplacement("25")
        compose.onNodeWithText("Flip horizontal").performScrollTo().performClick()
        compose.runOnIdle { assertNull(result) }
        compose.onNodeWithText("Apply transform").performScrollTo().performClick()
        compose.runOnIdle {
            val applied = requireNotNull(result)
            assertEquals(0.75f, applied.scaleX, 0f)
            assertEquals(0.75f, applied.scaleY, 0f)
            assertEquals(25f, applied.rotationDegrees, 0f)
            assertTrue(applied.flipHorizontal)
            assertEquals(1, closed)
        }
    }

    @Test fun cancelDoesNotApplyAndInvalidNumbersDisableCommit() {
        var applied = false
        var closed = false
        compose.setContent {
            MaterialTheme {
                TransformSheet("Ink layer", false, {
                    applied = true
                    true
                }, { closed = true })
            }
        }
        compose.onNodeWithText("Width %").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("Apply transform").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(closed)
            assertFalse(applied)
        }
    }

    @Test fun activeSelectionCannotSilentlyTransformTheWholeLayer() {
        compose.setContent {
            MaterialTheme { TransformSheet("Ink layer", true, { error("Must not apply") }, {}) }
        }
        compose.onNodeWithText("Deselect before transforming the entire layer.").assertIsDisplayed()
        compose.onNodeWithText("Apply transform").performScrollTo().assertIsNotEnabled()
    }
    @Test
    fun signedNumbersAndWrappedControlsRemainUsableWithLargeText() {
        var result: LayerTransform.Parameters? = null
        compose.setContent {
            ArtFlowTheme(AppSettings(uiScale = 1.6f, largeTouchTargets = true, reduceMotion = true)) {
                TransformSheet("Ink layer", false, { result = it; true }, {})
            }
        }
        compose.onNodeWithText("Move X (px)").performScrollTo().performTextReplacement("-12.5")
        compose.onNodeWithText("Horizontal skew °").performScrollTo().performTextReplacement("-20")
        compose.onNodeWithText("Flip vertical").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Pixel art").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Apply transform").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            val applied = requireNotNull(result)
            assertEquals(-12.5f, applied.translationX, 0f)
            assertEquals(-20f, applied.skewXDegrees, 0f)
            assertTrue(applied.flipVertical)
            assertEquals(LayerTransform.Interpolation.NEAREST, applied.interpolation)
        }
    }

}
