package com.artflow.studio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.canvas.CanvasRepository.LayerParameter
import com.artflow.studio.presentation.ui.components.editor.LayerMaskActions
import com.artflow.studio.presentation.ui.components.editor.LayerOpacityOverlay
import com.artflow.studio.presentation.ui.components.editor.LayerParameterGestureActions
import com.artflow.studio.presentation.ui.components.editor.LayerRowActions
import com.artflow.studio.presentation.ui.components.editor.LayerStackActions
import com.artflow.studio.presentation.ui.components.editor.LayersSheet
import com.artflow.studio.presentation.ui.viewmodel.LayerParameterController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real LayersSheet and overlay pointer callbacks reach the production controller, repository and storage. */
@RunWith(AndroidJUnit4::class)
class LayerParameterGesturesUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private lateinit var scope: CoroutineScope
    private lateinit var controller: LayerParameterController
    private lateinit var gestures: LayerParameterGestureActions
    private var layers by mutableStateOf(emptyList<Layer>())
    private var ordinaryChanges = 0
    private var layerId = 0L

    @Before
    fun setUp() {
        storage = ProjectStorage(ApplicationProvider.getApplicationContext())
        storage.deleteProjectFiles(PROJECT_ID)
        repository = CanvasRepositoryImpl(storage)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(PROJECT_ID, 16, 16, 72)
            layerId = repository.getActiveLayerId()
            assertTrue(repository.addLayerMask(layerId))
            refresh()
            controller = LayerParameterController(repository, ::refresh, { throw it })
            gestures = LayerParameterGestureActions(controller::begin, controller::update, controller::end)
        }
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.Main) {
            controller.cancel()
            scope.cancel()
            repository.dispose()
            storage.deleteProjectFiles(PROJECT_ID)
        }
    }

    @Test
    fun opacityDensityAndFeatherEachKeepTwoFastDragsAsTwoUndoSteps() {
        showLayers()
        for ((description, parameter) in controls) {
            val original = value(parameter)
            val depth = repository.undoDepth
            drag(description, 0.9f, 0.6f)
            val first = value(parameter)
            assertTrue(original != first)
            assertEquals(depth + 1, repository.undoDepth)
            drag(description, 0.5f, 0.25f)
            assertEquals(depth + 2, repository.undoDepth)
            compose.runOnIdle {
                assertTrue(repository.undo())
                assertEquals(first, value(parameter), 0f)
                assertTrue(repository.undo())
                assertEquals(original, value(parameter), 0f)
                refresh()
            }
        }
        compose.runOnIdle { assertEquals(0, ordinaryChanges) }
    }

    @Test
    fun cancellingTheMaskDragRetainsItsValueAndSeparatesTheNextDrag() {
        showLayers()
        val depth = repository.undoDepth
        drag("Density", 0.9f, 0.6f, cancel = true)
        val cancelledValue = value(LayerParameter.MASK_DENSITY)
        assertTrue(cancelledValue < 1f)
        assertEquals(depth + 1, repository.undoDepth)
        drag("Density", 0.5f, 0.25f)
        compose.runOnIdle {
            assertEquals(0, ordinaryChanges)
            assertEquals(depth + 2, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(cancelledValue, value(LayerParameter.MASK_DENSITY), 0f)
        }
    }

    @Test
    fun sliderTapValuesArrivingAfterPointerUpStillUseSeparateGestures() {
        showLayers()
        val depth = repository.undoDepth
        tap("Layer opacity", 0.7f)
        val first = value(LayerParameter.OPACITY)
        tap("Layer opacity", 0.3f)
        compose.runOnIdle {
            assertEquals(0, ordinaryChanges)
            assertEquals(depth + 2, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(first, value(LayerParameter.OPACITY), 0f)
        }
    }

    @Test
    fun opacityOverlaySeparatesDragsAndSealsCancellation() {
        runBlocking(Dispatchers.Main) {
            repository.setLayerOpacity(layerId, 0.5f)
            refresh()
        }
        compose.setContent {
            MaterialTheme {
                LayerOpacityOverlay(
                    opacity = layers.single().opacity,
                    onChange = { ordinary { repository.setLayerOpacity(layerId, it) } },
                    onDone = {},
                    layerId = layerId,
                    parameterGestures = gestures,
                )
            }
        }
        val depth = repository.undoDepth
        drag("Slide to set layer opacity", 0.3f, 0.5f)
        val first = value(LayerParameter.OPACITY)
        drag("Slide to set layer opacity", 0.6f, 0.4f, cancel = true)
        compose.runOnIdle {
            assertEquals(0, ordinaryChanges)
            assertEquals(depth + 2, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(first, value(LayerParameter.OPACITY), 0f)
        }
    }

    @Test
    fun finishedSliderValuesSaveAndReopenWhileTheOldTokenIsRejected() {
        showLayers()
        controls.keys.forEach { drag(it, 0.8f, 0.4f) }
        val expected = repository.getAllLayers().single()
        runBlocking(Dispatchers.Main) {
            assertNotNull(repository.saveCanvas(PROJECT_ID))
            val stale = requireNotNull(controller.begin(layerId, LayerParameter.MASK_DENSITY))
            assertNotNull(repository.loadCanvas(PROJECT_ID))
            assertFalse(repository.updateLayerParameterGesture(stale, 0.1f))
            val reopened = requireNotNull(repository.getActiveLayer())
            assertEquals(expected.opacity, reopened.opacity, 0f)
            assertEquals(expected.maskDensity, reopened.maskDensity, 0f)
            assertEquals(expected.maskFeather, reopened.maskFeather, 0f)
            assertTrue(reopened.hasMask())
            assertEquals(0, repository.undoDepth)
            refresh()
        }
    }

    private fun drag(
        description: String,
        from: Float,
        to: Float,
        cancel: Boolean = false,
    ) {
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).assertIsDisplayed().performTouchInput {
            down(Offset(width * from, centerY))
            moveTo(Offset(width * (from + to) / 2f, centerY))
            moveTo(Offset(width * to, centerY))
            if (cancel) this.cancel() else up()
        }
        compose.waitForIdle()
    }

    private fun tap(
        description: String,
        fraction: Float,
    ) {
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).performTouchInput {
            down(Offset(width * fraction, centerY))
            up()
        }
        compose.waitForIdle()
    }

    private fun value(parameter: LayerParameter): Float {
        val layer = requireNotNull(repository.getActiveLayer())
        return when (parameter) {
            LayerParameter.OPACITY -> layer.opacity
            LayerParameter.MASK_DENSITY -> layer.maskDensity
            LayerParameter.MASK_FEATHER -> layer.maskFeather
        }
    }

    private fun refresh() {
        layers = repository.getAllLayers()
    }

    private fun ordinary(action: suspend () -> Unit) {
        ordinaryChanges++
        scope.launch {
            action()
            refresh()
        }
    }

    private fun showLayers() {
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    LayersSheet(
                        layers = layers,
                        activeLayerId = layerId,
                        hasSelection = false,
                        rowActions =
                            LayerRowActions(
                                onSelect = {},
                                onVisibility = { _, _ -> },
                                onOpacity = { id, value -> ordinary { repository.setLayerOpacity(id, value) } },
                                onName = { _, _ -> },
                                onLock = { _, _ -> },
                                onAlphaLock = { _, _ -> },
                                onClipping = { _, _ -> },
                                onBlendMode = { _, _ -> },
                                onDuplicate = {},
                                onDelete = {},
                                onMergeDown = {},
                                parameterGestures = gestures,
                            ),
                        stackActions =
                            LayerStackActions(
                                onAddLayer = {},
                                onReorder = { _, _ -> },
                                onFlatten = {},
                                onMergeVisible = {},
                                onAddAdjustment = {},
                                onAddFilter = {},
                                onAdjustmentParameter = { _, _, _ -> },
                                onFilterAmount = { _, _ -> },
                            ),
                        maskActions =
                            LayerMaskActions(
                                onAddMask = {},
                                onRemoveMask = {},
                                onInvertMask = {},
                                onMaskEnabled = {},
                                onMaskDensity = { value -> ordinary { repository.setLayerMaskDensity(layerId, value) } },
                                onMaskFeather = { value -> ordinary { repository.setLayerMaskFeather(layerId, value) } },
                                onPaintMask = {},
                                parameterGestures = gestures,
                            ),
                    )
                }
            }
        }
    }

    companion object {
        private const val PROJECT_ID = 132L
        private val controls =
            linkedMapOf(
                "Layer opacity" to LayerParameter.OPACITY,
                "Density" to LayerParameter.MASK_DENSITY,
                "Feather" to LayerParameter.MASK_FEATHER,
            )
    }
}
