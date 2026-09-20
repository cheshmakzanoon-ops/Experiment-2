package com.artflow.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.presentation.ui.components.brush.BrushStudioTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrushStudioTabsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun labelsFitAtDefaultAndLargeFontScales() = verifyScales(1f, LayoutDirection.Ltr, listOf(1f, 1.3f, 2f))

    @Test
    fun labelsFitAtFractionalDisplayDensity() = verifyScales(2.625f, LayoutDirection.Ltr, listOf(1f, 1.3f))

    @Test
    fun labelsAndSelectionSurviveRightToLeftAndFontChanges() = verifyScales(1f, LayoutDirection.Rtl, listOf(1f, 2f, 1f))

    private fun verifyScales(
        density: Float,
        direction: LayoutDirection,
        scales: List<Float>,
    ) {
        var fontScale by mutableFloatStateOf(scales.first())
        var selected by mutableIntStateOf(0)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                MaterialTheme {
                    Box(Modifier.width(280.dp)) {
                        BrushStudioTabs(selected, { selected = it })
                    }
                }
            }
        }
        scales.forEach { scale ->
            compose.runOnIdle { fontScale = scale }
            listOf("Library", "Settings", "Drawing pad").forEachIndexed { index, label ->
                val node = compose.onNodeWithText(label)
                node.performScrollTo().assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertFalse(
                    "$label overflows at density=$density fontScale=$scale: ${layout.size}, paragraph=${layout.multiParagraph.width}",
                    layout.hasVisualOverflow,
                )
                node.performClick().assertIsSelected()
                compose.runOnIdle { assertEquals(index, selected) }
            }
        }
        TestEvidence.screenshot(if (direction == LayoutDirection.Rtl) "studio-tabs-rtl.png" else "studio-tabs-scaled.png")
    }
}
