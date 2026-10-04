package com.artflow.studio

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/** Real navigation, ViewModels, input, Room and document storage in the unmodified app shell. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ArtworkWorkflowTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var projects: ProjectRepository

    @Inject lateinit var canvas: CanvasRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var storage: ProjectStorage

    @Before
    fun setup() {
        hilt.inject()
    }

    @Test
    fun createPaintSaveReopenRecreateAndDiscardPreserveTheSavedPixels() {
        val originalSettings = runBlocking { settings.settings.first() }
        runBlocking {
            settings.update { it.copy(seenOnboarding = true, stylusOnly = false, autosaveEnabled = false) }
        }
        val name = "Workflow-${System.nanoTime()}"
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            createArtwork(name)
            awaitCanvas(scenario)
            val createdProjects = runBlocking { projects.getAllProjects().first() }
            val projectId = createdProjects.single { it.name == name }.id
            val blank = pixels()
            paint(scenario, 0.45f)
            compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() } }
            val painted = pixels()
            assertFalse("Input through the editor must change real artwork", blank.contentEquals(painted))
            inspectReachablePanels(scenario, painted)
            inspectLayerOrdering(painted)
            inspectTextPlacement(scenario, painted)
            inspectBrushStudio(painted)
            inspectFocusMode(scenario, painted)

            // Exercise the save-before-navigation callback, not a direct repository save.
            compose.onNodeWithText("Gallery").performClick()
            compose.onNodeWithText("Save changes?").assertIsDisplayed()
            compose.onNodeWithText("Save and leave").performClick()
            awaitGallery()
            assertTrue(storage.documentFile(projectId).isFile)
            val saved = runBlocking { projects.getProjectById(projectId) }!!
            assertNotNull(saved.thumbnailPath)
            assertEquals(64, saved.width)
            assertEquals(64, saved.height)

            compose.onNodeWithText(name).performClick()
            awaitCanvas(scenario)
            assertArrayEquals("Reopening must read the saved artwork", painted, pixels())
            assertFalse(runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() })

            scenario.recreate()
            awaitCanvas(scenario)
            assertArrayEquals("Activity recreation must preserve the editor", painted, pixels())

            paint(scenario, 0.7f)
            compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() } }
            assertFalse(painted.contentEquals(pixels()))
            compose.onNodeWithText("Gallery").performClick()
            compose.onNodeWithText("Stay").performClick()
            compose.onNodeWithText("Save changes?").assertDoesNotExist()
            compose.onNodeWithText("Gallery").performClick()
            compose.onNodeWithText("Discard and leave").performClick()
            awaitGallery()
            compose.onNodeWithText(name).performClick()
            awaitCanvas(scenario)
            assertArrayEquals("Discarding must not replace the last saved artwork", painted, pixels())
            assertFalse(runBlocking { storage.hasUnsavedRecovery(projectId) })
        } finally {
            scenario.close()
            runBlocking {
                val owned = projects.getAllProjects().first().filter { it.name == name }
                owned.forEach {
                    projects.deleteProjectById(it.id)
                    storage.deleteProjectFiles(it.id)
                }
                settings.update { originalSettings }
            }
        }
    }

    private fun inspectBrushStudio(expectedPixels: IntArray) {
        val depth = runBlocking(Dispatchers.Main) { canvas.undoDepth }
        compose.onNodeWithContentDescription("Smudge").performClick().assertIsOn()
        // As in Procreate, tapping the active Smudge tool again opens the brush library.
        compose.onNodeWithContentDescription("Smudge").performClick()
        compose.onNodeWithText("Brush studio").assertIsDisplayed()
        compose.onNodeWithText("Search brushes").performTextInput("fine liner")
        compose.onNodeWithText("Fine liner").performClick()
        compose.onNodeWithText("Drawing pad").performClick()
        compose.onNodeWithTag("brush-practice-pad").performTouchInput { swipeLeft() }
        assertArrayEquals("Practice must never paint the real document", expectedPixels, pixels())
        assertEquals(depth, runBlocking(Dispatchers.Main) { canvas.undoDepth })
        compose.onNodeWithContentDescription("Cancel brush changes").performClick()
        compose.onNodeWithContentDescription("Smudge").assertIsOn()
        assertArrayEquals(expectedPixels, pixels())
        assertEquals(depth, runBlocking(Dispatchers.Main) { canvas.undoDepth })
        compose.onNodeWithContentDescription("Smudge").performClick()
        compose.onNodeWithText("Search brushes").performTextInput("fine liner")
        compose.onNodeWithText("Fine liner").performClick()
        compose.onNodeWithText("Use brush").performClick()
        compose.onNodeWithContentDescription("Paint").assertIsOn()
        assertArrayEquals(expectedPixels, pixels())
        assertEquals(depth, runBlocking(Dispatchers.Main) { canvas.undoDepth })
    }

    private fun inspectReachablePanels(
        scenario: ActivityScenario<MainActivity>,
        expectedPixels: IntArray,
    ) {
        val depth = runBlocking(Dispatchers.Main) { canvas.undoDepth }
        listOf("Guides", "Animation", "Canvas", "Text").forEach { title ->
            openWorkspace(if (title == "Text") "Add text" else title)
            // Crop & Resize starts with the crop box; its Settings button opens the exact sizes.
            if (title == "Canvas") {
                compose.onNodeWithContentDescription("Crop box").assertIsDisplayed()
                compose.onNodeWithText("Crop settings").performClick()
            }
            compose.onNodeWithContentDescription("Close $title").assertIsDisplayed()
            if (title == "Guides") TestEvidence.screenshot("studio-guides.png")
            compose.onNodeWithContentDescription("Close $title").performClick()
            assertArrayEquals("Opening $title must not edit pixels", expectedPixels, pixels())
        }
        openWorkspace("Reference")
        compose.onNodeWithContentDescription("Close reference").assertIsDisplayed()
        TestEvidence.screenshot("studio-reference-empty.png")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Close reference").assertDoesNotExist()
        compose.onNodeWithText("Save changes?").assertDoesNotExist()
        compose.onNodeWithContentDescription("Paint").performClick()
        assertArrayEquals(expectedPixels, pixels())
        assertEquals(depth, runBlocking(Dispatchers.Main) { canvas.undoDepth })
    }

    private fun inspectLayerOrdering(expectedPixels: IntArray) {
        val before = runBlocking(Dispatchers.Main) { canvas.getAllLayers().map { it.id } }
        val depth = runBlocking(Dispatchers.Main) { canvas.undoDepth }
        compose.onNodeWithContentDescription("Layers").performClick()
        compose.onNodeWithContentDescription("Add layer").performClick()
        compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) { canvas.getAllLayers().size == before.size + 1 } }
        val added = runBlocking(Dispatchers.Main) { canvas.getActiveLayerId() }
        compose.onNodeWithContentDescription("Move active layer down").performClick()
        compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) { canvas.getAllLayers().first().id == added } }
        compose.onNodeWithContentDescription("Move active layer down").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move active layer up").performClick()
        compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) { canvas.getAllLayers().last().id == added } }
        compose.onNodeWithContentDescription("Move active layer up").assertIsNotEnabled()
        TestEvidence.screenshot("studio-layers.png")
        compose.onNodeWithContentDescription("Close Layers").performClick()
        repeat(3) { compose.onNodeWithContentDescription("Undo").performClick() }
        compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) { canvas.undoDepth == depth } }
        assertEquals(before, runBlocking(Dispatchers.Main) { canvas.getAllLayers().map { it.id } })
        assertArrayEquals(expectedPixels, pixels())
    }

    private fun inspectTextPlacement(
        scenario: ActivityScenario<MainActivity>,
        expectedPixels: IntArray,
    ) {
        val depth = runBlocking(Dispatchers.Main) { canvas.undoDepth }
        openWorkspace("Add text")
        compose.onNodeWithText("Content").performTextReplacement("A")
        compose.onNodeWithContentDescription("Size").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(16f) }
        compose.onNodeWithText("Place on canvas").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close Text").assertDoesNotExist()
        tapCanvas(scenario)
        compose.onNodeWithContentDescription("Close Text").assertIsDisplayed()
        assertArrayEquals("Choosing an anchor must not paint before confirmation", expectedPixels, pixels())
        compose.onNodeWithText("Place on canvas").performScrollTo().performClick()
        compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) { canvas.undoDepth == depth + 1 } }
        assertFalse("The reachable text workflow must rasterise real glyphs", expectedPixels.contentEquals(pixels()))
        tapWhenEnabled("Undo")
        awaitOrReport("Undo did not remove the placed text") { runBlocking(Dispatchers.Main) { canvas.undoDepth == depth } }
        assertArrayEquals(expectedPixels, pixels())

        // Closing an anchored text panel cancels placement without another history entry.
        openWorkspace("Add text")
        compose.onNodeWithText("Place on canvas").performScrollTo().performClick()
        tapCanvas(scenario)
        compose.onNodeWithContentDescription("Close Text").performClick()
        compose.onNodeWithContentDescription("Paint").performClick()
        assertEquals(depth, runBlocking(Dispatchers.Main) { canvas.undoDepth })
        assertArrayEquals(expectedPixels, pixels())
    }

    private fun openWorkspace(title: String) {
        val ready =
            try {
                compose.waitUntil(15_000) {
                    try {
                        compose
                            .onAllNodesWithContentDescription("Actions")
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    } catch (_: IllegalStateException) {
                        false
                    }
                }
                true
            } catch (_: ComposeTimeoutException) {
                false
            }
        if (!ready) throw AssertionError("No Actions button before opening $title. Screen: ${screenSummary()}")
        val (tab, item) = ACTIONS_LOCATIONS[title] ?: ("Add" to title)
        compose.onNodeWithContentDescription("Actions").performClick()
        // A sheet that is still closing can swallow the tap; tap Actions again if the panel is missing.
        if (!appears(tab, 3_000)) compose.onNodeWithContentDescription("Actions").performClick()
        if (!appears(tab, 15_000)) throw AssertionError("The Actions panel did not open for $title. Screen: ${screenSummary()}")
        // The tab row scrolls on narrow phones, so bring the tab into view before tapping it.
        compose.onNodeWithText(tab).performScrollTo().performClick()
        val target = compose.onNodeWithText(item).performScrollTo()
        lastTapped = "$item at ${target.fetchSemanticsNode().boundsInWindow}"
        target.performClick()
        compose.waitForIdle()
    }

    /** Where the last panel item was tapped, for failure messages. */
    private var lastTapped = "nothing"

    /** Waits for [condition]; on timeout, fails with [problem] and what the screen shows. */
    private fun awaitOrReport(
        problem: String,
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(15_000, condition)
        } catch (_: ComposeTimeoutException) {
            throw AssertionError("$problem. Screen: ${screenSummary()}")
        }
    }

    /** History buttons follow the repository asynchronously; tap only once the button is enabled. */
    private fun tapWhenEnabled(description: String) {
        val node = compose.onNodeWithContentDescription(description)
        compose.waitUntil(5_000) { runCatching { node.assertIsEnabled() }.isSuccess }
        lastTapped = "$description at ${node.fetchSemanticsNode().boundsInWindow}"
        node.performClick()
    }

    /** Waits for a node to be laid out on screen; on failure, reports its bounds and the screen. */
    private fun awaitDisplayed(description: String) {
        val node = compose.onNodeWithContentDescription(description)
        val shown =
            try {
                compose.waitUntil(5_000) { runCatching { node.assertIsDisplayed() }.isSuccess }
                true
            } catch (_: ComposeTimeoutException) {
                false
            }
        if (!shown) {
            val bounds =
                try {
                    node.fetchSemanticsNode().boundsInWindow
                } catch (error: AssertionError) {
                    error.message
                }
            throw AssertionError("$description is not displayed (bounds $bounds). Screen: ${screenSummary()}")
        }
    }

    /** Texts and descriptions on screen, so a CI failure says what was showing. */
    private fun screenSummary(): String =
        try {
            "last tap: $lastTapped; window: ${compose.onAllNodes(isRoot()).fetchSemanticsNodes().map { it.size }}; " +
                compose
                    .onAllNodes(SemanticsMatcher("any node") { true })
                    .fetchSemanticsNodes()
                    .flatMap { node ->
                        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                        texts.map { it.text } + node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }.filter { it.isNotBlank() }
                    .map { it.take(24) }
                    .distinct()
                    .take(30)
                    .joinToString(", ")
        } catch (error: IllegalStateException) {
            "unavailable (${error.message})"
        }

    private fun appears(
        text: String,
        timeoutMs: Long,
    ): Boolean {
        fun present() = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        if (timeoutMs <= 0) return present()
        return try {
            compose.waitUntil(timeoutMs) { present() }
            true
        } catch (_: ComposeTimeoutException) {
            false
        }
    }

    private fun tapCanvas(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val view = requireNotNull(findCanvas(activity.window.decorView))
            val time = SystemClock.uptimeMillis()
            dispatch(view, time, time, MotionEvent.ACTION_DOWN, view.width * 0.5f, view.height * 0.5f)
            dispatch(view, time, time + 40, MotionEvent.ACTION_UP, view.width * 0.5f, view.height * 0.5f)
        }
        compose.waitForIdle()
    }

    private fun inspectFocusMode(
        scenario: ActivityScenario<MainActivity>,
        expectedPixels: IntArray,
    ) {
        var originalView: ArtFlowCanvasView? = null
        var originalHeight = 0
        scenario.onActivity {
            originalView = findCanvas(it.window.decorView)
            originalHeight = requireNotNull(originalView).height
        }
        val undoDepth = runBlocking(Dispatchers.Main) { canvas.undoDepth }
        captureWorkspace("studio-workspace.png")
        openWorkspace("Full screen")
        awaitDisplayed("Exit focus mode")
        compose.onNodeWithText("Gallery").assertDoesNotExist()
        compose.waitForIdle()
        scenario.onActivity {
            val focusedView = findCanvas(it.window.decorView)
            assertSame("Focus must not recreate the GL canvas", originalView, focusedView)
            assertTrue(requireNotNull(focusedView).height > originalHeight)
        }
        captureWorkspace("studio-focus.png")
        assertArrayEquals(expectedPixels, pixels())
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Gallery").assertIsDisplayed()
        compose.onNodeWithText("Save changes?").assertDoesNotExist()
        openWorkspace("Full screen")
        compose.onNodeWithContentDescription("Exit focus mode").performClick()
        compose.onNodeWithText("Gallery").assertIsDisplayed()
        assertArrayEquals(expectedPixels, pixels())
        assertEquals(undoDepth, runBlocking(Dispatchers.Main) { canvas.undoDepth })
        assertTrue(runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() })
    }

    private fun captureWorkspace(name: String) = TestEvidence.screenshot(name)

    private fun createArtwork(name: String) {
        awaitGallery()
        compose.onNodeWithText("New artwork", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Name").performTextInput(name)
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithText("Width").performTextReplacement("64")
        compose.onNodeWithText("Height").performTextReplacement("64")
        compose.onNodeWithText("Dpi").performTextReplacement("72")
        compose.onNodeWithText("Create").performClick()
    }

    private fun awaitGallery() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("New artwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitCanvas(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(15_000) {
            var ready = false
            scenario.onActivity { activity ->
                val view = findCanvas(activity.window.decorView)
                ready = view != null && view.width > 0 && view.height > 0
            }
            ready
        }
        compose.waitForIdle()
    }

    private fun pixels(): IntArray = runBlocking(Dispatchers.Main) { requireNotNull(canvas.compositeBuffer()).pixels.copyOf() }

    private fun paint(
        scenario: ActivityScenario<MainActivity>,
        fraction: Float,
    ) {
        scenario.onActivity { activity ->
            val view = requireNotNull(findCanvas(activity.window.decorView))
            val down = SystemClock.uptimeMillis()
            val x = view.width * fraction
            val y = view.height * 0.5f
            dispatch(view, down, down, MotionEvent.ACTION_DOWN, x, y)
            dispatch(view, down, down + 20, MotionEvent.ACTION_MOVE, x + 3f, y)
            dispatch(view, down, down + 40, MotionEvent.ACTION_UP, x + 6f, y)
        }
        compose.waitForIdle()
    }

    private fun dispatch(
        view: View,
        down: Long,
        time: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(down, time, action, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)
        try {
            assertTrue(view.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun findCanvas(view: View): ArtFlowCanvasView? {
        if (view is ArtFlowCanvasView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findCanvas(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}

/** Where each workspace lives in the tabbed Actions panel: tab title to item label. */
private val ACTIONS_LOCATIONS =
    mapOf(
        "Guides" to ("Canvas" to "Drawing Guide"),
        "Animation" to ("Canvas" to "Animation Assist"),
        "Canvas" to ("Canvas" to "Crop & Resize"),
        "Reference" to ("Canvas" to "Reference"),
        "Add text" to ("Add" to "Add text"),
        "Full screen" to ("Prefs" to "Full screen"),
    )
