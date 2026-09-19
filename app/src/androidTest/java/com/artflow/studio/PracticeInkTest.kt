package com.artflow.studio

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.render.BrushPractice
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.presentation.ui.components.brush.BrushPracticePad
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PracticeInkTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun colourChangesAffectOnlyNewPracticeStrokesAndClearKeepsTheChosenInk() {
        var strokes by mutableStateOf<List<Stroke>>(emptyList())
        var ink by mutableIntStateOf(BrushPractice.INK)
        compose.setContent {
            MaterialTheme {
                BrushPracticePad(
                    BrushParams(wetMix = 0.5f),
                    strokes,
                    { strokes = it },
                    Modifier.widthIn(max = 320.dp).height(400.dp),
                    ink,
                    { ink = it },
                )
            }
        }
        val pad = compose.onNodeWithTag("brush-practice-pad")
        pad.performTouchInput { click(center) }
        compose.onNodeWithContentDescription("Practice ink").performClick()
        compose.onNodeWithText("Ocean").performClick()
        pad.performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(2, strokes.size)
            assertEquals(BrushPractice.INK, strokes.first().color)
            assertEquals(0xFF2F5E93.toInt(), strokes.last().color)
        }
        compose.onNodeWithText("Clear pad").performClick()
        compose.runOnIdle { assertTrue(strokes.isEmpty()) }
        compose
            .onNodeWithContentDescription("Practice ink")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ocean"))
        pad.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0xFF2F5E93.toInt(), strokes.single().color) }
    }

    @Test
    fun wetPracticeActuallyChangesDisplayedPixelsWithoutChangingRecordedInk() {
        var strokes by mutableStateOf<List<Stroke>>(emptyList())
        var ink by mutableIntStateOf(BrushPractice.INK)
        var params by mutableStateOf(BrushParams(size = 40f, pressureToSize = 0f, pressureToOpacity = 0f))
        compose.setContent {
            ArtFlowTheme {
                BrushPracticePad(params, strokes, { strokes = it }, Modifier.height(400.dp), ink, { ink = it })
            }
        }
        val pad = compose.onNodeWithTag("brush-practice-pad")
        pad.performTouchInput { swipe(Offset(width * 0.1f, height * 0.5f), Offset(width * 0.9f, height * 0.5f)) }
        compose.onNodeWithContentDescription("Practice ink").performClick()
        compose.onNodeWithText("Ocean").performClick()
        pad.performTouchInput { swipe(Offset(width * 0.1f, height * 0.5f), Offset(width * 0.9f, height * 0.5f)) }
        val ocean = 0xFF2F5E93.toInt()
        compose.waitUntil(10_000) { TestEvidence.centreRowColors("Brush practice pad")?.all { it == ocean } == true }
        val before = strokes
        compose.runOnIdle { params = params.copy(wetMix = 0.6f) }
        compose.waitUntil(10_000) {
            TestEvidence.centreRowColors("Brush practice pad")?.all { it != ocean && it != BrushPractice.PAPER } == true
        }
        compose.runOnIdle { assertEquals(before, strokes) }
        TestEvidence.screenshot("studio-wet-paint.png")
    }
}
