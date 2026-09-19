package com.artflow.studio

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.render.BrushPractice
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StudioBrushes
import com.artflow.studio.presentation.ui.components.brush.BrushParameterSlider
import com.artflow.studio.presentation.ui.components.brush.BrushPracticePad
import com.artflow.studio.presentation.ui.components.brush.BrushStudioDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrushStudioUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchingAndSelectingAPresetDoesNotApplyUntilConfirmed() {
        var applied: BrushParams? = null
        var open by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open) BrushStudioDialog(BrushParams(), { applied = it }, { open = false })
            }
        }
        compose.onNodeWithText("Search brushes").performTextInput("fine liner")
        compose.onNodeWithText("Fine liner").performClick()
        compose.runOnIdle { assertNull(applied) }
        TestEvidence.screenshot("studio-brush-library.png")
        compose.onNodeWithContentDescription("Cancel brush changes").performClick()
        compose.runOnIdle { assertNull(applied) }
        compose.onNodeWithText("Brush studio").assertDoesNotExist()
    }

    @Test
    fun categorySearchAndEmptyResultsCanBeRecovered() {
        compose.setContent {
            MaterialTheme { BrushStudioDialog(BrushParams(), {}, {}) }
        }
        compose.onNodeWithText("Ink").performClick().assertIsSelected()
        compose.onNodeWithText("Search brushes").performTextInput("charcoal")
        compose.onNodeWithText("No matching brushes. Clear the search or choose another category.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear brush search").performClick()
        compose.onNodeWithText("Fine liner").assertIsDisplayed()
        compose.onNodeWithText("Graphite point").assertDoesNotExist()
    }

    @Test
    fun restoringTheDraftAndApplyingUseTheCorrectSnapshot() {
        val original = BrushParams(size = 9f, spacing = 0.23f)
        val applied = mutableListOf<BrushParams>()
        var open by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open) BrushStudioDialog(original, { applied.add(it) }, { open = false })
            }
        }
        compose.onNodeWithText("Restore initial").assertIsNotEnabled()
        compose.onNodeWithText("Search brushes").performTextInput("fine liner")
        compose.onNodeWithText("Fine liner").performClick()
        compose.onNodeWithText("Restore initial").performClick().assertIsNotEnabled()
        compose.onNodeWithText("Use brush").performClick()
        compose.runOnIdle { assertEquals(listOf(original), applied) }
        compose.onNodeWithText("Use brush").assertDoesNotExist()
    }

    @Test
    fun selectingAndUsingAPresetPublishesExactlyOnce() {
        val applied = mutableListOf<BrushParams>()
        var open by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open) BrushStudioDialog(BrushParams(), { applied.add(it) }, { open = false })
            }
        }
        compose.onNodeWithText("Search brushes").performTextInput("fine liner")
        compose.onNodeWithText("Fine liner").performClick()
        compose.onNodeWithText("Use brush").performClick()
        compose.runOnIdle { assertEquals(listOf(StudioBrushes.search("fine liner").single().parameters), applied) }
    }

    @Test
    fun exactPercentageEntryRejectsOutOfRangeAndCancelDoesNotCommit() {
        var value by mutableFloatStateOf(0.1f)
        compose.setContent {
            MaterialTheme { BrushParameterSlider("Spacing", value, { value = it }, 0.01f..1f, "${value * 100}%") }
        }
        compose.onNodeWithContentDescription("Exact Spacing").performClick()
        compose.onNodeWithText("Value").performTextReplacement("101")
        compose.onNodeWithText("Set value").assertIsNotEnabled()
        compose.onNodeWithText("Value").performTextReplacement("37.5")
        compose.onNodeWithText("Set value").performClick()
        compose.runOnIdle { assertEquals(0.375f, value, 0.0001f) }
        compose.onNodeWithContentDescription("Exact Spacing").performClick()
        compose.onNodeWithText("Value").performTextReplacement("80")
        TestEvidence.screenshot("studio-brush-numeric.png")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0.375f, value, 0.0001f) }
    }

    @Test
    fun integerValuesCannotBeFractionalAndSliderValuesSnapToIntegers() {
        var value by mutableFloatStateOf(1f)
        compose.setContent {
            MaterialTheme { BrushParameterSlider("Count", value, { value = it }, 1f..5f, value.toInt().toString(), isInteger = true) }
        }
        compose.onNodeWithContentDescription("Exact Count").performClick()
        compose.onNodeWithText("Value").performTextReplacement("2.5")
        compose.onNodeWithText("Set value").assertIsNotEnabled()
        compose.onNodeWithText("Value").performTextReplacement("3")
        compose.onNodeWithText("Set value").performClick()
        compose.runOnIdle { assertEquals(3f, value, 0f) }
        compose.onNodeWithContentDescription("Count").performSemanticsAction(SemanticsActions.SetProgress) { it(3.8f) }
        compose.runOnIdle { assertEquals(4f, value, 0f) }
    }

    @Test
    fun practiceStrokesSurviveTabChangesButNeverApplyBrushSettingsByThemselves() {
        var applied: BrushParams? = null
        compose.setContent {
            MaterialTheme { BrushStudioDialog(BrushParams(), { applied = it }, {}) }
        }
        compose.onNodeWithText("Drawing pad").performClick()
        compose.onNodeWithTag("brush-practice-pad").performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.5f), Offset(width * 0.8f, height * 0.5f))
        }
        assertStrokes(1)
        compose.onNodeWithText("Settings").performClick()
        compose
            .onNodeWithContentDescription("Spacing")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }
        compose.onNodeWithText("Drawing pad").performClick()
        assertStrokes(1)
        compose.waitUntil(10_000) {
            val pixels = compose.onNodeWithTag("brush-practice-pad").captureToImage().toPixelMap()
            (pixels.width / 3 until pixels.width * 2 / 3).any { x ->
                pixels[x, pixels.height / 2].toArgb() != BrushPractice.PAPER
            }
        }
        TestEvidence.screenshot("studio-brush-pad.png")
        compose.runOnIdle { assertNull(applied) }
        compose.onNodeWithText("Clear pad").performClick()
        assertStrokes(0)
        compose.onNodeWithText("Use brush").performClick()
        compose.runOnIdle { assertEquals(0.4f, requireNotNull(applied).spacing, 0.001f) }
    }

    @Test
    fun cancelledPracticeGesturesDoNotBecomeStrokesAndHistoryIsBounded() {
        var strokes by mutableStateOf<List<Stroke>>(emptyList())
        compose.setContent {
            MaterialTheme { BrushPracticePad(BrushParams(), strokes, { strokes = it }, Modifier.widthIn(max = 320.dp).height(400.dp)) }
        }
        val pad = compose.onNodeWithTag("brush-practice-pad")
        pad.performTouchInput {
            down(center)
            moveTo(Offset(width * 0.8f, height * 0.5f))
            cancel()
        }
        assertStrokes(0)
        repeat(10) { pad.performTouchInput { click(center) } }
        assertStrokes(8)
        compose.runOnIdle { assertTrue(strokes.all { it.points.size <= 128 }) }
    }

    private fun assertStrokes(count: Int) {
        compose
            .onNodeWithTag("brush-practice-pad")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$count test strokes; not part of artwork"))
    }
}
