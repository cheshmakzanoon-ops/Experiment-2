package com.artflow.studio.data.repository.canvas

import android.content.Context
import com.artflow.studio.core.canvas.LayerTransform
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PhysicalPaintMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Complete-state contracts for the document: the same operation must treat colour, masks, physical
 * paint, group structure and editable text as one unit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private val testDispatcher = UnconfinedTestDispatcher()
    private val projectId = 77L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = ProjectStorage(context, testDispatcher)
        repository = CanvasRepositoryImpl(storage)
    }

    @After
    fun tearDown() {
        if (::repository.isInitialized) repository.dispose()
        Dispatchers.resetMain()
    }

    private val watercolor =
        BrushParams(size = 12f, opacity = 1f, physicalMode = PhysicalPaintMode.WATERCOLOR, waterAmount = 0.9f)

    private fun paintWatercolor(
        x0: Float = 12f,
        x1: Float = 28f,
        y: Float = 20f,
    ) {
        repository.setStrokeColor(0xFF2060CC.toInt())
        val id = repository.beginStroke(x0, y, 1f, watercolor, repository.getActiveLayerId(), timestamp = 0L)
        assertTrue(id != 0L)
        repository.continueStroke(id, x1, y, 1f, timestamp = 16L)
        repository.endStroke(id)
    }

    /** Test-only view of the live physical planes: the sum of wetness and the count of allocated tiles. */
    private fun physical(): Pair<Float, Int> {
        val frames = field(repository, "frameList") as List<*>
        var wetness = 0f
        var tiles = 0
        frames.forEach { frame ->
            (field(frame!!, "layers") as List<*>).forEach { layer ->
                val plane = field(layer!!, "physicalPlane") as com.artflow.studio.core.render.PhysicalPaintPlane? ?: return@forEach
                tiles += plane.allocatedTileCount()
                for (y in 0 until plane.height) for (x in 0 until plane.width) wetness += plane.wetnessAt(x, y)
            }
        }
        return wetness to tiles
    }

    private fun field(
        target: Any,
        name: String,
    ): Any? =
        generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .firstNotNullOf { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .also { it.isAccessible = true }
            .get(target)

    @Test
    fun diffusionAfterACaptureDoesNotRewriteTheCapturedRevision() =
        runTest {
            repository.createCanvas(64, 64, 72)
            paintWatercolor()
            assertTrue(repository.hasActiveWetPaint())
            val captured = physical().first
            assertTrue(captured > 0f)

            // addLayer pushes an undo entry that captures the wet plane.
            repository.addLayer("Above")
            repeat(5) { repository.stepPhysicalDiffusion(evaporationRate = 0.3f) }
            assertTrue("Diffusion must have changed the live plane", physical().first != captured)

            assertTrue(repository.undo())
            assertEquals(
                "Undo must restore the exact captured physical revision",
                captured,
                physical().first,
                0.0001f,
            )
        }

    @Test
    fun layerTransformMovesPhysicalPaintWithTheColour() =
        runTest {
            repository.createCanvas(64, 64, 72)
            paintWatercolor()
            val layerId = repository.getActiveLayerId()
            val before = physical()
            assertTrue(before.first > 0f)
            val moved =
                repository.transformLayer(
                    layerId,
                    LayerTransform.Parameters(translationX = 30f),
                    repository.contentRevision,
                )
            assertTrue(moved)
            val plane = livePlane()
            assertEquals(0f, plane.wetnessAt(20, 20), 0.0001f)
            assertTrue("Wetness must follow the paint to its new position", plane.wetnessAt(50, 20) > 0.1f)
        }

    @Test
    fun canvasCropAndRotationResizeThePhysicalPlane() =
        runTest {
            repository.createCanvas(64, 64, 72)
            paintWatercolor()
            assertTrue(repository.cropCanvas(com.artflow.studio.core.pixels.IntBounds(0, 0, 31, 63)))
            val cropped = livePlane()
            assertEquals(32, cropped.width)
            assertEquals(64, cropped.height)
            assertTrue(cropped.wetnessAt(20, 20) > 0.1f)

            assertTrue(repository.rotateCanvas(90))
            val rotated = livePlane()
            assertEquals(64, rotated.width)
            assertEquals(32, rotated.height)
            assertTrue("A clockwise quarter turn sends (20,20) to (43,20)", rotated.wetnessAt(43, 20) > 0.1f)
        }

    @Test
    fun erasingTheColourErasesThePhysicalStateBeneathIt() =
        runTest {
            repository.createCanvas(64, 64, 72)
            paintWatercolor()
            assertTrue(physical().second > 0)
            val layerId = repository.getActiveLayerId()
            assertTrue(repository.applyRasterEdit(layerId, "Clear layer") { it.clear() })
            assertEquals("No physical state may outlive its colour", 0, physical().second)
            assertFalse(repository.hasActiveWetPaint())
        }

    @Test
    fun cutRemovesPhysicalStateFromTheSourceLayer() =
        runTest {
            repository.createCanvas(64, 64, 72)
            paintWatercolor()
            assertTrue(physical().second > 0)
            repository.cutSelection()
            assertEquals(0, physical().second)
        }

    @Test
    fun pastedFrameKeepsGroupStructureAndEditableText() =
        runTest {
            repository.createCanvas(64, 64, 72)
            val child = repository.addLayer("Child")
            val group = checkNotNull(repository.groupLayers(listOf(child.id)))
            val text =
                TextLayerContent(
                    text = "Hello",
                    style = com.artflow.studio.core.text.TextLayout.TextStyle(),
                    color = 0xFF000000.toInt(),
                    x = 4f,
                    y = 4f,
                )
            assertNotNull(repository.addTextLayer(text, PixelBuffer(64, 64)))

            assertTrue(repository.copyFrame(0))
            assertTrue(repository.pasteFrame(1))
            val pasted = repository.frames()[1].layers
            val ids = pasted.map { it.id }.toSet()
            val original = repository.frames()[0].layers.map { it.id }.toSet()
            assertTrue("Pasted layers need fresh identities", ids.intersect(original).isEmpty())
            val pastedChild = pasted.first { it.name == "Child" }
            val pastedGroup = pasted.first { it.isGroup }
            assertEquals("A pasted child must live in the pasted group", pastedGroup.id, pastedChild.parentGroupId)
            assertTrue(pastedGroup.id != group)
            assertEquals("Hello", pasted.first { it.textContent != null }.textContent?.text)
        }

    @Test
    fun trimKeepsContentFromEveryFrame() =
        runTest {
            repository.createCanvas(64, 64, 72)
            // Frame 0: content in the top-left corner.
            val first = PixelBuffer(64, 64).also { it.fill(0) }
            for (y in 2..5) for (x in 2..5) first.setUnchecked(x, y, 0xFFFF0000.toInt())
            assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), first, "Fixture"))
            repository.addFrame(duplicateCurrent = false)
            // Frame 1: content in the bottom-right corner.
            val second = PixelBuffer(64, 64)
            for (y in 58..61) for (x in 58..61) second.setUnchecked(x, y, 0xFF00FF00.toInt())
            assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), second, "Fixture"))
            repository.selectFrame(0)

            assertTrue(repository.trimTransparent())
            val size = repository.getCanvasSize()
            assertTrue("Trim must keep content from both frames; got ${size.width}x${size.height}", size.width > 56 && size.height > 56)
            repository.selectFrame(1)
            assertFalse("The second frame's paint must survive the trim", repository.layerPixels(repository.getActiveLayerId())!!.isEmpty())
        }

    @Test
    fun unreadablePrimaryDoesNotHideAHealthyAutosave() =
        runTest {
            repository.loadOrCreate(projectId, 64, 64, 72)
            repository.saveCanvas(projectId)
            repository.addLayer("Only in the autosave")
            repository.autosave(projectId)
            val autosave = storage.autosaveFile(projectId)
            val document = storage.documentFile(projectId)
            document.writeText("{ this is not a document")
            document.setLastModified(autosave.lastModified() + 60_000)

            val reopened = CanvasRepositoryImpl(storage)
            try {
                val state = reopened.loadOrCreate(projectId, 64, 64, 72)
                assertNotNull("Opening must not fail while a healthy autosave exists", state)
                assertTrue(reopened.hasRecovery(projectId))
                assertTrue(reopened.hasUnresolvedRecovery(projectId))
                // The placeholder must not be allowed to overwrite the damaged primary or the autosave.
                assertNull(runCatching { reopened.saveCanvas(projectId) }.getOrNull())
                assertTrue(autosave.exists())

                assertNotNull(reopened.recoverAutosave(projectId))
                assertTrue(reopened.getAllLayers().any { it.name == "Only in the autosave" })
                assertFalse(reopened.hasUnresolvedRecovery(projectId))
            } finally {
                reopened.dispose()
            }
        }

    private fun livePlane(): com.artflow.studio.core.render.PhysicalPaintPlane {
        val frames = field(repository, "frameList") as List<*>
        return frames
            .flatMap { frame -> field(frame!!, "layers") as List<*> }
            .firstNotNullOf { layer -> field(layer!!, "physicalPlane") as com.artflow.studio.core.render.PhysicalPaintPlane? }
    }
}
