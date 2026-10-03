package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.math.abs

/** Frames that recomposite only a dirty area must show exactly what a full composite shows. */
@OptIn(ExperimentalCoroutinesApi::class)
class IncrementalPreviewTest {
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    /** Rendering a crop shifts float positions by whole pixels, so allow one or two levels of rounding. */
    private suspend fun assertMatchesFullComposite(frame: PixelBuffer) {
        val full = requireNotNull(repository.compositePreview())
        for (i in full.pixels.indices) {
            val a = full.pixels[i]
            val b = frame.pixels[i]
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val difference = abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF))
                assertTrue("Pixel $i differs from a full composite", difference <= 2)
            }
        }
    }

    @Test
    fun strokesInProgressAndCommittedRedrawOnlyTheirArea() =
        runTest {
            repository.createCanvas(96, 64, 72)
            val layer = repository.getActiveLayerId()
            assertTrue(repository.setLayerPixels(layer, PixelBuffer.filled(96, 64, 0xFF336699.toInt()), "Fixture"))
            assertNull("The first frame is always complete", requireNotNull(repository.compositePreviewFrame()).dirty)
            val params = BrushParams(size = 6f, spacing = 0.1f)
            val stroke = repository.beginStroke(10f, 10f, 1f, params, layer)
            repository.continueStroke(stroke, 30f, 12f, 1f)
            val growing = requireNotNull(repository.compositePreviewFrame())
            assertNotNull("A stroke in progress redraws only where it reaches", growing.dirty)
            assertMatchesFullComposite(growing.buffer)

            repository.continueStroke(stroke, 50f, 20f, 1f)
            val longer = requireNotNull(repository.compositePreviewFrame())
            val area = requireNotNull(longer.dirty)
            assertTrue("Only the new tail is redrawn", area.left > 10)
            assertMatchesFullComposite(longer.buffer)

            repository.endStroke(stroke)
            val committed = requireNotNull(repository.compositePreviewFrame())
            assertNotNull("Finishing the stroke redraws only its reach", committed.dirty)
            assertMatchesFullComposite(committed.buffer)
        }

    @Test
    fun trackedPixelSessionsRedrawReportedAreas() =
        runTest {
            repository.createCanvas(64, 64, 72)
            val layer = repository.getActiveLayerId()
            requireNotNull(repository.compositePreviewFrame())
            val session = requireNotNull(repository.beginRasterEdit(layer))
            repository.trackPreviewDamage(session)
            assertNull("A tracked session starts with a complete frame", requireNotNull(repository.compositePreviewFrame()).dirty)
            for (y in 10..14) for (x in 20..24) session.buffer.pixels[y * 64 + x] = 0xFFFF0000.toInt()
            repository.markPreviewDamage(session, IntBounds(20, 10, 24, 14))
            val frame = requireNotNull(repository.compositePreviewFrame())
            assertNotNull(frame.dirty)
            assertMatchesFullComposite(frame.buffer)
            repository.cancelRasterEdit(session)
            val cancelled = requireNotNull(repository.compositePreviewFrame())
            assertNull("Cancelling redraws everything the session showed", cancelled.dirty)
            assertMatchesFullComposite(cancelled.buffer)
        }
}
