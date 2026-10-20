package com.artflow.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.model.layer.LayerEffects
import com.artflow.studio.presentation.ui.components.editor.LayerEffectsDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayerEffectsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun eachEffectChangeIsAppliedAsItIsMade() {
        val applied = mutableListOf<LayerEffects?>()
        val red = 0xFFFF0000.toInt()
        compose.setContent {
            MaterialTheme {
                LayerEffectsDialog(Layer(id = 3L, name = "Ink", index = 0), red, { applied += it }, {})
            }
        }
        compose.onNodeWithContentDescription("Outline").performClick()
        compose.runOnIdle { assertEquals(LayerEffects(outline = LayerEffects.Outline(color = red)), applied.last()) }
        compose.onNodeWithContentDescription("Outline width").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) {
            it(10f)
        }
        compose.runOnIdle { assertEquals(10f, applied.last()?.outline?.width ?: 0f, 0.5f) }
        compose.onNodeWithContentDescription("Drop shadow").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Shadow softness").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(LayerEffects.Shadow(), applied.last()?.shadow) }
        TestEvidence.screenshot("layer-effects.png")
        // Turning both off removes the effects altogether.
        compose.onNodeWithContentDescription("Outline").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Drop shadow").performScrollTo().performClick()
        compose.runOnIdle { assertNull(applied.last()) }
        compose.onNodeWithText("Done").assertIsDisplayed()
    }
}
