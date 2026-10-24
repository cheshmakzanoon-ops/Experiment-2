package com.artflow.studio

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.viewModelScope
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.components.editor.StudioAction
import com.artflow.studio.presentation.ui.components.editor.StudioToolPalette
import com.artflow.studio.presentation.ui.screens.canvas.CanvasScreen
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import com.artflow.studio.presentation.ui.theme.studioColorScheme
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
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real editor navigation and separately labelled legacy palette component coverage. */
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

    private fun setThemedContent(
        preferences: AppSettings,
        content: @Composable () -> Unit,
    ) {
        setEditorContent {
            ArtFlowTheme(preferences) {
                // Match MainActivity: MaterialTheme alone does not paint the window background.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
            }
        }
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
        setThemedContent(preferences) {
            CanvasScreen(projectId, { navigations++ }, viewModel = viewModel)
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
        awaitRenderedArtwork()
    }

    /** Full screen lives in Actions > Prefs, and the on-screen exit button must stay reachable. */
    private fun enterFocusMode() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Prefs").performScrollTo().performClick()
        compose.onNodeWithText("Full screen").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Exit focus mode").assertIsDisplayed()
    }

    @Test fun smudgeSidebarUsesItsOwnOpacityAndLeavesPaintUnchanged() {
        openEditor()
        compose.runOnIdle {
            viewModel.setBrushParams(BrushParams(opacity = 0.8f))
            viewModel.setSmudgeParams(BrushParams(opacity = 0.3f, minOpacity = 0.2f, maxOpacity = 0.6f))
            viewModel.setTool(ToolType.SMUDGE)
        }
        compose
            .onNodeWithContentDescription(
                "Opacity 30%",
            ).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithContentDescription("Opacity 60%").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0.6f, requireNotNull(viewModel.input.value.smudgeParams).opacity, 0.0001f)
            assertEquals(0.8f, viewModel.input.value.brushParams.opacity, 0.0001f)
        }
        workspaceScreenshot("studio-smudge-opacity.png")
        compose.onNodeWithContentDescription("Paint").performClick()
        compose.onNodeWithContentDescription("Opacity 80%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Smudge").performClick()
        compose
            .onNodeWithContentDescription(
                "Opacity 60%",
            ).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithContentDescription("Opacity 20%").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0.8f, viewModel.input.value.brushParams.opacity, 0.0001f) }
    }

    @Test fun focusHidesTheChromeAndBackRestoresTheEditor() {
        openEditor()
        compose.onNodeWithContentDescription("Paint").assertIsOn()
        workspaceScreenshot("studio-dark.png")
        enterFocusMode()
        compose.onNodeWithContentDescription("Actions").assertDoesNotExist()
        workspaceScreenshot("studio-focus.png")
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

    /** Isolated legacy component coverage; the production editor route is exercised separately below. */
    @Test fun standalonePaletteRemainsReadableAndSelectableAtLargeTextSize() {
        var chosen by mutableStateOf(ToolType.BRUSH)
        val actions = mutableListOf<StudioAction>()
        val preferences = AppSettings(themeMode = ThemeMode.LIGHT, uiScale = 1.6f, largeTouchTargets = true, reduceMotion = true)
        setThemedContent(preferences) {
            StudioToolPalette(chosen, { chosen = it }, { actions += it })
        }
        for (tool in ToolType.entries) {
            compose.onNodeWithText(tool.displayName).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(tool, chosen) }
            compose.onNodeWithText(tool.displayName).assertIsSelected()
        }
        for (action in StudioAction.entries) {
            compose.onNodeWithText(action.label).performScrollTo().assertIsDisplayed()
            awaitPaletteContrast(action.label, preferences)
            compose.onNodeWithText(action.label).performClick()
            compose.runOnIdle { assertEquals(action, actions.last()) }
        }
        awaitPaletteContrast(StudioAction.EXPORT.label, preferences)
        TestEvidence.screenshot("studio-palette-large-text.png")
    }

    @Test fun everyEditorToolIsReachableThroughTheRealLargeTextToolOptions() {
        openEditor(AppSettings(themeMode = ThemeMode.LIGHT, uiScale = 1.6f, largeTouchTargets = true, reduceMotion = true))
        compose.onNodeWithContentDescription("Erase").performClick()
        compose.onNodeWithContentDescription("Erase").performClick()
        compose.onAllNodesWithText("Tool options").onFirst().assertIsDisplayed()
        for (tool in ToolType.entries) {
            // Choosing from the full strip deliberately collapses it, while leaving Tool options open.
            compose.onNodeWithText("All tools").performScrollTo().performClick()
            stripTool(tool).performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("The actual editor must activate ${tool.displayName}", tool, viewModel.input.value.tool) }
        }
        compose.onNodeWithText("All tools").performScrollTo().performClick()
        stripTool(ToolType.ZOOM).performScrollTo().assertIsDisplayed().assertIsSelected()
        compose.waitForIdle()
        TestEvidence.screenshot("studio-tools-strip-large-text.png")
        compose.onNodeWithContentDescription("Close Tool options").performClick()
        workspaceScreenshot("studio-tools-large-text.png")
    }

    private fun stripTool(tool: ToolType): SemanticsNodeInteraction =
        compose.onNode(hasContentDescription(tool.displayName) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    @Test fun lightWorkspaceKeepsPrimaryControlsReachable() {
        openEditor(AppSettings(themeMode = ThemeMode.LIGHT, largeTouchTargets = true, reduceMotion = true))
        compose.onNodeWithContentDescription("Erase").performClick()
        compose.onNodeWithContentDescription("Erase").assertIsOn()
        compose.onNodeWithContentDescription("Actions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Layers").assertIsDisplayed()
        workspaceScreenshot("studio-light.png")
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

    private fun workspaceScreenshot(name: String) {
        compose.waitForIdle()
        awaitRenderedArtwork()
        TestEvidence.screenshot(name)
    }

    /** Compose idle does not imply that the separate GL renderer has presented the fixture. */
    private fun awaitRenderedArtwork() {
        val anchors =
            listOf(
                Triple(150f, 92f, 0xFFFFD9A0.toInt()),
                Triple(90f, 90f, 0xFF586D80.toInt()),
                Triple(180f, 180f, 0xFF395761.toInt()),
                Triple(180f, 240f, 0xFF182B38.toInt()),
            )
        val expected = anchors.flatMap { anchor -> List(9) { anchor.third } }
        var observed: List<Int>? = null
        try {
            compose.waitUntil(20_000) {
                var points: List<Pair<Int, Int>>? = null
                compose.runOnUiThread {
                    val view = findCanvas(compose.activity.findViewById(android.R.id.content)) ?: return@runOnUiThread
                    if (!view.isShown || view.width <= 0 || view.height <= 0) return@runOnUiThread
                    val origin = IntArray(2).also(view::getLocationOnScreen)
                    points =
                        anchors.flatMap { (x, y) ->
                            val position = view.canvasToView(x, y)
                            (-1..1).flatMap { dy ->
                                (-1..1).map { dx ->
                                    origin[0] + position.first.roundToInt() + dx to origin[1] + position.second.roundToInt() + dy
                                }
                            }
                        }
                }
                observed = points?.let(TestEvidence::colorsAtScreenPoints)
                observed?.zip(expected)?.all { (actual, wanted) -> closeColor(actual, wanted) } == true
            }
        } catch (timeout: ComposeTimeoutException) {
            TestEvidence.screenshot("studio-workspace-render-timeout.png")
            throw AssertionError("The presented GL frame must contain all four artwork regions; observed=$observed", timeout)
        }
    }

    private fun findCanvas(view: View): ArtFlowCanvasView? {
        if (view is ArtFlowCanvasView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    /** Check the rendered button, including the actual parent background, rather than theme state alone. */
    private fun awaitPaletteContrast(
        label: String,
        preferences: AppSettings,
    ) {
        val colors = studioColorScheme(preferences.accent, dark = false, highContrast = preferences.highContrast)
        val background = colors.background.toArgb()
        val foreground = colors.primary.toArgb()
        var observed = emptyMap<Int, Int>()
        var contrast = 0.0
        try {
            compose.waitUntil(10_000) {
                val pixels = compose.onNodeWithText(label).captureToImage().toPixelMap()
                val counts = mutableMapOf<Int, Int>()
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) {
                        val color = pixels[x, y].toArgb()
                        counts[color] = (counts[color] ?: 0) + 1
                    }
                }
                observed = counts
                val paper = counts.filterKeys { closeColor(it, background) }.maxByOrNull { it.value }
                val ink = counts.filterKeys { closeColor(it, foreground) }.maxByOrNull { it.value }
                if (paper == null || ink == null || paper.value < 12 || ink.value < 12) {
                    false
                } else {
                    contrast = ColorUtils.calculateContrast(ink.key, paper.key)
                    contrast >= 4.5
                }
            }
        } catch (timeout: ComposeTimeoutException) {
            TestEvidence.screenshot("studio-palette-contrast-timeout.png")
            val common = observed.entries.sortedByDescending { it.value }.take(6)
            throw AssertionError("$label must show readable text on its themed surface; contrast=$contrast, common pixels=$common", timeout)
        }
        assertTrue("$label rendered contrast must be at least 4.5:1", contrast >= 4.5)
    }

    private fun closeColor(
        actual: Int,
        expected: Int,
    ): Boolean = (0..3).all { channel -> abs((actual ushr (channel * 8) and 255) - (expected ushr (channel * 8) and 255)) <= 2 }
}
