package com.artflow.studio

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.editor.StudioAction
import com.artflow.studio.presentation.ui.components.editor.StudioToolPalette
import com.artflow.studio.presentation.ui.screens.canvas.CanvasScreen
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/** Real editor plus actual tool palette; screenshots are execution evidence, not design mockups. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class StudioWorkspaceUiTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var projects: ProjectRepository

    @Inject lateinit var canvas: CanvasRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var exporter: ArtworkExporter

    @Inject lateinit var timelapse: TimelapseRecorder

    @Inject lateinit var storage: ProjectStorage

    private lateinit var viewModel: CanvasViewModel
    private var projectId = 0L
    private var navigations = 0

    @Before fun setup() {
        hilt.inject()
        projectId = runBlocking { projects.saveProject(Project(0L, "Evening study", "", null, 256, 256, 72, 1L, 1L)) }
        runBlocking(Dispatchers.Main) { viewModel = CanvasViewModel(canvas, projects, settings, exporter, timelapse) }
    }

    private fun setEditorContent(content: @Composable () -> Unit) {
        // ArtFlowCanvasView is a Hilt AndroidEntryPoint, so it needs the real injected activity.
        compose.runOnUiThread { compose.activity.setContent(content = content) }
    }

    @After fun cleanup() {
        // Detach GL and lifecycle observers before disposing their repository.
        setEditorContent {}
        compose.waitForIdle()
        if (::viewModel.isInitialized) {
            runBlocking(Dispatchers.Main) {
                viewModel.viewModelScope.cancel()
                canvas.dispose()
            }
        }
        if (projectId != 0L) {
            runBlocking { projects.deleteProjectById(projectId) }
            storage.deleteProjectFiles(projectId)
        }
    }

    private fun openEditor(preferences: AppSettings = AppSettings(themeMode = ThemeMode.DARK)) {
        setEditorContent {
            ArtFlowTheme(preferences) {
                CanvasScreen(projectId, { navigations++ }, viewModel = viewModel)
            }
        }
        compose.waitUntil(15_000) { viewModel.uiState.value is CanvasUiState.Ready }
        // A real raster on the GL surface makes missing-canvas screenshots unmistakable.
        runBlocking(Dispatchers.Main) {
            val image = PixelBuffer(256, 256)
            for (y in 0 until 256) {
                for (x in 0 until 256) {
                    val distance = (x - 150) * (x - 150) + (y - 92) * (y - 92)
                    val color =
                        when {
                            distance < 30 * 30 -> 0xFFFFD9A0.toInt()
                            y > 195 + x / 8 -> 0xFF182B38.toInt()
                            y > 160 - x / 5 -> 0xFF395761.toInt()
                            else -> 0xFF586D80.toInt()
                        }
                    image.setUnchecked(x, y, color)
                }
            }
            assertTrue(canvas.setLayerPixels(canvas.getActiveLayerId(), image, "Workspace UI evidence"))
        }
        compose.waitForIdle()
    }

    /** Full screen lives in Actions > Prefs, and the on-screen exit button must stay reachable. */
    private fun enterFocusMode() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Prefs").performScrollTo().performClick()
        compose.onNodeWithText("Full screen").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Exit focus mode").assertIsDisplayed()
    }

    @Test fun focusHidesTheChromeAndBackRestoresTheEditor() {
        openEditor()
        compose.onNodeWithContentDescription("Paint").assertIsOn()
        screenshot("studio-dark.png")
        enterFocusMode()
        compose.onNodeWithContentDescription("Actions").assertDoesNotExist()
        screenshot("studio-focus.png")
        Espresso.pressBack()
        compose.onNodeWithContentDescription("Actions").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, navigations) }
        enterFocusMode()
        compose.onNodeWithContentDescription("Exit focus mode").performClick()
        compose.onNodeWithContentDescription("Paint").assertIsOn()
    }

    @Test fun workflowPanelsAreReachableFromTheTopBarActions() {
        openEditor()
        val routes =
            listOf(
                Triple("Canvas", "Drawing Guide", "Guides"),
                Triple("Canvas", "Animation Assist", "Animation"),
                Triple("Add", "Add text", "Text"),
                Triple("Share", "Share or export artwork…", "Export"),
            )
        for ((tab, row, title) in routes) {
            compose.onNodeWithContentDescription("Actions").performClick()
            compose.onNodeWithText(tab).performScrollTo().performClick()
            compose.onNodeWithText(row).performScrollTo().performClick()
            compose.onAllNodesWithText(title).onFirst().assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithContentDescription("Actions").assertIsDisplayed()
        }
        // Tapping the active tool's top-bar button opens its panel, where every tool is one tap away.
        compose.onNodeWithContentDescription("Erase").performClick()
        compose.onNodeWithContentDescription("Erase").performClick()
        compose.onAllNodesWithText("Tool options").onFirst().assertIsDisplayed()
        compose.onNodeWithText("All tools").performScrollTo().performClick()
        // The strip lists every tool by icon and announces its name, so it stays one row high.
        compose.onNodeWithContentDescription(ToolType.PAINT_BUCKET.displayName).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ToolType.PAINT_BUCKET, viewModel.input.value.tool) }
    }

    @Test fun allNineteenToolsRemainSelectableAtLargeTextSize() {
        var chosen by mutableStateOf(ToolType.BRUSH)
        val actions = mutableListOf<StudioAction>()
        setEditorContent {
            ArtFlowTheme(AppSettings(themeMode = ThemeMode.LIGHT, uiScale = 1.6f, largeTouchTargets = true, reduceMotion = true)) {
                StudioToolPalette(chosen, { chosen = it }, { actions += it })
            }
        }
        for (tool in ToolType.entries) {
            compose.onNodeWithText(tool.displayName).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(tool, chosen) }
            compose.onNodeWithText(tool.displayName).assertIsSelected()
        }
        for (action in StudioAction.entries) {
            compose.onNodeWithText(action.label).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(action, actions.last()) }
        }
        screenshot("studio-palette-large-text.png")
    }

    @Test fun lightWorkspaceKeepsPrimaryControlsReachable() {
        openEditor(AppSettings(themeMode = ThemeMode.LIGHT, largeTouchTargets = true, reduceMotion = true))
        compose.onNodeWithContentDescription("Erase").performClick()
        compose.onNodeWithContentDescription("Erase").assertIsOn()
        compose.onNodeWithContentDescription("Actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Layers").assertIsDisplayed()
        screenshot("studio-light.png")
    }

    @Test
    fun textSettingsChoosesPositionPlacesOnceAndRestoresExactUndo() {
        openEditor()
        val layerCount = canvas.getAllLayers().size
        val depth = canvas.undoDepth
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Add text").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ToolType.TEXT, viewModel.input.value.tool) }
        compose.onNodeWithText("Content").performTextReplacement("Title")
        compose.runOnIdle { viewModel.requestTextAt(50f, 60f) }
        compose.onNodeWithText("Place on canvas").performScrollTo().performClick()
        compose.waitUntil(10_000) { canvas.undoDepth == depth + 1 }
        compose.runOnIdle { assertNull(viewModel.pendingText.value) }
        compose.onNodeWithText("Place on canvas").assertDoesNotExist()
        // Text becomes its own editable layer, as in Procreate, rather than baked pixels.
        assertEquals(layerCount + 1, canvas.getAllLayers().size)
        assertNotNull(canvas.getAllLayers().firstOrNull { it.textContent?.text == "Title" })
        compose.onNodeWithContentDescription("Undo").performClick()
        assertEquals(layerCount, canvas.getAllLayers().size)
        assertNull(canvas.getAllLayers().firstOrNull { it.textContent?.text == "Title" })
    }

    @Test
    fun dismissingTextClearsItsPositionAndDoesNotPlaceArtwork() {
        openEditor()
        val depth = canvas.undoDepth
        compose.runOnIdle { viewModel.requestTextAt(50f, 60f) }
        compose.onNodeWithText("Place on canvas").assertIsDisplayed()
        Espresso.pressBack()
        compose.runOnIdle { assertNull(viewModel.pendingText.value) }
        compose.runOnIdle { assertEquals(depth, canvas.undoDepth) }
        // The same point can be chosen afresh; it is not an abandoned placement from before.
        compose.runOnIdle { viewModel.requestTextAt(50f, 60f) }
        compose.onNodeWithText("Place on canvas").assertIsDisplayed()
        Espresso.pressBack()
        compose.runOnIdle { assertNull(viewModel.pendingText.value) }
    }

    @Test
    fun uiScaleAppliesOnceAndKeepsTheSystemTextPreference() {
        var baseDensity = 0f
        var baseFont = 0f
        var scaledDensity = 0f
        var scaledFont = 0f
        var baseText = 0f
        var scaledText = 0f
        setEditorContent {
            val before = LocalDensity.current
            ArtFlowTheme(AppSettings(uiScale = 1.6f)) {
                val after = LocalDensity.current
                SideEffect {
                    baseDensity = before.density
                    baseFont = before.fontScale
                    scaledDensity = after.density
                    scaledFont = after.fontScale
                    baseText = with(before) { 20.sp.toPx() }
                    scaledText = with(after) { 20.sp.toPx() }
                }
            }
        }
        compose.runOnIdle {
            assertTrue(baseDensity > 0f)
            // Density carries the UI scale, so a scaled dp and a scaled sp both grow exactly once.
            assertEquals(baseDensity * 1.6f, scaledDensity, 0.0001f)
            assertEquals(baseText * 1.6f, scaledText, 0.01f)
            // The independent system text preference is never overwritten by the app's scale.
            assertEquals(baseFont, scaledFont, 0f)
        }
    }

    private fun screenshot(name: String) = TestEvidence.screenshot(name)
}
