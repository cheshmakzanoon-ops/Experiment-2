package com.artflow.studio

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.core.three.MeshPicker
import com.artflow.studio.core.three.OrbitCamera
import com.artflow.studio.core.three.SurfaceHit
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.components.canvas.ModelView
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
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Real editor/modal input and MotionEvents through its attached GL model; no native-app comparison. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ModelEditorUiTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 2)
    val testName = TestName()

    @Inject lateinit var projects: ProjectRepository

    @Inject lateinit var canvas: CanvasRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var exporter: ArtworkExporter

    @Inject lateinit var timelapse: TimelapseRecorder

    @Inject lateinit var storage: ProjectStorage

    private val preferences =
        AppSettings(themeMode = ThemeMode.LIGHT, reduceMotion = true, autosaveEnabled = false, recordTimelapse = false)
    private lateinit var viewModel: CanvasViewModel
    private var previousSettings: AppSettings? = null
    private var projectId = 0L
    private var layer = 0L
    private var eventTime = 0L
    private var downTime = 0L
    private val original = IntArray(WIDTH * HEIGHT) { PAPER }
    private val window get() = compose.onNodeWithTag("model-window")
    private val close get() = compose.onNodeWithContentDescription("Close 3D view")

    @Before fun setUp() {
        hilt.inject()
        previousSettings = settings.current()
        runBlocking {
            settings.update { preferences }
            projectId = projects.saveProject(Project(0L, "Model input lifecycle", "", null, WIDTH, HEIGHT, 72, 1L, 1L))
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
            layer = canvas.getActiveLayerId()
            assertTrue(canvas.setLayerPixels(layer, PixelBuffer(WIDTH, HEIGHT, original.copyOf()), "Model fixture"))
            viewModel.setBrushParams(BrushParams(size = 6f, pressureToSize = 0f, pressureToOpacity = 0f, smoothing = 0f))
            viewModel.setColor(INK)
            viewModel.model.attach(SEAMED_MODEL)
        }
        awaitModel()
        awaitModelPixels(painted = false)
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

    @Test fun modalControlsAboveTheRealModelRemainReachableAndFocusRestoresTheView() {
        runBlocking(Dispatchers.Main) {
            // A populated stack makes the phone panel overlap the model as well as the tablet popover.
            repeat(6) { canvas.addLayer("Routing layer ${it + 1}") }
            assertTrue(canvas.setActiveLayer(layer))
        }
        compose.waitUntil(5_000) { viewModel.layers.value.size == 7 }
        tapAt(modelChip("Paint"))
        modelChip("Paint").assertIsSelected()
        val initialCount = compose.runOnIdle { canvas.getAllLayers().size }
        val before = compose.runOnIdle { canvas.historyMark }
        compose.onNodeWithContentDescription("Layers").performClick()
        val add = compose.onNodeWithContentDescription("Add layer").assertIsDisplayed()
        val shared = add.bounds().intersect(window.bounds())
        assertTrue("The tested modal control must overlap the real model: $shared", shared.width > 4f && shared.height > 4f)
        tapAt(add, shared.center)
        compose.waitUntil(5_000) { viewModel.layers.value.size == initialCount + 1 }
        compose.runOnIdle {
            assertEquals(initialCount + 1, canvas.getAllLayers().size)
            assertEquals(before + 1, canvas.historyMark)
        }
        assertStoredPixels(original)
        window.assertExists()
        TestEvidence.screenshot("model-editor-layers.png")
        compose.onNodeWithContentDescription("Layers").performClick()
        modelChip("Paint").assertIsSelected()
        tapAt(modelChip("Turn"))
        modelChip("Turn").assertIsSelected()
        awaitModelPixels(painted = false)

        val stableHistory = compose.runOnIdle { canvas.historyMark }
        compose.onNodeWithContentDescription("Actions").performClick()
        actionTab("Prefs").performScrollTo().performClick()
        compose.onNodeWithText("Full screen").performScrollTo().performClick()
        window.assertDoesNotExist()
        val exit = compose.onNodeWithContentDescription("Exit focus mode").assertIsDisplayed()
        awaitCanvasArtwork()
        TestEvidence.screenshot("model-editor-focus.png")
        tapAt(exit)
        awaitModel()
        awaitModelPixels(painted = false)
        close.assertIsDisplayed()
        tapAt(close)
        window.assertDoesNotExist()
        openModel()
        awaitModelPixels(painted = false)
        TestEvidence.screenshot("model-editor-restored.png")
        assertStoredPixels(original)
        compose.runOnIdle { assertEquals("Model/focus controls do not edit the document", stableHistory, canvas.historyMark) }
    }

    @Test fun cancelledModelPaintDiscardsEverySeamSegmentAndSuccessfulUpKeepsUndoablePixels() {
        tapAt(modelChip("Paint"))
        val view = modelView()
        val path = seamPath(view)
        val beforeHistory = compose.runOnIdle { canvas.historyMark }
        val beforeUndo = compose.runOnIdle { canvas.undoDepth }
        send(view, MotionEvent.ACTION_DOWN, path.left.point)
        sendBatchedMove(view, historical = path.right.point, current = path.left.point)
        val preview = runBlocking(Dispatchers.Main) { requireNotNull(canvas.compositePreview()).pixels.copyOf() }
        assertSeamPaint(preview)
        assertStoredPixels(original)
        compose.runOnIdle {
            assertEquals(beforeHistory, canvas.historyMark)
            assertEquals(beforeUndo, canvas.undoDepth)
        }
        awaitModelPixels(painted = true, path = path)
        TestEvidence.screenshot("model-editor-provisional.png")
        send(view, MotionEvent.ACTION_CANCEL, path.right.point)
        send(view, MotionEvent.ACTION_MOVE, path.left.point)
        send(view, MotionEvent.ACTION_UP, path.left.point)
        assertCancelled(beforeHistory, beforeUndo)
        awaitModelPixels(painted = false, path = path)
        TestEvidence.screenshot("model-editor-cancelled.png")

        if (Build.VERSION.SDK_INT >= 33) {
            beginAcrossSeam(view, path)
            send(view, MotionEvent.ACTION_UP, path.right.point, flags = MotionEvent.FLAG_CANCELED)
            assertCancelled(beforeHistory, beforeUndo)
        }
        // Removing the real AndroidView must also roll back a partly finished seam gesture.
        beginAcrossSeam(view, path)
        tapAt(close)
        window.assertDoesNotExist()
        assertCancelled(beforeHistory, beforeUndo)
        openModel()
        tapAt(modelChip("Paint"))
        val reopened = modelView()
        val reopenedPath = seamPath(reopened)
        send(reopened, MotionEvent.ACTION_DOWN, reopenedPath.left.point)
        // There is no MOVE on this path: the final UP position must reach the other UV island.
        send(reopened, MotionEvent.ACTION_UP, reopenedPath.right.point)
        val committed = runBlocking(Dispatchers.Main) { requireNotNull(canvas.layerPixels(layer)).pixels.copyOf() }
        assertSeamPaint(committed)
        compose.runOnIdle {
            assertEquals("Seam segments retain their existing individual undo entries", beforeUndo + 2, canvas.undoDepth)
            assertTrue(canvas.undo())
            assertTrue(canvas.undo())
        }
        assertStoredPixels(original)
        compose.runOnIdle {
            assertTrue(canvas.redo())
            assertTrue(canvas.redo())
        }
        assertStoredPixels(committed)
        awaitModelPixels(painted = true, path = reopenedPath)
        File(TestEvidence.directory(), "model-editor-layer.png").writeBytes(
            BitmapPixelBridge.toPngBytes(PixelBuffer(WIDTH, HEIGHT, committed)),
        )
        TestEvidence.screenshot("model-editor-committed.png")
        assertPinchStillNavigatesWithoutPainting(reopened, committed)
    }

    @Test fun materialControlsPaintIndependentMapsAndPresentProvisionalUndoableLighting() {
        tapAt(modelChip("Paint"))
        compose.onNodeWithContentDescription("Model Metallic").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            viewModel.layers.value.any {
                it.id == viewModel.activeLayerId.value &&
                    it.materialChannel == MaterialChannel.METALLIC
            }
        }
        compose.onNodeWithContentDescription("Model Metallic").assertIsSelected()
        val slider = compose.onNodeWithContentDescription("Material paint value").assertIsDisplayed()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        val view = modelView()
        val path = seamPath(view)
        awaitModelPixels(false, path)
        val screen =
            compose.runOnIdle {
                val origin = IntArray(2).also(view::getLocationOnScreen)
                origin[0] +
                    path.left.point.x
                        .roundToInt() to origin[1] +
                    path.left.point.y
                        .roundToInt()
            }
        val before = requireNotNull(TestEvidence.colorsAtScreenPoints(listOf(screen))).single()
        val metalLayer = compose.runOnIdle { canvas.getActiveLayerId() }
        send(view, MotionEvent.ACTION_DOWN, path.left.point)
        awaitModelPoint(view, path.left.point, "model-material-provisional-timeout.png") { actual ->
            (0..16 step 8).any { shift -> abs((actual ushr shift and 255) - (before ushr shift and 255)) >= 8 }
        }
        runBlocking(Dispatchers.Main) {
            assertTrue(requireNotNull(canvas.compositeMaterial(MaterialChannel.METALLIC, true)).pixels.any { it ushr 24 > 0 })
            assertTrue(requireNotNull(canvas.compositeMaterial(MaterialChannel.METALLIC)).pixels.all { it == 0 })
            assertArrayEquals(original, requireNotNull(canvas.compositeBuffer()).pixels)
        }
        assertStoredPixels(original)
        TestEvidence.screenshot("model-material-provisional.png")
        send(view, MotionEvent.ACTION_CANCEL, path.left.point)
        awaitModelPoint(view, path.left.point, "model-material-cancelled-timeout.png") { closeColor(it, before) }
        TestEvidence.screenshot("model-material-cancelled.png")
        send(view, MotionEvent.ACTION_DOWN, path.left.point)
        send(view, MotionEvent.ACTION_UP, path.left.point)
        val metalPixels =
            runBlocking(Dispatchers.Main) {
                val committed = requireNotNull(canvas.layerPixels(metalLayer)).pixels.copyOf()
                assertTrue(committed.any { it ushr 24 > 0 })
                assertTrue(canvas.undo())
                assertTrue(requireNotNull(canvas.compositeMaterial(MaterialChannel.METALLIC)).pixels.all { it == 0 })
                assertTrue(canvas.redo())
                assertArrayEquals(committed, requireNotNull(canvas.layerPixels(metalLayer)).pixels)
                committed
            }
        compose.onNodeWithContentDescription("Model Roughness").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            viewModel.layers.value.any {
                it.id == viewModel.activeLayerId.value &&
                    it.materialChannel == MaterialChannel.ROUGHNESS
            }
        }
        compose.onNodeWithContentDescription("Model Roughness").assertIsSelected()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        compose.waitUntil(5_000) { viewModel.model.paintValue.value == 0.25f }
        val roughPath = seamPath(modelView())
        send(view, MotionEvent.ACTION_DOWN, roughPath.right.point)
        send(view, MotionEvent.ACTION_UP, roughPath.right.point)
        runBlocking(Dispatchers.Main) {
            assertArrayEquals(metalPixels, requireNotNull(canvas.layerPixels(metalLayer)).pixels)
            val roughPixels = requireNotNull(canvas.compositeMaterial(MaterialChannel.ROUGHNESS))
            assertTrue(roughPixels.pixels.any { it ushr 24 > 200 && (it and 255) == 64 })
            assertArrayEquals(original, requireNotNull(canvas.compositeBuffer()).pixels)
            File(TestEvidence.directory(), "model-material-roughness-layer.png").writeBytes(BitmapPixelBridge.toPngBytes(roughPixels))
        }
        compose.onNodeWithContentDescription("Model Color").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { viewModel.activeLayerId.value == layer }
        assertStoredPixels(original)
        assertModelHeaderVisible()
        TestEvidence.screenshot("model-material-committed.png")
    }

    private fun assertCancelled(
        history: Long,
        undo: Int,
    ) {
        assertStoredPixels(original)
        runBlocking(Dispatchers.Main) {
            assertArrayEquals("Cancellation clears every provisional segment", original, requireNotNull(canvas.compositePreview()).pixels)
            assertEquals(history, canvas.historyMark)
            assertEquals(undo, canvas.undoDepth)
        }
    }

    private fun assertSeamPaint(pixels: IntArray) {
        assertTrue("The left island must contain painted pixels", pixels.indices.any { it % WIDTH < 42 && pixels[it] != original[it] })
        assertTrue("The right island must contain painted pixels", pixels.indices.any { it % WIDTH > 54 && pixels[it] != original[it] })
        for (y in 0 until HEIGHT) {
            for (x in 44..51) assertEquals("A UV seam must not streak through the gap ($x,$y)", PAPER, pixels[y * WIDTH + x])
        }
    }

    private fun assertStoredPixels(expected: IntArray) {
        runBlocking(Dispatchers.Main) { assertArrayEquals(expected, requireNotNull(canvas.layerPixels(layer)).pixels) }
    }

    private fun beginAcrossSeam(
        view: ModelView,
        path: SeamPath,
    ) {
        send(view, MotionEvent.ACTION_DOWN, path.left.point)
        send(view, MotionEvent.ACTION_MOVE, path.right.point)
    }

    private fun send(
        view: ModelView,
        action: Int,
        point: Offset,
        flags: Int = 0,
    ) = sendPointers(view, action, listOf(point), flags = flags)

    private fun sendBatchedMove(
        view: ModelView,
        historical: Offset,
        current: Offset,
    ) {
        compose.runOnIdle {
            eventTime = maxOf(eventTime + 10, SystemClock.uptimeMillis())
            val event =
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    MotionEvent.ACTION_MOVE,
                    historical.x,
                    historical.y,
                    1f,
                    0.1f,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                )
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                eventTime += 10
                event.addBatch(eventTime, current.x, current.y, 1f, 0.1f, 0)
                assertEquals("The right island exists only in the batched sample", 1, event.historySize)
                assertTrue(view.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }

    private fun sendPointers(
        view: ModelView,
        action: Int,
        points: List<Offset>,
        changed: Int = 0,
        flags: Int = 0,
    ) {
        compose.runOnIdle {
            eventTime = maxOf(eventTime + 10, SystemClock.uptimeMillis())
            if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
            val properties =
                Array(points.size) { index ->
                    MotionEvent.PointerProperties().apply {
                        id = index
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
            val coordinates =
                Array(points.size) { index ->
                    val point = points[index]
                    MotionEvent.PointerCoords().apply {
                        x = point.x
                        y = point.y
                        pressure = 1f
                        size = 0.1f
                    }
                }
            val event =
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action or (changed shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    points.size,
                    properties,
                    coordinates,
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    flags,
                )
            try {
                assertTrue("The attached ModelView must consume the real pointer event", view.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }

    private fun awaitModel() {
        compose.waitUntil(15_000) {
            viewModel.model.mesh.value != null &&
                compose.runOnIdle {
                    findModel(compose.activity.findViewById(android.R.id.content))?.let { it.width > 0 && it.height > 0 } == true
                }
        }
        window.assertIsDisplayed()
        close.assertIsDisplayed()
    }

    private fun openModel() {
        compose.onNodeWithContentDescription("Actions").performClick()
        actionTab("Canvas").performScrollTo().performClick()
        compose.onNodeWithText("3D view").performScrollTo().performClick()
        awaitModel()
    }

    private fun modelView(): ModelView =
        compose.runOnIdle { requireNotNull(findModel(compose.activity.findViewById(android.R.id.content))) }

    private fun findModel(view: View): ModelView? {
        if (view is ModelView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findModel(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    /** Uses the actual mesh/view dimensions; the pixel assertions below prove the selected UV regions. */
    private fun seamPath(view: ModelView): SeamPath =
        compose.runOnIdle {
            val mesh = requireNotNull(view.mesh)
            val camera = OrbitCamera()
            val samples =
                (4 until view.width - 4 step 2).mapNotNull { x ->
                    val point = Offset(x.toFloat(), view.height * 0.5f)
                    val ray = camera.ray(point.x, point.y, view.width.toFloat(), view.height.toFloat())
                    MeshPicker.hit(mesh, ray.first, ray.second)?.let { Sample(point, it) }
                }
            val left = requireNotNull(samples.filter { it.hit.u in 0.15f..0.3f }.minByOrNull { abs(it.hit.u - 0.22f) })
            val right = requireNotNull(samples.filter { it.hit.u in 0.7f..0.85f }.minByOrNull { abs(it.hit.u - 0.78f) })
            assertTrue("The injected drag must cross two actual UV islands", left.hit.island != right.hit.island)
            SeamPath(left, right)
        }

    private fun awaitModelPixels(
        painted: Boolean,
        path: SeamPath = seamPath(modelView()),
    ) {
        var observed: List<Int>? = null
        var points = emptyList<Pair<Int, Int>>()
        try {
            compose.waitUntil(20_000) {
                points =
                    compose.runOnIdle {
                        val view = requireNotNull(findModel(compose.activity.findViewById(android.R.id.content)))
                        val origin = IntArray(2).also(view::getLocationOnScreen)
                        listOf(path.left.point, path.right.point, Offset(view.width * 0.1f, view.height * 0.1f)).map { point ->
                            origin[0] + point.x.roundToInt() to origin[1] + point.y.roundToInt()
                        }
                    }
                observed = TestEvidence.colorsAtScreenPoints(points)
                val actual = observed
                actual != null && actual.take(2).all { dominant(it, painted) } && isModelBackground(actual.last())
            }
        } catch (timeout: ComposeTimeoutException) {
            val method = testName.methodName.replace(Regex("[A-Z]")) { "-${it.value.lowercase(Locale.ROOT)}" }
            TestEvidence.screenshot("model-editor-$method-render-timeout.png")
            throw AssertionError("Expected ${if (painted) "green paint" else "red artwork"} on the model at $points: $observed", timeout)
        }
        assertModelHeaderVisible()
    }

    /** Screen pixels include SurfaceView's hole, which semantic visibility and node captures can miss. */
    private fun assertModelHeaderVisible() {
        val controls =
            linkedMapOf(
                "title" to modelChip("3D"),
                "Turn" to modelChip("Turn"),
                "Paint" to modelChip("Paint"),
                "Light" to modelChip("Light"),
                "close" to close,
            )
        val root = compose.onRoot().fetchSemanticsNode()
        val rootShift = root.boundsInWindow.topLeft - root.boundsInRoot.topLeft
        val windowShift =
            compose.runOnIdle {
                val decor = compose.activity.window.decorView
                val screen = IntArray(2).also(decor::getLocationOnScreen)
                val window = IntArray(2).also(decor::getLocationInWindow)
                Offset(screen[0] - window[0].toFloat(), screen[1] - window[1].toFloat())
            }
        val areas = controls.mapValues { (_, node) -> node.assertIsDisplayed().bounds().translate(rootShift + windowShift) }
        val samples =
            areas.mapValues { (_, bounds) ->
                (ceil(bounds.top).toInt() until floor(bounds.bottom).toInt()).flatMap { y ->
                    (ceil(bounds.left).toInt() until floor(bounds.right).toInt()).map { x -> x to y }
                }
            }
        assertTrue("Every header control must have a nonempty on-screen area: $areas", samples.values.all { it.size >= 8 })
        val scheme = studioColorScheme(preferences.accent, false, preferences.highContrast)
        val papers = listOf(scheme.surfaceColorAtElevation(6.dp).toArgb(), scheme.secondaryContainer.toArgb())
        val inks = listOf(scheme.onSurface.toArgb(), scheme.onSurfaceVariant.toArgb(), scheme.onSecondaryContainer.toArgb())
        var observed = emptyMap<String, String>()
        try {
            compose.waitUntil(10_000) {
                val captured = TestEvidence.colorsAtScreenPoints(samples.values.flatten()) ?: return@waitUntil false
                var offset = 0
                val readable = mutableMapOf<String, Boolean>()
                observed =
                    samples.mapValues { (name, points) ->
                        val histogram = captured.subList(offset, offset + points.size).groupingBy { it }.eachCount()
                        offset += points.size
                        val paper = histogram.filterKeys { actual -> papers.any { closeColor(actual, it) } }.maxByOrNull { it.value }
                        val ink = histogram.filterKeys { actual -> inks.any { closeColor(actual, it) } }.maxByOrNull { it.value }
                        val contrast = if (paper != null && ink != null) ColorUtils.calculateContrast(ink.key, paper.key) else 0.0
                        readable[name] = (paper?.value ?: 0) >= 4 && (ink?.value ?: 0) >= 4 && contrast >= 4.5
                        "paper=$paper, ink=$ink, contrast=$contrast, common=${histogram.entries.sortedByDescending { it.value }.take(4)}"
                    }
                readable.values.all { it }
            }
        } catch (timeout: ComposeTimeoutException) {
            val method = testName.methodName.replace(Regex("[A-Z]")) { "-${it.value.lowercase(Locale.ROOT)}" }
            TestEvidence.screenshot("model-editor-$method-header-timeout.png")
            throw AssertionError(
                "Model title/mode/close controls need actual opaque paper and readable ink; bounds=$areas, $observed",
                timeout,
            )
        }
    }

    private fun closeColor(
        actual: Int,
        expected: Int,
    ): Boolean = (0..24 step 8).all { shift -> abs((actual ushr shift and 255) - (expected ushr shift and 255)) <= 2 }

    private fun assertPinchStillNavigatesWithoutPainting(
        view: ModelView,
        committed: IntArray,
    ) {
        val probe = compose.runOnIdle { zoomRevealPoint(view) }
        awaitModelPoint(view, probe, "model-editor-navigation-before-timeout.png", ::isModelBackground)
        val history = compose.runOnIdle { canvas.historyMark }
        val centre = Offset(view.width / 2f, view.height / 2f)
        val halfSpan = view.width * 0.15f
        val left = centre - Offset(halfSpan, 0f)
        val right = centre + Offset(halfSpan, 0f)
        send(view, MotionEvent.ACTION_DOWN, left)
        sendPointers(view, MotionEvent.ACTION_POINTER_DOWN, listOf(left, right), changed = 1)
        val zoomed = listOf(centre - Offset(halfSpan * 1.4f, 0f), centre + Offset(halfSpan * 1.4f, 0f))
        sendPointers(view, MotionEvent.ACTION_MOVE, zoomed)
        sendPointers(view, MotionEvent.ACTION_POINTER_UP, zoomed, changed = 1)
        send(view, MotionEvent.ACTION_UP, zoomed.first())
        awaitModelPoint(view, probe, "model-editor-navigation-timeout.png") { dominant(it, painted = false) }
        assertModelHeaderVisible()
        assertStoredPixels(committed)
        compose.runOnIdle { assertEquals("Pinching cannot commit the first finger's provisional mark", history, canvas.historyMark) }
        TestEvidence.screenshot("model-editor-navigation.png")
    }

    private fun zoomRevealPoint(view: ModelView): Offset {
        val mesh = requireNotNull(view.mesh)
        val originalCamera = OrbitCamera()
        val zoomedCamera = originalCamera.zoomed(1.4f)

        fun hit(
            camera: OrbitCamera,
            point: Offset,
        ): SurfaceHit? {
            val ray = camera.ray(point.x, point.y, view.width.toFloat(), view.height.toFloat())
            return MeshPicker.hit(mesh, ray.first, ray.second)
        }
        for (y in 8 until view.height - 8 step 4) {
            for (x in 8 until view.width - 8 step 4) {
                val point = Offset(x.toFloat(), y.toFloat())
                val enlarged = hit(zoomedCamera, point) ?: continue
                val unpainted = enlarged.v in 0.15f..0.3f || enlarged.v in 0.7f..0.85f
                val patch = listOf(Offset.Zero, Offset(-3f, 0f), Offset(3f, 0f), Offset(0f, -3f), Offset(0f, 3f))
                if (unpainted && patch.all { hit(originalCamera, point + it) == null && hit(zoomedCamera, point + it) != null }) {
                    return point
                }
            }
        }
        error("The fixture must expose a previously empty model-view point after a 1.4x pinch")
    }

    private fun awaitModelPoint(
        view: ModelView,
        point: Offset,
        failureImage: String,
        accepts: (Int) -> Boolean,
    ) {
        val screen =
            compose.runOnIdle {
                val origin = IntArray(2).also(view::getLocationOnScreen)
                origin[0] + point.x.roundToInt() to origin[1] + point.y.roundToInt()
            }
        awaitPixel(screen, failureImage, accepts)
    }

    private fun awaitCanvasArtwork() {
        val screen =
            compose.runOnIdle {
                val view = requireNotNull(findCanvas(compose.activity.findViewById(android.R.id.content)))
                val origin = IntArray(2).also(view::getLocationOnScreen)
                val centre = view.canvasToView(WIDTH / 2f, HEIGHT / 2f)
                origin[0] + centre.first.roundToInt() to origin[1] + centre.second.roundToInt()
            }
        awaitPixel(screen, "model-editor-focus-timeout.png") { actual ->
            (0..16 step 8).all { shift -> abs((actual ushr shift and 255) - (PAPER ushr shift and 255)) <= 2 }
        }
    }

    private fun findCanvas(view: View): ArtFlowCanvasView? {
        if (view is ArtFlowCanvasView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun awaitPixel(
        point: Pair<Int, Int>,
        failureImage: String,
        accepts: (Int) -> Boolean,
    ) {
        var observed: Int? = null
        try {
            compose.waitUntil(20_000) {
                observed = TestEvidence.colorsAtScreenPoints(listOf(point))?.single()
                observed?.let(accepts) == true
            }
        } catch (timeout: ComposeTimeoutException) {
            TestEvidence.screenshot(failureImage)
            throw AssertionError("Unexpected presented pixel at $point: $observed", timeout)
        }
    }

    private fun dominant(
        color: Int,
        painted: Boolean,
    ): Boolean {
        val red = color ushr 16 and 255
        val green = color ushr 8 and 255
        val blue = color and 255
        return if (painted) green > red + 35 && green > blue + 35 else red > green + 35 && red > blue + 35
    }

    private fun isModelBackground(color: Int): Boolean = (0..16 step 8).all { shift -> abs((color ushr shift and 255) - 41) <= 2 }

    private fun modelChip(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("model-window")))

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

    private data class Sample(
        val point: Offset,
        val hit: SurfaceHit,
    )

    private data class SeamPath(
        val left: Sample,
        val right: Sample,
    )

    private companion object {
        const val WIDTH = 96
        const val HEIGHT = 64
        val PAPER = 0xFFB04020.toInt()
        val INK = 0xFF20D070.toInt()
        val SEAMED_MODEL =
            """
            v -1 -1 0
            v 0 -1 0
            v 0 1 0
            v -1 1 0
            v 1 -1 0
            v 1 1 0
            vt 0 0
            vt 0.4 0
            vt 0.4 1
            vt 0 1
            vt 0.6 0
            vt 1 0
            vt 1 1
            vt 0.6 1
            f 1/1 2/2 3/3 4/4
            f 2/5 5/6 6/7 3/8
            """.trimIndent()
    }
}
