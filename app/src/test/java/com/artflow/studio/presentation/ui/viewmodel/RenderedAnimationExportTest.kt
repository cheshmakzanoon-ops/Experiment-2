package com.artflow.studio.presentation.ui.viewmodel

import android.content.Context
import com.artflow.studio.core.export.ExportArea
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import javax.imageio.ImageIO

/** The production editor export route, including a real atomic file write and independent GIF decoding. */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderedAnimationExportTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private lateinit var repository: CanvasRepositoryImpl
    private lateinit var exporter: ArtworkExporter

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.root)
        val storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
        exporter = ArtworkExporter(context, storage)
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    private suspend fun frame(
        color: Int,
        position: Int,
        first: Boolean = false,
    ) {
        if (first) repository.createCanvas(8, 6, 72) else repository.addFrame(false)
        val pixels = PixelBuffer(8, 6)
        pixels.pixels[position] = color
        assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), pixels, "Draw animation fixture"))
        repository.setFrameDuration(repository.activeFrameIndex(), (repository.activeFrameIndex() + 1) * 100)
    }

    @Test
    fun gifMatchesTheVisiblePingPongCycleWithMultiplePinsAndHolds() =
        runTest {
            val red = 0xFFFF0000.toInt()
            val green = 0xFF00FF00.toInt()
            val blue = 0xFF0000FF.toInt()
            val purple = 0xFF800080.toInt()
            val yellow = 0xFFFFFF00.toInt()
            val cyan = 0xFF00FFFF.toInt()
            val white = -1
            frame(red, 0, first = true)
            frame(green, 0)
            frame(blue, 1)
            frame(purple, 1)
            frame(yellow, 1)
            frame(cyan, 1)
            frame(white, 2)
            val ids = repository.frames().map { it.id }
            repository.updateAnimationSettings(
                repository.timeline.value.settings.copy(
                    backgroundFrameIds = setOf(ids[0], ids[1]),
                    foregroundFrameIds = setOf(ids[6]),
                    hiddenFrameIds = setOf(ids[3]),
                    pingPong = true,
                    playbackRangeStart = 2,
                    playbackRangeEnd = 5,
                ),
            )
            val before = repository.timeline.value
            val result =
                renderedExport(
                    repository,
                    exporter,
                    1L,
                    "Pinned bounce",
                    ExportOptions(format = ExportFormat.GIF, area = ExportArea.ALL_FRAMES),
                ).getOrThrow()
            assertEquals(4, result.frameCount)
            assertEquals(before, repository.timeline.value)
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            ImageIO.createImageInputStream(File(result.filePath)).use { input ->
                try {
                    reader.input = input
                    assertEquals(4, reader.getNumImages(true))
                    val expected = listOf(blue, yellow, cyan, yellow)
                    val delays = listOf(300, 500, 600, 500)
                    expected.forEachIndexed { index, color ->
                        val image = reader.read(index)
                        assertEquals(green, image.getRGB(0, 0))
                        assertEquals(color, image.getRGB(1, 0))
                        assertEquals(white, image.getRGB(2, 0))
                        val root = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
                        var node = root.firstChild
                        while (node.nodeName != "GraphicControlExtension") node = node.nextSibling
                        assertEquals(
                            delays[index],
                            node.attributes
                                .getNamedItem("delayTime")
                                .nodeValue
                                .toInt() * 10,
                        )
                    }
                } finally {
                    reader.dispose()
                }
            }
        }

    @Test
    fun animationSnapshotsRenderOnlySelectedSourcesWhileDocumentSnapshotsKeepEveryFrame() =
        runTest {
            val green = 0xFF00FF00.toInt()
            val blue = 0xFF0000FF.toInt()
            val yellow = 0xFFFFFF00.toInt()
            val cyan = 0xFF00FFFF.toInt()
            frame(0xFFFF0000.toInt(), 0, first = true)
            frame(green, 0)
            frame(0xFF800080.toInt(), 1)
            frame(0xFF800080.toInt(), 1)
            frame(blue, 1)
            frame(0xFF800080.toInt(), 1)
            frame(yellow, 1)
            frame(cyan, 1)
            frame(0xFF800080.toInt(), 1)
            frame(-1, 2)
            val ids = repository.frames().map { it.id }
            repository.updateAnimationSettings(
                repository.timeline.value.settings.copy(
                    backgroundFrameIds = setOf(ids[0], ids[1]),
                    foregroundFrameIds = setOf(ids[9]),
                    hiddenFrameIds = setOf(ids[5]),
                    playbackRangeStart = 4,
                    playbackRangeEnd = 7,
                    pingPong = true,
                ),
            )
            val animation = repository.exportSnapshot(true, false, false, animationPlayback = true)
            assertEquals("Only three source buffers are budgeted and rendered", 3, animation.frames.size)
            assertEquals(listOf(ids[4], ids[6], ids[7]), animation.frameIds)
            assertEquals(listOf(500, 700, 800), animation.delaysMs)
            assertEquals(listOf(0, 1, 2, 1), animation.frameOrder)
            listOf(blue, yellow, cyan).forEachIndexed { index, color ->
                assertEquals(listOf(green, color, -1), animation.frames[index].pixels.take(3))
            }
            val document = repository.exportSnapshot(true, false, false)
            assertEquals("An all-page document keeps the complete source timeline", 10, document.frames.size)
            assertEquals(ids, document.frameIds)
            assertEquals((0..9).toList(), document.frameOrder)
        }

    @Test
    fun currentFrameExportStillIncludesASelectedHiddenDrawing() =
        runTest {
            frame(0xFF0000FF.toInt(), 0, first = true)
            val id = repository.frames().single().id
            repository.updateAnimationSettings(
                repository.timeline.value.settings
                    .copy(hiddenFrameIds = setOf(id)),
            )
            val result =
                renderedExport(
                    repository,
                    exporter,
                    1L,
                    "Current drawing",
                    ExportOptions(format = ExportFormat.GIF, area = ExportArea.CURRENT_FRAME, gifLoop = false),
                ).getOrThrow()
            assertEquals(1, result.frameCount)
            assertEquals(0xFF0000FF.toInt(), ImageIO.read(File(result.filePath)).getRGB(0, 0))
            assertFalse(File(result.filePath).readBytes().toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        }

    @Test
    fun anAllHiddenAnimationFailsBeforeWritingAnOutput() =
        runTest {
            frame(0xFFFF0000.toInt(), 0, first = true)
            frame(0xFF00FF00.toInt(), 0)
            repository.updateAnimationSettings(
                repository.timeline.value.settings
                    .copy(hiddenFrameIds = repository.frames().map { it.id }.toSet()),
            )
            val failure =
                runCatching {
                    renderedExport(repository, exporter, 1L, "Hidden", ExportOptions(format = ExportFormat.GIF))
                }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("No visible animated frames"))
            assertFalse(File(temporary.root, "exports").exists())
        }

    @Test
    fun oneShotExportsForwardOnceAndAllowsAnExplicitFileLoopOverride() =
        runTest {
            frame(0xFFFF0000.toInt(), 0, first = true)
            frame(0xFF00FF00.toInt(), 0)
            frame(0xFF0000FF.toInt(), 0)
            repository.updateAnimationSettings(
                repository.timeline.value.settings
                    .copy(loop = false, pingPong = false),
            )
            for (repeat in listOf(false, true)) {
                val result =
                    renderedExport(
                        repository,
                        exporter,
                        1L,
                        "One shot $repeat",
                        ExportOptions(format = ExportFormat.GIF, gifLoop = repeat),
                    ).getOrThrow()
                assertEquals(3, result.frameCount)
                val bytes = File(result.filePath).readBytes()
                assertEquals(repeat, bytes.toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
            }
        }

    @Test
    fun aFiniteBounceFileIncludesTheReturnLegAndFinalFirstFrameHold() =
        runTest {
            val red = 0xFFFF0000.toInt()
            val green = 0xFF00FF00.toInt()
            val blue = 0xFF0000FF.toInt()
            frame(red, 0, first = true)
            frame(green, 0)
            frame(blue, 0)
            repository.updateAnimationSettings(
                repository.timeline.value.settings
                    .copy(loop = false, pingPong = true),
            )
            val result =
                renderedExport(
                    repository,
                    exporter,
                    1L,
                    "Finite bounce",
                    ExportOptions(format = ExportFormat.GIF, gifLoop = false),
                ).getOrThrow()
            assertEquals(5, result.frameCount)
            assertFalse(File(result.filePath).readBytes().toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            ImageIO.createImageInputStream(File(result.filePath)).use { input ->
                try {
                    reader.input = input
                    val colors = listOf(red, green, blue, green, red)
                    val holds = listOf(100, 200, 300, 200, 100)
                    assertEquals(colors.size, reader.getNumImages(true))
                    colors.forEachIndexed { index, color ->
                        assertEquals(color, reader.read(index).getRGB(0, 0))
                        val root = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
                        var node = root.firstChild
                        while (node.nodeName != "GraphicControlExtension") node = node.nextSibling
                        assertEquals(
                            holds[index],
                            node.attributes
                                .getNamedItem("delayTime")
                                .nodeValue
                                .toInt() * 10,
                        )
                    }
                } finally {
                    reader.dispose()
                }
            }
        }

    @Test
    fun onionFramesExcludeAllPinsAndHiddenDrawings() =
        runTest {
            frame(0xFFFF0000.toInt(), 0, first = true)
            frame(0xFF00FF00.toInt(), 1)
            frame(0xFF0000FF.toInt(), 2)
            val ids = repository.frames().map { it.id }
            repository.updateAnimationSettings(
                repository.timeline.value.settings
                    .copy(backgroundFrameIds = setOf(ids[0]), foregroundFrameIds = setOf(ids[2])),
            )
            val full = repository.compositeFrame(1, transparentBackground = true)!!
            assertTrue(full.pixels.take(3).all { it != 0 })
            val ghost = repository.compositeOnionFrame(1)!!
            assertEquals(listOf(0, 0xFF00FF00.toInt(), 0), ghost.pixels.take(3))
            repository.updateAnimationSettings(
                repository.timeline.value.settings
                    .copy(hiddenFrameIds = setOf(ids[1])),
            )
            assertEquals(null, repository.compositeOnionFrame(1))
        }
}
