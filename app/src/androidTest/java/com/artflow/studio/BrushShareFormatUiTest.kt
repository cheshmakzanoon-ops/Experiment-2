package com.artflow.studio

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.local.BrushShareFormat
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.presentation.ui.components.brush.BrushFileButtons
import com.artflow.studio.presentation.ui.components.brush.BrushLibraryControls
import com.artflow.studio.presentation.ui.components.brush.BrushShareDialog
import com.artflow.studio.presentation.ui.components.brush.BrushStudioContent
import com.artflow.studio.presentation.ui.viewmodel.BrushLibraryViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrushShareFormatUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun singleBrushDefaultsToArtflowAndCanExplicitlySelectProcreate() {
        var chosen: BrushShareFormat? = null
        compose.setContent {
            MaterialTheme {
                BrushShareDialog(isSet = false, brushes = listOf(brush("Wash", 20)), onDismiss = {}, onShare = { chosen = it })
            }
        }
        compose.onNodeWithText("ArtFlow (.artbrush)").assertIsSelected()
        compose.onNodeWithText("Count changes in Procreate format").assertDoesNotExist()
        compose.onNodeWithText("Procreate (.brushset)").assertDoesNotExist()
        compose.onNodeWithText("Procreate (.brush)").performClick().assertIsSelected()
        compose.onNodeWithText("Wash: Count 20 becomes 16.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose ArtFlow (.artbrush) to keep the current counts.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ArtFlow (.artbrush)").performScrollTo().performClick()
        compose.onNodeWithText("Count changes in Procreate format").assertDoesNotExist()
        compose.onNodeWithText("Wash: Count 20 becomes 16.").assertDoesNotExist()
        compose.onNodeWithText("Procreate (.brush)").performClick()
        compose.onNodeWithText("Wash: Count 20 becomes 16.").performScrollTo().assertIsDisplayed()
        TestEvidence.screenshot("brush-share-count-single.png")
        compose.onNodeWithText("Share").performClick()
        compose.runOnIdle { assertEquals(BrushShareFormat.PROCREATE_BRUSH, chosen) }
    }

    @Test
    fun setChoiceUsesTheBrushsetFormat() {
        var chosen: BrushShareFormat? = null
        compose.setContent {
            MaterialTheme {
                BrushShareDialog(
                    isSet = true,
                    brushes = listOf(brush("Wash", 20, 32), brush("Ink", 16), brush("Texture", 1, 17)),
                    onDismiss = {},
                    onShare = { chosen = it },
                )
            }
        }
        compose.onNodeWithText("ArtFlow (.artbrush)").assertIsSelected()
        compose.onNodeWithText("Procreate (.brush)").assertDoesNotExist()
        compose.onNodeWithText("Procreate (.brushset)").performClick()
        compose.onNodeWithText("Wash: Count 20 becomes 16.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Secondary brush in Wash: Count 32 becomes 16.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Secondary brush in Texture: Count 17 becomes 16.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ink: Count 16 becomes 16.").assertDoesNotExist()
        compose.onNodeWithText("Texture: Count 1 becomes 1.").assertDoesNotExist()
        TestEvidence.screenshot("brush-share-count-set.png")
        compose.onNodeWithText("Share").performClick()
        compose.runOnIdle { assertEquals(BrushShareFormat.PROCREATE_SET, chosen) }
    }

    @Test
    fun cancelDoesNotShareOrCommitAFormat() {
        var chosen: BrushShareFormat? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                BrushShareDialog(
                    isSet = false,
                    brushes = listOf(brush("Unchanged", 16, 8)),
                    onDismiss = { dismissed = true },
                    onShare = { chosen = it },
                )
            }
        }
        compose.onNodeWithText("Procreate (.brush)").performClick()
        compose.onNodeWithText("Count changes in Procreate format").assertDoesNotExist()
        compose.onNodeWithText("Choose ArtFlow (.artbrush) to keep the current counts.").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertNull(chosen)
        }
    }

    @Test
    fun theDraftShareButtonShowsCurrentPrimaryAndSecondaryCounts() {
        var parameters by mutableStateOf(brush("Draft", 20, 32).parameters)
        val failures = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Row {
                    BrushFileButtons(parameters, controls(BrushLibraryViewModel.State(loading = false)), true, failures::add)
                }
            }
        }
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithText("Procreate (.brush)").performClick()
        compose.onNodeWithText("Custom brush: Count 20 becomes 16.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Secondary brush in Custom brush: Count 32 becomes 16.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { parameters = parameters.copy(count = 16, dual = DualBrush(BrushParams(count = 20))) }
        compose.onNodeWithText("Custom brush: Count 20 becomes 16.").assertDoesNotExist()
        compose.onNodeWithText("Secondary brush in Custom brush: Count 32 becomes 16.").assertDoesNotExist()
        compose.onNodeWithText("Secondary brush in Custom brush: Count 20 becomes 16.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { parameters = parameters.copy(dual = DualBrush(BrushParams(count = 16))) }
        compose.onNodeWithText("Count changes in Procreate format").assertDoesNotExist()
        compose.onNodeWithText("Procreate (.brush)").assertIsSelected()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertTrue(failures.isEmpty())
            assertEquals(16, parameters.count)
            assertEquals(16, requireNotNull(parameters.dual).params.count)
        }
    }

    @Test
    fun theSavedSetShareButtonRefreshesTheNamedComponentConversions() {
        val original = brush("Saved wash", 20, 32)
        var state by
            mutableStateOf(
                BrushLibraryViewModel.State(
                    brushes = listOf(original),
                    loading = false,
                    sets = mapOf("My set" to listOf("saved-${original.id}")),
                ),
            )
        compose.setContent {
            MaterialTheme {
                BrushStudioContent(BrushParams(), {}, {}, library = controls(state))
            }
        }
        compose.onNodeWithText("My set").performScrollTo().performClick()
        compose.onNodeWithText("Share the set").assertIsDisplayed().performClick()
        compose.onNodeWithText("ArtFlow (.artbrush)").assertIsSelected()
        compose.onNodeWithText("Count changes in Procreate format").assertDoesNotExist()
        compose.onNodeWithText("Procreate (.brushset)").performClick()
        compose.onNodeWithText("Saved wash: Count 20 becomes 16.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Secondary brush in Saved wash: Count 32 becomes 16.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(brushes = listOf(original.copy(parameters = BrushParams(count = 16))), revision = state.revision + 1L)
        }
        compose.onNodeWithText("Count changes in Procreate format").assertDoesNotExist()
        compose.onNodeWithText("Procreate (.brushset)").assertIsSelected()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Share the set").assertIsDisplayed()
        compose.runOnIdle {
            val saved = state.brushes.single()
            assertEquals(16, saved.parameters.count)
        }
    }

    private fun brush(
        name: String,
        count: Int,
        secondaryCount: Int? = null,
    ): SavedBrush =
        SavedBrush(name, name, BrushParams(size = 37f, count = count, dual = secondaryCount?.let { DualBrush(BrushParams(count = it)) }))

    private fun controls(state: BrushLibraryViewModel.State): BrushLibraryControls =
        BrushLibraryControls(state, saveCopy = { _, _ -> }, rename = { _, _ -> }, delete = {}, retry = {})
}
