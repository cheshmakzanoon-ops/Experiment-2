package com.artflow.studio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.model.layer.LayerBlendContract
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.presentation.ui.components.editor.LayerMaskActions
import com.artflow.studio.presentation.ui.components.editor.LayerRowActions
import com.artflow.studio.presentation.ui.components.editor.LayerStackActions
import com.artflow.studio.presentation.ui.components.editor.LayersSheet
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Component coverage of the production LayersSheet -> layer row -> blend dialog route.
 * Repository contract replacement, undo and disk persistence have a separate real-storage test.
 */
@RunWith(AndroidJUnit4::class)
class ProcreateImportedBlendUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun layersDialogExplainsImportedDarkenAndOnlyAnExplicitModeChoiceReplacesIt() {
        val imported =
            Layer(
                id = 17L,
                name = "Imported ink",
                index = 0,
                blendMode = BlendMode.DARKEN,
                blendContract = LayerBlendContract.PROCREATE_DARKEN_19,
            )
        var layer by mutableStateOf(imported)
        val requests = mutableListOf<Pair<Long, BlendMode>>()
        val rowActions =
            LayerRowActions(
                onSelect = {},
                onVisibility = { _, _ -> },
                onOpacity = { _, _ -> },
                onName = { _, _ -> },
                onLock = { _, _ -> },
                onAlphaLock = { _, _ -> },
                onClipping = { _, _ -> },
                onBlendMode = { id, mode -> requests += id to mode },
                onDuplicate = {},
                onDelete = {},
                onMergeDown = {},
            )
        compose.setContent {
            ArtFlowTheme(AppSettings(themeMode = ThemeMode.LIGHT)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LayersSheet(
                        layers = listOf(layer),
                        activeLayerId = layer.id,
                        rowActions = rowActions,
                        stackActions = stackActions(),
                        maskActions = maskActions(),
                        hasSelection = false,
                    )
                }
            }
        }

        openImportedDialog()
        TestEvidence.screenshot("layers-imported-darken.png")
        compose.onNodeWithText("Done").performClick()
        assertDismissed()
        compose.runOnIdle {
            assertTrue(requests.isEmpty())
            assertEquals(imported, layer)
        }

        openImportedDialog()
        Espresso.pressBack()
        assertDismissed()
        compose.runOnIdle {
            assertTrue(requests.isEmpty())
            assertEquals(imported, layer)
        }

        openImportedDialog()
        compose.onNodeWithText("Darken").performClick()
        assertDismissed()
        compose.runOnIdle {
            assertEquals(listOf(imported.id to BlendMode.DARKEN), requests)
            // The host supplies the repository's result; this case tests the component boundary.
            layer = imported.copy(blendContract = LayerBlendContract.STANDARD)
        }
        compose.onNodeWithText("D").performClick()
        compose.onNodeWithText("Imported Darken").assertDoesNotExist()
        compose.onNodeWithText(NOTICE).assertDoesNotExist()
        compose.onNodeWithText("Darken").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Normal").assertIsNotSelected()
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        compose.onNodeWithText("Done").performClick()
        assertDismissed()
        compose.runOnIdle {
            assertEquals(listOf(imported.id to BlendMode.DARKEN), requests)
            assertEquals(imported.copy(blendContract = LayerBlendContract.STANDARD), layer)
        }
    }

    private fun openImportedDialog() {
        compose.onNodeWithText("D").assertIsDisplayed().performClick()
        compose.onNodeWithText("Imported Darken").assertIsDisplayed()
        compose.onNodeWithText(NOTICE).assertIsDisplayed()
        compose.onNodeWithText("Darken").assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithText("Normal").assertIsNotSelected()
        compose.onAllNodes(isSelected()).assertCountEquals(0)
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    private fun assertDismissed() {
        compose.onNodeWithText("Imported Darken").assertDoesNotExist()
        compose.onNodeWithText("Blend Mode").assertDoesNotExist()
        compose.onNodeWithText("Imported ink").assertIsDisplayed()
    }

    private fun stackActions(): LayerStackActions =
        LayerStackActions(
            onAddLayer = {},
            onReorder = { _, _ -> },
            onFlatten = {},
            onMergeVisible = {},
            onAddAdjustment = {},
            onAddFilter = {},
            onAdjustmentParameter = { _, _, _ -> },
            onFilterAmount = { _, _ -> },
        )

    private fun maskActions(): LayerMaskActions =
        LayerMaskActions(
            onAddMask = {},
            onRemoveMask = {},
            onInvertMask = {},
            onMaskEnabled = {},
            onMaskDensity = {},
            onMaskFeather = {},
            onPaintMask = {},
        )

    private companion object {
        const val NOTICE = "Choosing a mode here replaces the imported Darken effect and may change how this layer looks."
    }
}
