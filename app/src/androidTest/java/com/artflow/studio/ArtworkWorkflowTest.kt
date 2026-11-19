package com.artflow.studio

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.theme.studioColorScheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

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
            settings.update { it.copy(themeMode = ThemeMode.LIGHT, seenOnboarding = true, stylusOnly = false, autosaveEnabled = false) }
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
            awaitOrReport("The first pointer stroke did not make the canvas dirty") {
                runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() }
            }
            val painted = pixels()
            assertFalse("Input through the editor must change real artwork", blank.contentEquals(painted))
            inspectReachablePanels(scenario, painted)
            inspectLayerOrdering(blank, painted)
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
        } catch (failure: Throwable) {
            // Preserve the actual editor before closing the Activity or deleting this test's artwork.
            runCatching { TestEvidence.screenshot("artwork-workflow-first-failure.png") }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
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
        selectFineLiner()
        TestEvidence.screenshot("artwork-workflow-brush-studio-ready.png")
        tapDrawingPadThroughWindow()
        compose
            .onNodeWithTag("brush-practice-pad")
            .assertIsDisplayed()
            .performTouchInput { swipeLeft() }
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 test strokes; not part of artwork"))
        assertArrayEquals("Practice must never paint the real document", expectedPixels, pixels())
        assertEquals(depth, runBlocking(Dispatchers.Main) { canvas.undoDepth })
        TestEvidence.screenshot("artwork-workflow-brush-practice.png")
        compose.onNodeWithContentDescription("Cancel brush changes").performClick()
        compose.onNodeWithContentDescription("Smudge").assertIsOn()
        assertArrayEquals(expectedPixels, pixels())
        assertEquals(depth, runBlocking(Dispatchers.Main) { canvas.undoDepth })
        compose.onNodeWithContentDescription("Smudge").performClick()
        selectFineLiner()
        compose.onNodeWithText("Use brush").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Paint").assertIsOn()
        assertArrayEquals(expectedPixels, pixels())
        assertEquals(depth, runBlocking(Dispatchers.Main) { canvas.undoDepth })
    }

    private fun selectFineLiner() {
        compose.onNodeWithText("Search brushes").performTextInput("fine liner")
        compose.onNode(hasText("Fine liner") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithText("Search brushes").assertIsNotFocused()
        // Selecting a preset dismisses the IME and repositions this Dialog on small phones.
        // Compose idleness alone does not wait for that platform window transition: an immediate
        // tab tap can use its old position and hit the search field instead. Keep the real click,
        // but require the Dialog's own insets and layout to be ready before choosing the next tab.
        awaitOrReport("Brush Studio did not finish dismissing the search keyboard") {
            var ready = false
            onView(ViewMatchers.isRoot()).inRoot(isDialog()).check { view, failure ->
                failure?.let { throw it }
                val insets = ViewCompat.getRootWindowInsets(view)
                ready = insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime()) && !view.isLayoutRequested
            }
            ready
        }
        compose.waitForIdle()
    }

    /**
     * Exercise one physical touch through the Android window, retaining the event route and geometry.
     * Compose's direct View dispatch left this visible tab unselected even after IME readiness; its
     * cause is not established. This is one original OS touch, never a fallback or a semantic click.
     */
    private fun tapDrawingPadThroughWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val events = JSONArray()
        val receipt =
            JSONObject()
                .put("schemaVersion", 1)
                .put("route", "UiAutomation.injectInputEvent")
                .put("targetText", "Drawing pad")
                .put("targetRole", "Tab")
                .put("attemptToken", InstrumentationRegistry.getArguments().getString("artflowApkEvidenceToken"))
                .put("events", events)
        var window: Window? = null
        var originalCallback: Window.Callback? = null
        var failure: Throwable? = null
        try {
            val tab = compose.onNode(hasText("Drawing pad") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            tab.assertIsDisplayed().assertIsEnabled().assertIsNotSelected()
            val node = tab.fetchSemanticsNode()
            val root = requireNotNull(node.root as? ViewRootForTest).view
            val origin = IntArray(2)
            val bounds = node.boundsInRoot
            instrumentation.runOnMainSync {
                root.getLocationOnScreen(origin)
                val inWindow = IntArray(2).also(root::getLocationInWindow)
                receipt.put("rootOnScreen", JSONArray(origin.toList()))
                receipt.put("rootInWindow", JSONArray(inWindow.toList()))
                receipt.put("boundsInRoot", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                var parent: View? = root
                while (parent != null && parent !is DialogWindowProvider) parent = parent.parent as? View
                window = requireNotNull(parent as? DialogWindowProvider) { "The tab is not in a Dialog window" }.window
            }
            val expected = listOf(bounds.left + origin[0], bounds.top + origin[1], bounds.right + origin[0], bounds.bottom + origin[1])
            receipt.put("expectedScreenBounds", JSONArray(expected))
            val observed = drawingPadScreenBounds(expected, receipt)
            receipt.put("accessibilityScreenBounds", JSONArray(listOf(observed.left, observed.top, observed.right, observed.bottom)))
            val actual = listOf(observed.left, observed.top, observed.right, observed.bottom)
            assertTrue(
                "Compose and accessibility disagree on the tab's screen bounds: $receipt",
                expected.zip(actual).all { (a, b) -> abs(a - b) <= 1f },
            )
            val x = observed.exactCenterX()
            val y = observed.exactCenterY()
            receipt.put("screenPoint", JSONArray(listOf(x, y)))
            instrumentation.runOnMainSync {
                val delegate = requireNotNull(requireNotNull(window).callback)
                originalCallback = delegate
                requireNotNull(window).callback =
                    object : Window.Callback by delegate {
                        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                            val observation =
                                JSONObject()
                                    .put("action", event.actionMasked)
                                    .put("downTime", event.downTime)
                                    .put("eventTime", event.eventTime)
                                    .put("x", event.x)
                                    .put("y", event.y)
                                    .put("rawX", event.rawX)
                                    .put("rawY", event.rawY)
                            events.put(observation)
                            return delegate.dispatchTouchEvent(event).also { observation.put("handled", it) }
                        }
                    }
            }
            val downTime = SystemClock.uptimeMillis()
            val down = injectScreenTouch(MotionEvent.ACTION_DOWN, downTime, x, y)
            receipt.put("downAccepted", down)
            val up = injectScreenTouch(MotionEvent.ACTION_UP, downTime, x, y)
            receipt.put("upAccepted", up)
            assertTrue("Android rejected the Drawing pad DOWN event: $receipt", down)
            assertTrue("Android rejected the Drawing pad UP event: $receipt", up)
            compose.waitForIdle()
            receipt.put("selectedAfter", tab.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
            assertEquals(
                "The Dialog must receive exactly this original DOWN/UP gesture: $receipt",
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP),
                (0 until events.length()).map { events.getJSONObject(it).getInt("action") },
            )
            tab.assertIsSelected()
        } catch (error: Throwable) {
            failure = error
            receipt.put("failure", error.toString())
            throw error
        } finally {
            val restoreFailure =
                runCatching { instrumentation.runOnMainSync { originalCallback?.let { requireNotNull(window).callback = it } } }
                    .exceptionOrNull()
            restoreFailure?.let { receipt.put("callbackRestoreFailure", it.toString()) }
            val receiptFailure =
                runCatching { File(TestEvidence.directory(), "artwork-workflow-drawing-pad-touch.json").writeText(receipt.toString(2)) }
                    .exceptionOrNull()
            val originalFailure = failure
            val additional = listOfNotNull(restoreFailure, receiptFailure)
            if (originalFailure != null) {
                additional.forEach(originalFailure::addSuppressed)
            } else if (additional.isNotEmpty()) {
                additional.drop(1).forEach(additional.first()::addSuppressed)
                throw additional.first()
            }
        }
    }

    private fun drawingPadScreenBounds(
        expected: List<Float>,
        receipt: JSONObject,
    ): Rect {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        var labels: List<AccessibilityNodeInfo> = emptyList()
        val targets = mutableListOf<AccessibilityNodeInfo>()
        val observations = JSONArray()
        receipt.put("accessibilityCandidates", observations)
        try {
            receipt.put("accessibilityRoot", accessibilityObservation(root))
            labels = drawingPadLabels(root, receipt)
            labels.forEach { label ->
                // Compose accessibility exposes unmerged text separately from the parent's OnClick.
                // Resolve that actual clickable ancestor, never invoke the label's semantic action.
                val target = nearestClickableAncestor(label, instrumentation.targetContext.packageName)
                val observation =
                    accessibilityObservation(label).put("labelClickable", label.isClickable).put(
                        "hasClickableAncestor",
                        target != null,
                    )
                observations.put(observation)
                if (target != null) {
                    val bounds = Rect().also(target::getBoundsInScreen)
                    val edges = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    val matches = expected.zip(edges).all { (a, b) -> abs(a - b) <= 1f }
                    observation.put("ancestor", accessibilityObservation(target))
                    observation.put("ancestorScreenBounds", JSONArray(edges)).put("matchesExpectedTab", matches)
                    if (matches && targets.none { it == target }) targets.add(target) else target.recycle()
                }
            }
            receipt.put("matchingAccessibilityTargets", targets.size)
            assertEquals("Expected one unambiguous clickable Drawing pad Tab at the observed bounds: $receipt", 1, targets.size)
            val target = targets.single()
            return Rect().also(target::getBoundsInScreen).also { assertFalse("Drawing pad has empty screen bounds", it.isEmpty) }
        } finally {
            targets.forEach { it.recycle() }
            labels.forEach { it.recycle() }
            root.recycle()
        }
    }

    private fun drawingPadLabels(
        root: AccessibilityNodeInfo,
        receipt: JSONObject,
    ): List<AccessibilityNodeInfo> {
        val observations = JSONArray()
        receipt.put("accessibilityTraversal", observations)
        val labels = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
        pending.addLast(AccessibilityNodeInfo.obtain(root) to "root")
        try {
            // Compose's provider implements node creation, but not findAccessibilityNodeInfosByText.
            // Walk its actual child nodes once; retain the full observed tree, including rejected labels.
            while (pending.isNotEmpty()) {
                val (node, path) = pending.removeFirst()
                try {
                    val missingChildren = JSONArray()
                    observations.put(accessibilityObservation(node).put("path", path).put("missingChildIndices", missingChildren))
                    if (node.text?.toString() == "Drawing pad") labels.add(AccessibilityNodeInfo.obtain(node))
                    repeat(node.childCount) { index ->
                        val child = node.getChild(index)
                        if (child == null) missingChildren.put(index) else pending.addLast(child to "$path/$index")
                    }
                } finally {
                    node.recycle()
                }
            }
            return labels
        } catch (failure: Throwable) {
            labels.forEach { it.recycle() }
            throw failure
        } finally {
            pending.forEach { (node, _) -> node.recycle() }
        }
    }

    private fun accessibilityObservation(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect().also(node::getBoundsInScreen)
        return JSONObject()
            .put("windowId", node.windowId)
            .put("packageName", node.packageName?.toString() ?: JSONObject.NULL)
            .put("className", node.className?.toString() ?: JSONObject.NULL)
            .put("text", node.text?.toString() ?: JSONObject.NULL)
            .put("contentDescription", node.contentDescription?.toString() ?: JSONObject.NULL)
            .put("screenBounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            .put("visibleToUser", node.isVisibleToUser)
            .put("enabled", node.isEnabled)
            .put("clickable", node.isClickable)
            .put("selected", node.isSelected)
            .put("childCount", node.childCount)
    }

    /** Returns an owned node copy; the caller must recycle it after checking identity and bounds. */
    private fun nearestClickableAncestor(
        label: AccessibilityNodeInfo,
        packageName: String,
    ): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(label)
        while (node != null) {
            if (node.isVisibleToUser && node.isClickable && node.packageName?.toString() == packageName) return node
            val parent =
                try {
                    node.parent
                } finally {
                    node.recycle()
                }
            node = parent
        }
        return null
    }

    private fun injectScreenTouch(
        action: Int,
        downTime: Long,
        x: Float,
        y: Float,
    ): Boolean {
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        val coordinates =
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                size = 1f
            }
        val event =
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                1,
                arrayOf(properties),
                arrayOf(coordinates),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        return try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
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

    private fun inspectLayerOrdering(
        blankPixels: IntArray,
        expectedPixels: IntArray,
    ) {
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
        awaitLayerThumbnail(blankPixels, expectedPixels)
        TestEvidence.screenshot("studio-layers.png")
        compose.onNodeWithContentDescription("Close Layers").performClick()
        repeat(3) { compose.onNodeWithContentDescription("Undo").performClick() }
        compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) { canvas.undoDepth == depth } }
        assertEquals(before, runBlocking(Dispatchers.Main) { canvas.getAllLayers().map { it.id } })
        assertArrayEquals(expectedPixels, pixels())
    }

    private fun awaitLayerThumbnail(
        blankPixels: IntArray,
        expectedPixels: IntArray,
    ) {
        // compositeBuffer includes canvas paper. Compare against the original blank so neither
        // that paper nor the current default brush color is mistaken for the painted mark.
        val changedColors = mutableMapOf<Int, Int>()
        expectedPixels.forEachIndexed { index, color ->
            if (color != blankPixels[index]) changedColors[color] = (changedColors[color] ?: 0) + 1
        }
        val stroke = requireNotNull(changedColors.maxByOrNull { it.value }) { "The fixture must contain a painted mark" }.key
        val preferences = runBlocking { settings.settings.first() }
        val paper = studioColorScheme(preferences.accent, false, preferences.highContrast).surface.toArgb()
        val row = compose.onNode(hasText("Background") and hasClickAction())
        val label = compose.onNodeWithText("Background", useUnmergedTree = true)
        var geometry = "No captured Background row"
        var observed = emptyMap<Int, Int>()
        try {
            compose.waitUntil(15_000) {
                val rowBounds = row.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val labelBounds = label.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val pixels = row.captureToImage().toPixelMap()
                // LayerRow has 6dp inner padding, a 44dp preview and an 8dp gap before its label.
                // Infer scale from the live semantic bounds; sample only inside that preview,
                // excluding its border, surrounding row, text and all trailing action icons.
                val unit = (labelBounds.left - rowBounds.left) / (6f + 44f + 8f)
                val scaleX = pixels.width / rowBounds.width
                val scaleY = pixels.height / rowBounds.height
                val top = (rowBounds.height - 44f * unit) / 2f
                val left = ceil(8f * unit * scaleX).toInt().coerceIn(0, pixels.width)
                val right = floor(48f * unit * scaleX).toInt().coerceIn(0, pixels.width)
                val firstRow = ceil((top + 2f * unit) * scaleY).toInt().coerceIn(0, pixels.height)
                val lastRow = floor((top + 42f * unit) * scaleY).toInt().coerceIn(0, pixels.height)
                val counts = mutableMapOf<Int, Int>()
                for (y in firstRow until lastRow) {
                    for (x in left until right) {
                        val color = pixels[x, y].toArgb()
                        counts[color] = (counts[color] ?: 0) + 1
                    }
                }
                observed = counts
                val inkPixels = counts.filterKeys { closeColor(it, stroke) }.values.sum()
                val paperPixels = counts.filterKeys { closeColor(it, paper) }.values.sum()
                geometry =
                    "row=$rowBounds, label=$labelBounds, image=${pixels.width}x${pixels.height}, " +
                    "preview=[$left,$firstRow..$right,$lastRow], strokePixels=$inkPixels, paperPixels=$paperPixels"
                inkPixels >= 4 && paperPixels >= 12
            }
        } catch (timeout: ComposeTimeoutException) {
            TestEvidence.screenshot("studio-layers-thumbnail-timeout.png")
            val common = observed.entries.sortedByDescending { it.value }.take(6)
            throw AssertionError(
                "Background thumbnail needs rendered stroke ${stroke.toUInt().toString(16)} on paper " +
                    "${paper.toUInt().toString(16)}; $geometry, colors=$common",
                timeout,
            )
        }
    }

    private fun closeColor(
        actual: Int,
        expected: Int,
    ): Boolean = (0..3).all { channel -> abs((actual ushr (channel * 8) and 255) - (expected ushr (channel * 8) and 255)) <= 2 }

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

    /** The activity's Back callbacks, newest last, and whether each is enabled. */
    private fun backCallbacks(activity: ComponentActivity): String =
        try {
            val field = OnBackPressedDispatcher::class.java.getDeclaredField("onBackPressedCallbacks")
            field.isAccessible = true
            (field.get(activity.onBackPressedDispatcher) as Iterable<*>).joinToString { callback ->
                val name = callback?.javaClass?.name?.substringAfterLast('.')
                "$name=${(callback as? OnBackPressedCallback)?.isEnabled}"
            }
        } catch (error: ReflectiveOperationException) {
            "unavailable (${error.message})"
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
    private fun awaitDisplayed(
        description: String,
        node: SemanticsNodeInteraction = compose.onNodeWithContentDescription(description),
    ) {
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
        captureWorkspace("artwork-workflow-focus.png")
        assertArrayEquals(expectedPixels, pixels())
        scenario.onActivity {
            lastTapped = "system Back; callbacks ${backCallbacks(it)}"
            it.onBackPressedDispatcher.onBackPressed()
        }
        awaitDisplayed("Gallery", compose.onNodeWithText("Gallery"))
        compose.onNodeWithText("Save changes?").assertDoesNotExist()
        openWorkspace("Full screen")
        awaitDisplayed("Exit focus mode")
        val exit = compose.onNodeWithContentDescription("Exit focus mode")
        lastTapped = "Exit focus mode at ${exit.fetchSemanticsNode().boundsInWindow}"
        exit.performClick()
        awaitDisplayed("Gallery", compose.onNodeWithText("Gallery"))
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
                // Laid-out SurfaceViews can still be waiting for their first GL frame. Input is
                // intentionally blocked until the artwork is visible, so only then send DOWN.
                ready = view != null && view.width > 0 && view.height > 0 && view.isPreviewAvailable
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
