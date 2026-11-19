package com.artflow.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.presentation.ui.components.brush.BrushLibraryControls
import com.artflow.studio.presentation.ui.components.brush.SavedBrushToolbar
import com.artflow.studio.presentation.ui.viewmodel.BrushLibraryViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedBrushToolbarLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactToolbarKeepsLabelsAndTouchTargetsReadableAtLargeFontScales() {
        var fontScale by mutableFloatStateOf(1f)
        val controls =
            BrushLibraryControls(
                state = BrushLibraryViewModel.State(loading = false),
                saveCopy = { _, _ -> },
                rename = { _, _ -> },
                delete = {},
                retry = {},
            )
        compose.setContent {
            // A 320 dp screen leaves 296 dp after the production dialog's outer padding.
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(296.dp).testTag("compact-brush-toolbar")) {
                        SavedBrushToolbar(BrushParams(), controls)
                    }
                }
            }
        }

        val failures = mutableListOf<AssertionError>()
        listOf(1f, 1.3f, 2f).forEach { scale ->
            compose.runOnIdle { fontScale = scale }
            val labels = listOf("0 saved brushes", "Save a copy", "Share", "Import")
            val measurements = labels.associateWith(::measureLabel)
            val host = compose.onNodeWithTag("compact-brush-toolbar").fetchSemanticsNode().boundsInRoot
            // Preserve every scale before checking it; a failed first label must not hide the later evidence.
            captureMeasurements(scale, host, measurements)
            labels.forEach { label ->
                val measurement = measurements.getValue(label)
                recordFailure(failures) { compose.onNodeWithText(label).assertIsDisplayed() }
                recordFailure(failures) {
                    assertEquals("$label wraps at font scale $scale\n${measurement.details()}", 1, measurement.layout.lineCount)
                }
                recordFailure(failures) {
                    assertFalse(
                        "$label overflows at font scale $scale\n${measurement.details()}",
                        measurement.layout.hasVisualOverflow,
                    )
                }
            }
            val targets =
                labels.drop(1).associateWith { label ->
                    recordFailure(failures) { compose.onNodeWithText(label).assertHasClickAction().assertIsEnabled() }
                    // A clickable node's visual bounds can be smaller than its actual minimum touch region.
                    // Keep the 48 dp requirement on touchBoundsInRoot, and retain visual bounds in the evidence.
                    measurements.getValue(label).touchBounds
                }
            targets.forEach { (label, bounds) ->
                // LocalDensity is explicitly 1 px/dp, so these are the actual minimum touch sizes.
                recordFailure(failures) {
                    assertTrue("$label has a small touch target at font scale $scale: $bounds", bounds.width >= 48f && bounds.height >= 48f)
                }
                recordFailure(failures) {
                    assertTrue(
                        "$label extends outside the compact toolbar at font scale $scale: $bounds in $host",
                        bounds.left >= host.left && bounds.top >= host.top && bounds.right <= host.right && bounds.bottom <= host.bottom,
                    )
                }
            }
            val entries = targets.entries.toList()
            entries.forEachIndexed { index, first ->
                entries.drop(index + 1).forEach { second ->
                    recordFailure(failures) {
                        assertFalse("${first.key} and ${second.key} overlap at font scale $scale", first.value.overlaps(second.value))
                    }
                }
            }
        }
        // Keep the original failure and attach every later assertion; collecting evidence never turns a failure into a pass.
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }

        compose.runOnIdle { fontScale = 1f }
        compose.onNodeWithText("Save a copy").performClick()
        compose.onNodeWithText("Save brush copy").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithText("ArtFlow (.artbrush)").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose
            .onNodeWithText("Import")
            .assertHasClickAction()
            .assertIsEnabled()
            .assertIsDisplayed()
    }

    private fun measureLabel(label: String): LabelMeasurement {
        val node = compose.onNodeWithText(label)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val semantics = node.fetchSemanticsNode()
        return LabelMeasurement(layouts.single(), semantics.boundsInRoot, semantics.touchBoundsInRoot)
    }

    private fun captureMeasurements(
        scale: Float,
        host: Rect,
        measurements: Map<String, LabelMeasurement>,
    ) {
        val suffix = scale.toString().replace('.', '-')
        val directory = TestEvidence.directory()
        directory.resolve("saved-brush-toolbar-layout-$suffix.txt").writeText(
            buildString {
                appendLine("fontScale=$scale hostBounds=$host")
                measurements.forEach { (label, measurement) ->
                    appendLine("label=$label")
                    appendLine(measurement.details())
                }
            },
        )
        directory.resolve("saved-brush-toolbar-tree-$suffix.txt").writeText(compose.onRoot().printToString())
        val screenshot =
            when (scale) {
                1f -> "saved-brush-toolbar-compact.png"
                2f -> "saved-brush-toolbar-large-font.png"
                else -> "saved-brush-toolbar-font-$suffix.png"
            }
        TestEvidence.screenshot(screenshot)
    }

    private fun recordFailure(
        failures: MutableList<AssertionError>,
        assertion: () -> Unit,
    ) {
        try {
            assertion()
        } catch (failure: AssertionError) {
            failures += failure
        }
    }

    private data class LabelMeasurement(
        val layout: TextLayoutResult,
        val visualBounds: Rect,
        val touchBounds: Rect,
    ) {
        fun details(): String =
            buildString {
                appendLine("visualBounds=$visualBounds touchBounds=$touchBounds")
                appendLine(
                    "size=${layout.size} paragraphWidth=${layout.multiParagraph.width} paragraphHeight=${layout.multiParagraph.height}",
                )
                appendLine("overflowWidth=${layout.didOverflowWidth} overflowHeight=${layout.didOverflowHeight}")
                appendLine("hasVisualOverflow=${layout.hasVisualOverflow} lineCount=${layout.lineCount}")
                appendLine("density=${layout.layoutInput.density.density} fontScale=${layout.layoutInput.density.fontScale}")
                appendLine("fontSize=${layout.layoutInput.style.fontSize} lineHeight=${layout.layoutInput.style.lineHeight}")
                appendLine("constraints=${layout.layoutInput.constraints} textLength=${layout.layoutInput.text.length}")
                repeat(layout.lineCount) { line ->
                    appendLine(
                        "line=$line left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} " +
                            "top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)} " +
                            "baseline=${layout.getLineBaseline(line)} start=${layout.getLineStart(line)} " +
                            "visibleEnd=${layout.getLineEnd(line, visibleEnd = true)} ellipsized=${layout.isLineEllipsized(line)}",
                    )
                }
            }
    }
}
