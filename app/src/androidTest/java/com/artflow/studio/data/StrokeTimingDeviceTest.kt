package com.artflow.studio.data

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/**
 * Times what sits between a pen sample and the screen while drawing: building the live preview
 * frame after each sample, on a 2048 × 2048 canvas with several painted layers. The numbers are
 * written as test evidence (stroke-timing.txt) and printed by CI.
 */
@RunWith(AndroidJUnit4::class)
class StrokeTimingDeviceTest {
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private val projectId = 9_400_001L

    @Before
    fun setUp() {
        storage = ProjectStorage(ApplicationProvider.getApplicationContext<Context>())
        repository = CanvasRepositoryImpl(storage)
        storage.deleteProjectFiles(projectId)
    }

    @After
    fun cleanUp() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            storage.deleteProjectFiles(projectId)
        }

    @Test
    fun liveStrokeFramesAreTimed() =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(projectId, SIZE, SIZE, DPI)
            repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(SIZE, SIZE, PAPER), "Paper")
            repeat(PAINTED_LAYERS) { index -> repository.setLayerPixels(repository.addLayer("Layer $index").id, stripes(index), "Fixture") }
            val ink = repository.addLayer("Ink").id
            repository.setStrokeColor(INK)
            val report =
                BRUSHES.map { (name, params) ->
                    requireNotNull(repository.compositePreviewFrame())
                    val frames = LongArray(SAMPLES)
                    val stroke = repository.beginStroke(START_X, CENTRE, 0.5f, params, ink, false)
                    for (sample in 0 until SAMPLES) {
                        val x = START_X + sample * STEP
                        val y = CENTRE + sin(sample / WAVE) * AMPLITUDE
                        repository.continueStroke(stroke, x, y, 0.4f + 0.5f * (sample % 20) / 20f)
                        val start = System.nanoTime()
                        requireNotNull(repository.compositePreviewFrame())
                        frames[sample] = System.nanoTime() - start
                    }
                    val commitStart = System.nanoTime()
                    repository.endStroke(stroke)
                    requireNotNull(repository.compositePreviewFrame())
                    val commit = System.nanoTime() - commitStart
                    frames.sort()
                    Timing(name, frames[SAMPLES / 2], frames[SAMPLES * 95 / 100], frames.last(), commit)
                }
            val text = report.joinToString("\n", postfix = "\n") { it.line() }
            File(TestEvidence.directory(), "stroke-timing.txt").writeText(text)
            Log.i("StrokeTiming", text)
            // Lanes differ hugely (one runs without the JIT), so the numbers are evidence, not a gate.
            assertTrue(report.all { it.p50 > 0 })
        }

    private fun stripes(index: Int): PixelBuffer =
        PixelBuffer(SIZE, SIZE).also { buffer ->
            val colour = COLOURS[index % COLOURS.size]
            for (y in 0 until SIZE) {
                if ((y / STRIPE + index) % 2 == 0) buffer.pixels.fill(colour, y * SIZE, y * SIZE + SIZE / 2 + index * STRIPE)
            }
        }

    private class Timing(
        val name: String,
        val p50: Long,
        val p95: Long,
        val max: Long,
        val commit: Long,
    ) {
        fun line(): String =
            "${android.os.Build.VERSION.SDK_INT} $name: frame p50 ${ms(
                p50,
            )} ms, p95 ${ms(p95)} ms, max ${ms(max)} ms; commit ${ms(commit)} ms"

        private fun ms(nanos: Long) = "%.1f".format(nanos / 1_000_000.0)
    }

    private companion object {
        const val SIZE = 2048
        const val DPI = 264
        const val PAINTED_LAYERS = 4
        const val SAMPLES = 80
        const val START_X = 240f
        const val STEP = 12f
        const val CENTRE = 1024f
        const val WAVE = 9f
        const val AMPLITUDE = 260f
        const val STRIPE = 64
        const val PAPER = 0xFFF4EFE6.toInt()
        const val INK = 0xFF203A5C.toInt()
        val COLOURS = intArrayOf(0x80D94F4F.toInt(), 0xFF4F8FD9.toInt(), 0x6040A060, 0xFFE0B040.toInt())
        val BRUSHES =
            listOf(
                "pen 12 px" to BrushParams(size = 12f, spacing = 0.08f),
                "textured 80 px" to BrushParams(size = 80f, textureId = "paper", blendTexture = true, opacity = 0.8f),
                "soft airbrush 300 px" to BrushParams(size = 300f, spacing = 0.05f, opacity = 0.4f, flow = 0.4f, buildUp = true),
            )
    }
}
