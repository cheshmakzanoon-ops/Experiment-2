package com.artflow.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

        listOf(1f, 1.3f, 2f).forEach { scale ->
            compose.runOnIdle { fontScale = scale }
            val labels = listOf("0 saved brushes", "Save a copy", "Share", "Import")
            labels.forEach { label ->
                val node = compose.onNodeWithText(label).assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals("$label wraps at font scale $scale", 1, layout.lineCount)
                assertFalse("$label overflows at font scale $scale", layout.hasVisualOverflow)
            }
            val host = compose.onNodeWithTag("compact-brush-toolbar").fetchSemanticsNode().boundsInRoot
            val targets =
                labels.drop(1).associateWith { label ->
                    compose
                        .onNodeWithText(label)
                        .assertHasClickAction()
                        .assertIsEnabled()
                        .fetchSemanticsNode()
                        .boundsInRoot
                }
            targets.forEach { (label, bounds) ->
                // LocalDensity is explicitly 1 px/dp, so these are the actual minimum touch sizes.
                assertTrue("$label has a small touch target at font scale $scale: $bounds", bounds.width >= 48f && bounds.height >= 48f)
                assertTrue(
                    "$label extends outside the compact toolbar at font scale $scale: $bounds in $host",
                    bounds.left >= host.left && bounds.top >= host.top && bounds.right <= host.right && bounds.bottom <= host.bottom,
                )
            }
            val entries = targets.entries.toList()
            entries.forEachIndexed { index, first ->
                entries.drop(index + 1).forEach { second ->
                    assertFalse("${first.key} and ${second.key} overlap at font scale $scale", first.value.overlaps(second.value))
                }
            }
            if (scale == 1f) TestEvidence.screenshot("saved-brush-toolbar-compact.png")
            if (scale == 2f) TestEvidence.screenshot("saved-brush-toolbar-large-font.png")
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
}
