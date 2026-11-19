package com.artflow.studio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.presentation.ui.components.editor.LayerMaskActions
import com.artflow.studio.presentation.ui.components.editor.LayerOptionActions
import com.artflow.studio.presentation.ui.components.editor.LayerRowActions
import com.artflow.studio.presentation.ui.components.editor.LayerStackActions
import com.artflow.studio.presentation.ui.components.editor.LayersSheet
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import com.artflow.studio.presentation.ui.viewmodel.LayerBatchController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production layer controls call the real batch controller and document, including undo. */
@RunWith(AndroidJUnit4::class)
class LinkedLayersUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var repository: CanvasRepositoryImpl
    private lateinit var batch: LayerBatchController
    private var first = 0L
    private var second = 0L

    @Before
    fun setUp() {
        repository = CanvasRepositoryImpl(ProjectStorage(ApplicationProvider.getApplicationContext()))
        runBlocking(Dispatchers.Main) {
            repository.createCanvas(16, 16, 72)
            first = repository.getActiveLayerId()
            repository.setLayerName(first, "Ink")
            second = repository.addLayer("Colour").id
        }
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.Main) { repository.dispose() }
    }

    @Test
    fun selectedLayersLinkAndTheActiveLayerMenuUnlinksThem() {
        showLayers()
        val depth = repository.undoDepth
        compose.onNodeWithText("Link selected").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { repository.getAllLayers().all { it.linkGroupId != null } }
        compose.runOnIdle {
            val layers = repository.getAllLayers()
            assertNotNull(layers.first().linkGroupId)
            assertEquals(layers.first().linkGroupId, layers.last().linkGroupId)
            assertTrue(batch.picked.value.isEmpty())
            assertEquals(depth + 1, repository.undoDepth)
        }
        compose.onNodeWithText("Colour").performClick()
        compose.onNodeWithText("Unlink layer").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { repository.getAllLayers().all { it.linkGroupId == null } }
        assertEquals(depth + 2, repository.undoDepth)
        runBlocking(Dispatchers.Main) {
            assertTrue(repository.undo())
            assertTrue(repository.getAllLayers().all { it.linkGroupId != null })
        }
    }

    @Test
    fun linkingIsDisabledWhenOnePickedLayerIsProtected() {
        runBlocking(Dispatchers.Main) { repository.setLayerLock(first, true) }
        val depth = repository.undoDepth
        showLayers()
        compose.onNodeWithText("Link selected").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(setOf(first), batch.picked.value)
            assertTrue(repository.getAllLayers().all { it.linkGroupId == null })
            assertEquals(depth, repository.undoDepth)
        }
    }

    private fun showLayers() {
        compose.setContent {
            var layers by remember { mutableStateOf(repository.getAllLayers()) }
            val scope = rememberCoroutineScope()
            batch = remember {
                LayerBatchController(repository) { action ->
                    scope.launch {
                        action()
                        layers = repository.getAllLayers()
                    }
                }.apply { pick(setOf(first)) }
            }
            val picked by batch.picked.collectAsState()
            ArtFlowTheme(AppSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    LayersSheet(
                        layers = layers,
                        activeLayerId = second,
                        rowActions = rowActions(),
                        stackActions = stackActions(),
                        maskActions = maskActions(),
                        hasSelection = false,
                        optionActions = optionActions(),
                        picked = picked,
                        onPicked = batch::pick,
                    )
                }
            }
        }
    }

    private fun rowActions() =
        LayerRowActions(
            onSelect = {},
            onVisibility = { _, _ -> },
            onOpacity = { _, _ -> },
            onName = { _, _ -> },
            onLock = { _, _ -> },
            onAlphaLock = { _, _ -> },
            onClipping = { _, _ -> },
            onBlendMode = { _, _ -> },
            onDuplicate = {},
            onDelete = {},
            onMergeDown = {},
        )

    private fun stackActions() =
        LayerStackActions(
            onAddLayer = {},
            onReorder = { _, _ -> },
            onFlatten = {},
            onMergeVisible = {},
            onAddAdjustment = {},
            onAddFilter = {},
            onAdjustmentParameter = { _, _, _ -> },
            onFilterAmount = { _, _ -> },
            onLinkLayers = batch::link,
        )

    private fun optionActions() =
        LayerOptionActions(
            onSelectContents = {},
            onCopy = {},
            onFill = {},
            onClear = {},
            onInvert = {},
            onReference = { _, _ -> },
            onMask = {},
            onCombineDown = {},
            onUnlink = batch::unlink,
        )

    private fun maskActions() =
        LayerMaskActions(
            onAddMask = {},
            onRemoveMask = {},
            onInvertMask = {},
            onMaskEnabled = {},
            onMaskDensity = {},
            onMaskFeather = {},
            onPaintMask = {},
        )
}
