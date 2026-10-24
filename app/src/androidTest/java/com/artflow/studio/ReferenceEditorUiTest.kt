package com.artflow.studio

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
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
import com.artflow.studio.presentation.ui.screens.canvas.CanvasScreen
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import com.artflow.studio.presentation.ui.theme.studioColorScheme
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real pointer routing where the floating Reference window overlaps the editor's other controls. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReferenceEditorUiTest {
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

    private val preferences =
        AppSettings(
            themeMode = ThemeMode.LIGHT,
            rightHandedInterface = false,
            reduceMotion = true,
            autosaveEnabled = false,
            recordTimelapse = false,
        )
    private lateinit var viewModel: CanvasViewModel
    private lateinit var originalPixels: IntArray
    private lateinit var originalBrush: BrushParams
    private var originalHistory = 0L
    private var originalUndoDepth = 0
    private var previousSettings: AppSettings? = null
    private var projectId = 0L
    private val artworkColor = 0xFF146F86.toInt()
    private val window get() = compose.onNodeWithTag("reference-window")
    private val handle get() = compose.onNodeWithTag("reference-handle")
    private val close get() = compose.onNodeWithContentDescription("Close reference")
    private val sizeSlider get() = compose.onNode(hasContentDescription("Brush size ", substring = true))

    @Before fun setUp() {
        hilt.inject()
        previousSettings = settings.current()
        runBlocking {
            settings.update { preferences }
            projectId = projects.saveProject(Project(0L, "Reference routing", "", null, 128, 128, 72, 1L, 1L))
        }
        runBlocking(Dispatchers.Main) { viewModel = CanvasViewModel(canvas, projects, settings, exporter, timelapse) }
        compose.runOnUiThread {
            compose.activity.setContent {
                val current by viewModel.settings.collectAsState()
                ArtFlowTheme(current) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CanvasScreen(projectId, {}, viewModel = viewModel)
                    }
                }
            }
        }
        compose.waitUntil(15_000) { viewModel.uiState.value is CanvasUiState.Ready }
        compose.waitUntil(5_000) { viewModel.settings.value == preferences }
        runBlocking(Dispatchers.Main) {
            assertTrue(canvas.setLayerPixels(canvas.getActiveLayerId(), PixelBuffer.filled(128, 128, artworkColor), "Reference fixture"))
            viewModel.setBrushSize(24f)
            viewModel.setBrushOpacity(0.6f)
            originalBrush = viewModel.input.value.brushParams
            originalPixels = requireNotNull(canvas.layerPixels(canvas.getActiveLayerId())).pixels.copyOf()
            originalHistory = canvas.historyMark
            originalUndoDepth = canvas.undoDepth
        }
        awaitPresentedArtwork()
    }

    @After fun cleanUp() {
        if (::viewModel.isInitialized) {
            compose.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            runBlocking(Dispatchers.Main) {
                viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                canvas.dispose()
            }
        }
        if (projectId != 0L) {
            runBlocking { projects.deleteProjectById(projectId) }
            storage.deleteProjectFiles(projectId)
        }
        previousSettings?.let { previous -> runBlocking { settings.update { previous } } }
    }

    @Test fun referenceControlsStayAboveEitherSidebarWithoutBlockingTheRestOfTheEditor() {
        openReference()
        val leftSlider = sizeSlider.bounds()
        val canvasTab = referenceTab("Canvas")
        moveReferenceTo(
            editorBounds().left,
            window.bounds().top + leftSlider.center.y - canvasTab.bounds().center.y,
        )
        assertReferenceControlsVisible()
        assertHeaderPaintsAboveSidebar()
        // The touch really lands inside both controls. A semantics click would miss this regression.
        tapAt(canvasTab, overlap(canvasTab.bounds(), sizeSlider.bounds()).center)
        canvasTab.assertIsSelected()
        assertBrushUnchanged()
        awaitReferencePreview()
        TestEvidence.screenshot("reference-editor-left.png")
        tapAt(referenceTab("Image"))
        referenceTab("Image").assertIsSelected()
        compose.onNodeWithTag("reference-image").assertDoesNotExist()

        val beforeDrag = window.bounds()
        val start = overlap(handle.bounds(), sizeSlider.bounds()).center
        dragHandle(start, Offset(0f, 32f * density()))
        assertTrue("Dragging the covered title must move Reference", window.bounds().top > beforeDrag.top + 8f * density())
        assertInsideEditor()
        assertBrushUnchanged()
        tapAt(close)
        window.assertDoesNotExist()

        updatePreferences { it.copy(rightHandedInterface = true) }
        openReference()
        val rightSlider = sizeSlider.bounds()
        moveReferenceTo(
            editorBounds().right - window.bounds().width,
            window.bounds().top + rightSlider.top + rightSlider.height * 0.3f - close.bounds().center.y,
        )
        assertReferenceControlsVisible()
        tapAt(referenceTab("Canvas"))
        awaitReferencePreview()
        TestEvidence.screenshot("reference-editor-right.png")
        tapAt(close, overlap(close.bounds(), sizeSlider.bounds()).center)
        window.assertDoesNotExist()
        assertBrushUnchanged()

        openReference()
        assertModalKeepsPrecedence()
        assertOutsideTouchesReachTheEditor()
        assertDocumentUnchanged()
    }

    @Test fun referenceAndFocusExitRemainReachableWithEitherTouchTargetSize() {
        openReference()
        moveReferenceTo(editorBounds().left, editorBounds().top)
        for (large in listOf(false, true)) {
            updatePreferences { it.copy(largeTouchTargets = large) }
            val normalBounds = window.bounds()
            compose.onNodeWithContentDescription("Actions").performClick()
            actionTab("Prefs").performScrollTo().performClick()
            val fullScreen = compose.onNodeWithText("Full screen").performScrollTo()
            tapAt(fullScreen)
            val exit = compose.onNodeWithContentDescription("Exit focus mode").assertIsDisplayed()
            assertReferenceControlsVisible()
            assertTrue(
                "Reference must clear the actual focus-exit target: window=${window.bounds()}, exit=${exit.bounds()}",
                window.bounds().top > exit.bounds().bottom,
            )
            tapAt(referenceTab("Canvas"))
            referenceTab("Canvas").assertIsSelected()
            awaitPresentedArtwork()
            awaitReferencePreview()
            TestEvidence.screenshot(if (large) "reference-editor-focus-large-targets.png" else "reference-editor-focus.png")
            tapAt(referenceTab("Image"))
            referenceTab("Image").assertIsSelected()
            tapAt(exit)
            compose.onNodeWithContentDescription("Actions").assertIsDisplayed()
            assertReferenceControlsVisible()
            assertEquals("Focus must preserve the saved horizontal placement", normalBounds.left, window.bounds().left, 1f)
            assertEquals("Focus must preserve the saved vertical placement", normalBounds.top, window.bounds().top, 1f)
        }
        assertBrushUnchanged()
        assertDocumentUnchanged()
    }

    private fun openReference() {
        compose.onNodeWithContentDescription("Actions").performClick()
        actionTab("Canvas").performScrollTo().performClick()
        compose.onNodeWithText("Reference").performScrollTo().performClick()
        assertReferenceControlsVisible()
    }

    private fun assertModalKeepsPrecedence() {
        // Calibrate from the real modal geometry on this device, then put Reference beneath that tab.
        val actions = compose.onNodeWithContentDescription("Actions")
        actions.performClick()
        val target = actionTab("Canvas").performScrollTo().bounds()
        actions.performClick()
        moveReferenceTo(target.center.x - window.bounds().width / 4f, target.center.y - window.bounds().height / 4f)
        actions.performClick()
        actionTab("Add").performScrollTo().performClick()
        val tab = actionTab("Canvas").performScrollTo()
        tab.assertIsNotSelected()
        tapAt(tab, overlap(tab.bounds(), window.bounds()).center)
        tab.assertIsSelected()
        compose.onNodeWithText("Drawing Guide").assertIsDisplayed()
        window.assertExists()
        TestEvidence.screenshot("reference-editor-modal.png")
        actions.performClick()
        assertBrushUnchanged()
    }

    private fun assertOutsideTouchesReachTheEditor() {
        moveReferenceTo(editorBounds().left, editorBounds().top)
        val opacity = compose.onNode(hasContentDescription("Opacity ", substring = true))
        val point = Offset(opacity.bounds().center.x, opacity.bounds().bottom - 8f * density())
        assertTrue("The opacity probe must be outside Reference", !window.bounds().contains(point))
        tapAt(opacity, point)
        compose.runOnIdle {
            assertTrue("Reference's empty host must not intercept the sidebar", viewModel.input.value.brushParams.opacity < 0.3f)
            viewModel.setBrushOpacity(originalBrush.opacity)
            viewModel.setTool(ToolType.ZOOM)
        }
        val view = compose.runOnIdle { canvasView() }
        val scale = compose.runOnIdle { view.currentScale() }
        val area = editorBounds()
        val canvasPoint = Offset(area.center.x, area.bottom - 40f * density())
        assertTrue("The canvas probe must be outside Reference", !window.bounds().contains(canvasPoint))
        tapAt(compose.onRoot(), canvasPoint)
        compose.runOnIdle {
            assertEquals("Reference's empty host must let canvas navigation through", scale * 1.25f, view.currentScale(), 0.0001f)
            viewModel.setTool(ToolType.BRUSH)
        }
        assertBrushUnchanged()
    }

    private fun assertReferenceControlsVisible() {
        handle.assertIsDisplayed()
        referenceTab("Canvas").assertIsDisplayed()
        referenceTab("Image").assertIsDisplayed()
        compose.onNodeWithContentDescription("Import reference image").assertIsDisplayed()
        close.assertIsDisplayed()
        assertInsideEditor()
    }

    private fun assertInsideEditor() {
        val available = editorBounds()
        val panel = window.bounds()
        assertTrue("Reference must stay inside the editor: $panel within $available", panel.left >= available.left - 1f)
        assertTrue("Reference must stay inside the editor: $panel within $available", panel.top >= available.top - 1f)
        assertTrue("Reference must stay inside the editor: $panel within $available", panel.right <= available.right + 1f)
        assertTrue("Reference must stay inside the editor: $panel within $available", panel.bottom <= available.bottom + 1f)
    }

    private fun assertHeaderPaintsAboveSidebar() {
        val panel = window.bounds()
        val title = handle.bounds()
        val slider = sizeSlider.bounds()
        // Padding above the title is plain panel paper, over the actual sidebar's vertical track.
        val point = Offset(slider.center.x, (panel.top + title.top) / 2f)
        assertTrue("The paper probe must cover the sidebar: $point in $slider", slider.contains(point))
        val paper = studioColorScheme(preferences.accent, dark = false, highContrast = false).surfaceColorAtElevation(4.dp).toArgb()
        awaitColor("reference-editor-header-timeout.png", paper) { screenPatch(point) }
    }

    private fun assertBrushUnchanged() {
        compose.runOnIdle {
            assertEquals("Reference controls must not operate the covered sidebar", originalBrush, viewModel.input.value.brushParams)
        }
    }

    private fun assertDocumentUnchanged() {
        runBlocking(Dispatchers.Main) {
            val actual = requireNotNull(canvas.layerPixels(canvas.getActiveLayerId())).pixels
            assertArrayEquals("Reference interactions must not paint the artwork", originalPixels, actual)
            assertEquals(originalHistory, canvas.historyMark)
            assertEquals(originalUndoDepth, canvas.undoDepth)
        }
    }

    private fun updatePreferences(change: (AppSettings) -> AppSettings) {
        val next = change(settings.current())
        runBlocking { settings.update { next } }
        compose.waitUntil(5_000) { viewModel.settings.value == next }
        compose.waitForIdle()
    }

    private fun referenceTab(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("reference-window")))

    private fun actionTab(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun tapAt(
        node: SemanticsNodeInteraction,
        point: Offset = node.bounds().center,
    ) {
        val local = point - node.bounds().topLeft
        node.performTouchInput { click(local) }
        compose.waitForIdle()
    }

    private fun moveReferenceTo(
        left: Float,
        top: Float,
    ) {
        val current = window.bounds()
        dragHandle(handle.bounds().center, Offset(left - current.left, top - current.top))
    }

    private fun dragHandle(
        start: Offset,
        delta: Offset,
    ) {
        val local = start - handle.bounds().topLeft
        handle.performTouchInput { swipe(local, local + delta, durationMillis = 500) }
        compose.waitForIdle()
    }

    private fun overlap(
        first: Rect,
        second: Rect,
    ): Rect {
        val shared = first.intersect(second)
        assertTrue("This regression needs an actual overlap: $first versus $second", shared.width > 4f && shared.height > 4f)
        return shared
    }

    private fun editorBounds(): Rect {
        val root = compose.onRoot().fetchSemanticsNode()
        val rootOffset = root.boundsInWindow.topLeft - root.boundsInRoot.topLeft
        return compose.runOnIdle {
            val view = canvasView()
            val origin = IntArray(2).also(view::getLocationInWindow)
            Rect(
                origin[0] - rootOffset.x,
                origin[1] - rootOffset.y,
                origin[0] + view.width - rootOffset.x,
                origin[1] + view.height - rootOffset.y,
            )
        }
    }

    private fun canvasView(): ArtFlowCanvasView = requireNotNull(findCanvas(compose.activity.findViewById(android.R.id.content)))

    private fun findCanvas(view: View): ArtFlowCanvasView? {
        if (view is ArtFlowCanvasView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun density(): Float = compose.activity.resources.displayMetrics.density * viewModel.settings.value.uiScale

    private fun screenPatch(point: Offset): List<Pair<Int, Int>> {
        val root = compose.onRoot().fetchSemanticsNode()
        val shift = root.boundsInWindow.topLeft - root.boundsInRoot.topLeft
        val offset =
            compose.runOnIdle {
                val decor = compose.activity.window.decorView
                val screen = IntArray(2).also(decor::getLocationOnScreen)
                val window = IntArray(2).also(decor::getLocationInWindow)
                Offset(screen[0] - window[0].toFloat(), screen[1] - window[1].toFloat())
            }
        val centre = point + shift + offset
        return (-1..1).flatMap { y -> (-1..1).map { x -> centre.x.roundToInt() + x to centre.y.roundToInt() + y } }
    }

    private fun awaitPresentedArtwork() {
        awaitColor("reference-editor-artwork-timeout.png", artworkColor) {
            val area = editorBounds()
            val panels = compose.onAllNodesWithTag("reference-window").fetchSemanticsNodes().map { it.boundsInRoot }
            val point =
                compose.runOnIdle {
                    listOf(64f to 112f, 112f to 112f, 32f to 96f, 64f to 64f)
                        .map { (x, y) -> canvasView().canvasToView(x, y).let { area.topLeft + Offset(it.first, it.second) } }
                        .first { candidate -> area.contains(candidate) && panels.none { it.contains(candidate) } }
                }
            screenPatch(point)
        }
    }

    private fun awaitReferencePreview() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reference-image").fetchSemanticsNodes().isNotEmpty() }
        awaitColor("reference-editor-preview-timeout.png", artworkColor) {
            screenPatch(compose.onNodeWithTag("reference-image").bounds().center)
        }
    }

    private fun awaitColor(
        failureImage: String,
        expected: Int,
        points: () -> List<Pair<Int, Int>>,
    ) {
        var observed: List<Int>? = null
        var sampled: List<Pair<Int, Int>> = emptyList()
        try {
            compose.waitUntil(20_000) {
                sampled = points()
                observed = TestEvidence.colorsAtScreenPoints(sampled)
                observed?.all { actual ->
                    (0..16 step 8).all { shift -> abs((actual ushr shift and 255) - (expected ushr shift and 255)) <= 2 }
                } == true
            }
        } catch (timeout: ComposeTimeoutException) {
            TestEvidence.screenshot(failureImage)
            val colors = observed?.map { it.toUInt().toString(16) }
            throw AssertionError("Expected ${expected.toUInt().toString(16)} at $sampled, observed=$colors", timeout)
        }
    }
}
