package com.artflow.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.presentation.ui.components.brush.BrushLibraryControls
import com.artflow.studio.presentation.ui.components.brush.BrushStudioContent
import com.artflow.studio.presentation.ui.viewmodel.BrushLibraryViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedBrushUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun savingUsesAnExplicitSnapshotAndDoesNotApplyTheBrush() {
        var state by mutableStateOf(BrushLibraryViewModel.State(loading = false))
        var saved: Pair<String, BrushParams>? = null
        var applied: BrushParams? = null
        val parameters = BrushParams(size = 37f, wetMix = 0.35f)
        compose.setContent {
            MaterialTheme {
                BrushStudioContent(
                    initial = parameters,
                    onApply = { applied = it },
                    onDismiss = {},
                    library =
                        BrushLibraryControls(
                            state = state,
                            saveCopy = { name, params ->
                                saved = name to params
                                state = state.copy(busy = true)
                            },
                            rename = { _, _ -> },
                            delete = {},
                            retry = {},
                        ),
                )
            }
        }
        compose.onNodeWithText("Save a copy").performClick()
        compose.onNodeWithText("Save copy").assertIsNotEnabled()
        compose.onNodeWithText("Brush name").performTextInput(" My ink ")
        compose.onNodeWithText("Save copy").performClick()
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("My ink" to parameters, saved)
            assertNull(applied)
            state = state.copy(busy = false, brushes = listOf(SavedBrush("a", "My ink", parameters)), revision = 1L)
        }
        compose.onNodeWithText("Save brush copy").assertDoesNotExist()
        compose.onNodeWithText("Saved").performScrollTo().performClick()
        compose.onNodeWithText("My ink").assertIsDisplayed()
        TestEvidence.screenshot("studio-saved-brushes.png")
        compose.onNodeWithText("My ink").performClick()
        compose.onNodeWithText("Use brush").performClick()
        compose.runOnIdle { assertEquals(parameters, applied) }
    }

    @Test
    fun savedRowsCanBeRenamedAndDeleteRequiresConfirmation() {
        val original = SavedBrush("a", "My pencil", BrushParams(size = 3f))
        var state by mutableStateOf(BrushLibraryViewModel.State(brushes = listOf(original), loading = false))
        var deletes = 0
        compose.setContent {
            MaterialTheme {
                BrushStudioContent(
                    initial = BrushParams(),
                    onApply = {},
                    onDismiss = {},
                    library =
                        BrushLibraryControls(
                            state = state,
                            saveCopy = { _, _ -> },
                            rename = { id, name ->
                                state =
                                    state.copy(
                                        brushes = state.brushes.map { if (it.id == id) it.copy(name = name) else it },
                                        revision = 1L,
                                    )
                            },
                            delete = {
                                deletes++
                                state = state.copy(brushes = emptyList(), revision = 2L)
                            },
                            retry = {},
                        ),
                )
            }
        }
        compose.onNodeWithContentDescription("Saved brush actions My pencil").performClick()
        compose.onNodeWithText("Rename saved brush").performClick()
        compose.onNodeWithText("Brush name").performTextReplacement("Fine graphite")
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithContentDescription("Saved brush actions Fine graphite").performClick()
        compose.onNodeWithText("Delete saved brush").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithContentDescription("Saved brush actions Fine graphite").performClick()
        compose.onNodeWithText("Delete saved brush").performClick()
        compose.onNodeWithText("Delete copy").performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
        compose.onNodeWithText("Fine graphite").assertDoesNotExist()
    }

    @Test
    fun failedSaveRetainsTheNameAndDoesNotPretendItWasSaved() {
        var state by mutableStateOf(BrushLibraryViewModel.State(loading = false))
        compose.setContent {
            MaterialTheme {
                BrushStudioContent(
                    initial = BrushParams(),
                    onApply = {},
                    onDismiss = {},
                    library =
                        BrushLibraryControls(
                            state = state,
                            saveCopy = { _, _ -> state = state.copy(error = "Storage unavailable") },
                            rename = { _, _ -> },
                            delete = {},
                            retry = {},
                        ),
                )
            }
        }
        compose.onNodeWithText("Save a copy").performClick()
        compose.onNodeWithText("Brush name").performTextInput("Keep my name")
        compose.onNodeWithText("Save copy").performClick()
        compose.onNodeWithText("Save brush copy").assertIsDisplayed()
        compose.onNodeWithText("Keep my name").assertIsDisplayed()
        compose.onAllNodesWithText("Storage unavailable").onLast().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Save a copy").assertIsNotEnabled()
        compose.onNodeWithText("Retry saved library").assertExists()
    }
}
