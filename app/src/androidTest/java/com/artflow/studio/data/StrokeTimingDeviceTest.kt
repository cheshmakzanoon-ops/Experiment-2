package com.artflow.studio.data

import android.content.Context
import android.os.Build
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.sin

/**
 * Measures elapsed repository-call phases on a 2048 x 2048 canvas. Preview calls include their
 * rasterization/compositing work; all durations also include any dispatch, scheduling and lock waits.
 * These are not CPU times or end-to-end input latency: no device input delivery, GL upload/draw or
 * display presentation is measured. Raw ordered samples and summaries are retained as test evidence.
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
            repeat(STRIPED_LAYERS) { index -> repository.setLayerPixels(repository.addLayer("Layer $index").id, stripes(index), "Fixture") }
            val ink = repository.addLayer("Ink").id
            repository.setStrokeColor(INK)
            val report =
                BRUSHES.map { (name, params) ->
                    val preparationStart = System.nanoTime()
                    requireNotNull(repository.compositePreviewFrame())
                    val beginStart = System.nanoTime()
                    val stroke = repository.beginStroke(START_X, CENTRE, 0.5f, params, ink, false)
                    val beginEnd = System.nanoTime()
                    val samples =
                        (0 until SAMPLES).map { sample ->
                            val x = START_X + sample * STEP
                            val y = CENTRE + sin(sample / WAVE) * AMPLITUDE
                            val pressure = 0.4f + 0.5f * (sample % 20) / 20f
                            val start = System.nanoTime()
                            repository.continueStroke(stroke, x, y, pressure)
                            val afterContinue = System.nanoTime()
                            requireNotNull(repository.compositePreviewFrame())
                            val afterPreview = System.nanoTime()
                            Sample(sample, afterContinue - start, afterPreview - afterContinue, afterPreview - start)
                        }
                    val endStart = System.nanoTime()
                    repository.endStroke(stroke)
                    val afterEnd = System.nanoTime()
                    requireNotNull(repository.compositePreviewFrame())
                    val afterFinalPreview = System.nanoTime()
                    Timing(
                        name,
                        params,
                        beginStart - preparationStart,
                        beginEnd - beginStart,
                        samples,
                        afterEnd - endStart,
                        afterFinalPreview - afterEnd,
                        afterFinalPreview - endStart,
                    )
                }
            val text = readableReport(report)
            File(TestEvidence.directory(), "stroke-timing.txt").writeText(text)
            File(TestEvidence.directory(), "stroke-timing.json").writeText(jsonReport(report).toString(2) + "\n")
            Log.i("StrokeTiming", text)
            // Lanes differ hugely (some disable JIT), so these numbers are evidence, not a speed gate.
            assertTrue(report.all { it.preview.p50 > 0 })
        }

    private fun stripes(index: Int): PixelBuffer =
        PixelBuffer(SIZE, SIZE).also { buffer ->
            val colour = COLOURS[index % COLOURS.size]
            for (y in 0 until SIZE) {
                if ((y / STRIPE + index) % 2 == 0) buffer.pixels.fill(colour, y * SIZE, y * SIZE + SIZE / 2 + index * STRIPE)
            }
        }

    private fun readableReport(report: List<Timing>): String =
        buildString {
            appendLine("Elapsed repository-call timings; System.nanoTime; values below in milliseconds.")
            appendLine("Includes called raster/compositing work and dispatch/scheduling/lock waits; not CPU time.")
            appendLine("Excludes device input delivery, GL upload/draw and display presentation.")
            appendLine("Device: API ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.HARDWARE}")
            appendLine("Fixture: ${SIZE}x$SIZE at $DPI dpi; 1 filled Paper + $STRIPED_LAYERS striped + 1 Ink = 6 layers.")
            appendLine("$SAMPLES samples per brush, in the order below; earlier committed strokes remain on Ink.")
            appendLine("Raw nanoseconds and sample order are retained in stroke-timing.json; no speed threshold.")
            report.forEach { appendLine(it.line()) }
        }

    private fun jsonReport(report: List<Timing>): JSONObject =
        JSONObject()
            .put("schemaVersion", 1)
            .put("clock", "System.nanoTime")
            .put("unit", "nanoseconds")
            .put("measurement", "elapsed repository calls, including dispatch/scheduling/lock waits")
            .put("excluded", JSONArray(listOf("device input delivery", "GL upload/draw", "display presentation")))
            .put(
                "device",
                JSONObject()
                    .put("sdkInt", Build.VERSION.SDK_INT)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("hardware", Build.HARDWARE)
                    .put("fingerprint", Build.FINGERPRINT),
            ).put(
                "fixture",
                JSONObject()
                    .put("width", SIZE)
                    .put("height", SIZE)
                    .put("dpi", DPI)
                    .put("paperLayers", 1)
                    .put("stripedLayers", STRIPED_LAYERS)
                    .put("inkLayers", 1)
                    .put("totalLayers", STRIPED_LAYERS + 2)
                    .put("samplesPerBrush", SAMPLES)
                    .put("priorCommittedStrokesRetained", true)
                    .put("startX", START_X)
                    .put("step", STEP)
                    .put("centreY", CENTRE)
                    .put("waveDivisor", WAVE)
                    .put("amplitude", AMPLITUDE),
            ).put("brushes", JSONArray(report.map(Timing::json)))

    private class Sample(
        val index: Int,
        val continueNanos: Long,
        val previewNanos: Long,
        val combinedNanos: Long,
    ) {
        fun json(): JSONObject =
            JSONObject()
                .put("index", index)
                .put("continueStrokeNanos", continueNanos)
                .put("compositePreviewFrameNanos", previewNanos)
                .put("combinedNanos", combinedNanos)
    }

    private class Timing(
        val name: String,
        val params: BrushParams,
        val preStrokePreviewNanos: Long,
        val beginNanos: Long,
        val samples: List<Sample>,
        val endNanos: Long,
        val finalPreviewNanos: Long,
        val endAndFinalPreviewNanos: Long,
    ) {
        val continuation = Summary(samples.map(Sample::continueNanos))
        val preview = Summary(samples.map(Sample::previewNanos))
        val combined = Summary(samples.map(Sample::combinedNanos))

        fun line(): String =
            buildString {
                appendLine("$name:")
                appendLine("  pre-stroke preview ${ms(preStrokePreviewNanos)} ms; beginStroke ${ms(beginNanos)} ms")
                appendLine("  sample continueStroke ${continuation.line()}")
                appendLine("  sample compositePreviewFrame ${preview.line()}")
                appendLine("  sample continueStroke + preview ${combined.line()}")
                appendLine("  endStroke ${ms(endNanos)} ms; final preview ${ms(finalPreviewNanos)} ms")
                append("  endStroke + final preview ${ms(endAndFinalPreviewNanos)} ms")
            }

        fun json(): JSONObject =
            JSONObject()
                .put("name", name)
                .put(
                    "parameters",
                    JSONObject()
                        .put("size", params.size)
                        .put("spacing", params.spacing)
                        .put("opacity", params.opacity)
                        .put("flow", params.flow)
                        .put("buildUp", params.buildUp)
                        .put("textureId", params.textureId ?: JSONObject.NULL)
                        .put("blendTexture", params.blendTexture),
                ).put("preStrokePreviewNanos", preStrokePreviewNanos)
                .put("beginStrokeNanos", beginNanos)
                .put("samples", JSONArray(samples.map(Sample::json)))
                .put("continueStrokeSummary", continuation.json())
                .put("compositePreviewFrameSummary", preview.json())
                .put("combinedSampleSummary", combined.json())
                .put("endStrokeNanos", endNanos)
                .put("finalPreviewNanos", finalPreviewNanos)
                .put("endAndFinalPreviewNanos", endAndFinalPreviewNanos)
    }

    private class Summary(
        values: List<Long>,
    ) {
        private val sorted = values.sorted()
        val p50 = sorted[sorted.size / 2]
        private val p95 = sorted[sorted.size * 95 / 100]
        private val max = sorted.last()

        fun line(): String = "p50 ${ms(p50)} ms, p95 ${ms(p95)} ms, max ${ms(max)} ms"

        fun json(): JSONObject =
            JSONObject()
                .put("p50Nanos", p50)
                .put("p95Nanos", p95)
                .put("maxNanos", max)
    }

    private companion object {
        const val SIZE = 2048
        const val DPI = 264
        const val STRIPED_LAYERS = 4
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

        fun ms(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)
    }
}
