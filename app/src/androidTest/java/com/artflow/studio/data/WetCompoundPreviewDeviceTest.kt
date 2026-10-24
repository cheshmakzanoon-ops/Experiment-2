package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

/** Exact repository preview/storage evidence, not native-app rendering or input/display latency. */
@RunWith(AndroidJUnit4::class)
class WetCompoundPreviewDeviceTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private lateinit var report: JSONObject
    private val plain = BrushParams(size = 20f, spacing = 0.25f, pressureToSize = 0f, pressureToOpacity = 0f, smoothing = 0f)
    private val brush = plain.copy(wetMix = 0.1f, pull = 1f, dual = DualBrush(plain, DualBrush.Mode.MULTIPLY))

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "wet-compound-preview-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
        report = newReport()
    }

    @After
    fun tearDown() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            StorageFileTree.delete(directory)
        }

    @Test
    fun croppedWetTailMatchesFullCommitAndSurvivesCancellationHistoryAndDiskReopen(): Unit =
        runBlocking(Dispatchers.Main) {
            try {
                val source = PixelBuffer.filled(WIDTH, HEIGHT, RED)
                prepare(repository, PROJECT_ID, source)
                val original = source.pixels.copyOf()
                val layer = repository.getActiveLayerId()
                val before = history()
                recordState("prepared")

                // These independent repositories never render a preview. Their committed output
                // also matches exact pixels captured from the unchanged published 8050121 renderer.
                val firstOracle = committedWithoutPreview(2)
                val finalOracle = committedWithoutPreview(3)
                writeImage("oracle", finalOracle)
                report.put("firstOracleArgbSha256", pixelHash(firstOracle))
                assertEquals(FIRST_SHA, pixelHash(firstOracle))
                assertEquals(FINAL_SHA, pixelHash(finalOracle))

                val baseline = requireNotNull(repository.compositePreviewFrame())
                assertArrayEquals(original, baseline.buffer.pixels)
                assertNull("The initial frame must initialize the complete preview", baseline.dirty)
                val stroke = begin(repository)
                append(repository, stroke, 1)
                val firstFrame = captureFrame("first")
                val firstFull = requireNotNull(repository.compositePreview()).pixels.copyOf()
                report.put("firstFullArgbSha256", pixelHash(firstFull))
                assertArrayEquals("First preview versus independent commit", firstOracle, firstFrame)
                assertArrayEquals("First preview versus full rendering", firstFull, firstFrame)
                assertEquals(0xFFDD0022.toInt(), firstFrame[60 * WIDTH + 190])
                assertProvisional(stroke, 2, original, before)
                recordState("firstPreview", stroke)

                append(repository, stroke, 2)
                val tailFrame = captureFrame("tail")
                val full = requireNotNull(repository.compositePreview()).pixels.copyOf()
                writeImage("full", full)
                // Retain all three outputs before asserting, so a regression leaves useful pixels.
                assertArrayEquals("Appended preview versus full rendering", full, tailFrame)
                assertArrayEquals("Appended preview versus independent no-preview commit", finalOracle, tailFrame)
                assertFinalPixels(tailFrame)
                assertProvisional(stroke, 3, original, before)
                assertArrayEquals("Caller-owned source pixels remain unchanged", original, source.pixels)
                recordState("tailPreview", stroke)

                repository.cancelStroke(stroke)
                assertNull(repository.activeStroke(stroke))
                val cancelled = captureFrame("cancelled")
                assertArrayEquals("Cancellation removes every provisional pixel", original, cancelled)
                assertArrayEquals(original, pixels(layer))
                assertHistory(before)
                recordState("cancelled")

                // Exercise the same cached-preview sequence again, now committing it normally.
                val committedStroke = begin(repository)
                append(repository, committedStroke, 1)
                assertArrayEquals(firstOracle, requireNotNull(repository.compositePreviewFrame()).buffer.pixels)
                append(repository, committedStroke, 2)
                assertArrayEquals(finalOracle, requireNotNull(repository.compositePreviewFrame()).buffer.pixels)
                repository.endStroke(committedStroke)
                assertNull(repository.activeStroke(committedStroke))
                assertArrayEquals(finalOracle, pixels(layer))
                assertArrayEquals(finalOracle, requireNotNull(repository.compositePreviewFrame()).buffer.pixels)
                assertHistory(History(before.undoDepth + 1, 0, before.mark + 1))
                recordState("committed")

                assertTrue(repository.undo())
                assertArrayEquals(original, pixels(layer))
                assertArrayEquals(original, requireNotNull(repository.compositePreviewFrame()).buffer.pixels)
                assertHistory(History(before.undoDepth, 1, before.mark + 2))
                recordState("undo")
                assertTrue(repository.redo())
                assertArrayEquals(finalOracle, pixels(layer))
                assertArrayEquals(finalOracle, requireNotNull(repository.compositePreviewFrame()).buffer.pixels)
                assertHistory(History(before.undoDepth + 1, 0, before.mark + 3))
                assertArrayEquals("Neither commit nor history may mutate the supplied raster", original, source.pixels)
                recordState("redo")

                assertTrue(repository.saveCanvas(PROJECT_ID) != null)
                val manifest = storage.documentFile(PROJECT_ID).readBytes()
                report.put("savedChecksum", assertSigned(manifest))
                writeBytes("wet-compound-preview-saved.artflow", manifest)
                val flattened = requireNotNull(storage.loadFlattenedBytes(PROJECT_ID))
                val decoded = requireNotNull(BitmapPixelBridge.fromEncodedBytes(flattened))
                assertEquals(WIDTH, decoded.width)
                assertEquals(HEIGHT, decoded.height)
                assertArrayEquals("Android PNG decode of the actual saved composite", finalOracle, decoded.pixels)
                writeBytes("wet-compound-preview-saved.png", flattened)
                recordState("saved")

                repository.dispose()
                storage = ProjectStorage(context)
                repository = CanvasRepositoryImpl(storage)
                requireNotNull(repository.loadCanvas(PROJECT_ID))
                assertEquals(PROJECT_ID, repository.projectId())
                assertEquals(layer, repository.getActiveLayerId())
                assertEquals(1, repository.getAllLayers().size)
                assertEquals(0, repository.undoDepth)
                assertEquals(0, repository.redoDepth)
                assertArrayEquals(finalOracle, pixels(layer))
                val reopened = requireNotNull(repository.compositeBuffer()).pixels.copyOf()
                writeImage("reopened", reopened)
                assertArrayEquals(finalOracle, reopened)
                assertFinalPixels(reopened)
                assertArrayEquals(
                    "Loading must not rewrite a valid signed manifest",
                    manifest,
                    storage.documentFile(PROJECT_ID).readBytes(),
                )
                assertArrayEquals(flattened, requireNotNull(storage.loadFlattenedBytes(PROJECT_ID)))
                recordState("reopened")
                report.put("complete", true)
            } finally {
                File(TestEvidence.directory(), "wet-compound-preview.json").writeText(report.toString(2))
            }
        }

    private suspend fun prepare(
        target: CanvasRepositoryImpl,
        projectId: Long,
        source: PixelBuffer,
    ) {
        requireNotNull(target.loadOrCreate(projectId, WIDTH, HEIGHT, DPI))
        assertTrue(target.setLayerPixels(target.getActiveLayerId(), source, "Opaque red wet-paint fixture"))
        target.setStrokeColor(BLUE)
    }

    private suspend fun committedWithoutPreview(sampleCount: Int): IntArray {
        val target = CanvasRepositoryImpl(ProjectStorage(context))
        try {
            prepare(target, PROJECT_ID + sampleCount, PixelBuffer.filled(WIDTH, HEIGHT, RED))
            val stroke = begin(target)
            for (sample in 1 until sampleCount) append(target, stroke, sample)
            target.endStroke(stroke)
            return requireNotNull(target.layerPixels(target.getActiveLayerId())).pixels.copyOf()
        } finally {
            target.dispose()
        }
    }

    private fun begin(target: CanvasRepositoryImpl): Long {
        val stroke = target.beginStroke(SAMPLE_X[0], SAMPLE_Y, 1f, brush, target.getActiveLayerId(), timestamp = TIMES[0])
        assertTrue("Fixture stroke must start", stroke > 0L)
        return stroke
    }

    private fun append(
        target: CanvasRepositoryImpl,
        stroke: Long,
        sample: Int,
    ) = target.continueStroke(stroke, SAMPLE_X[sample], SAMPLE_Y, 1f, timestamp = TIMES[sample])

    private suspend fun captureFrame(name: String): IntArray {
        report.put("lastStage", name)
        val frame = requireNotNull(repository.compositePreviewFrame())
        val pixels = frame.buffer.pixels.copyOf()
        report.getJSONObject("dirtyBounds").put(name, bounds(frame.dirty))
        writeImage(name, pixels)
        return pixels
    }

    private suspend fun assertProvisional(
        stroke: Long,
        pointCount: Int,
        original: IntArray,
        before: History,
    ) {
        val active = requireNotNull(repository.activeStroke(stroke))
        assertEquals(pointCount, active.points.size)
        assertEquals(brush, active.brushParams)
        assertArrayEquals("Preview cannot alter its committed source layer", original, pixels(repository.getActiveLayerId()))
        assertArrayEquals("Non-preview composites exclude provisional paint", original, requireNotNull(repository.compositeBuffer()).pixels)
        assertHistory(before)
    }

    private fun assertFinalPixels(pixels: IntArray) {
        assertEquals(FINAL_SHA, pixelHash(pixels))
        assertEquals("Retained pickup at the previous endpoint", 0xFFDF0020.toInt(), pixels[60 * WIDTH + 190])
        assertEquals("Retained pickup beside the appended tail", 0xFFD70028.toInt(), pixels[51 * WIDTH + 176])
    }

    private suspend fun pixels(layer: Long): IntArray = requireNotNull(repository.layerPixels(layer)).pixels

    private fun history(): History = History(repository.undoDepth, repository.redoDepth, repository.historyMark)

    private fun assertHistory(expected: History) = assertEquals("Preview/commit history ownership", expected, history())

    private suspend fun recordState(
        name: String,
        stroke: Long? = null,
    ) {
        report.put("lastStage", name)
        report.getJSONObject("states").put(
            name,
            JSONObject()
                .put("undoDepth", repository.undoDepth)
                .put("redoDepth", repository.redoDepth)
                .put("historyMark", repository.historyMark)
                .put("projectId", repository.projectId())
                .put("activeLayerId", repository.getActiveLayerId())
                .put("activeStrokePointCount", stroke?.let { repository.activeStroke(it)?.points?.size } ?: 0)
                .put("committedArgbSha256", pixelHash(pixels(repository.getActiveLayerId()))),
        )
    }

    private fun writeImage(
        name: String,
        pixels: IntArray,
    ) {
        val bytes = BitmapPixelBridge.toPngBytes(PixelBuffer(WIDTH, HEIGHT, pixels), DPI)
        writeBytes("wet-compound-preview-$name.png", bytes)
        report.getJSONObject("images").put(
            name,
            JSONObject()
                .put("file", "wet-compound-preview-$name.png")
                .put("argbSha256", pixelHash(pixels))
                .put("at190x60", hex(pixels[60 * WIDTH + 190]))
                .put("at176x51", hex(pixels[51 * WIDTH + 176])),
        )
    }

    private fun writeBytes(
        name: String,
        bytes: ByteArray,
    ) {
        File(TestEvidence.directory(), name).writeBytes(bytes)
        report.getJSONObject("files").put(name, JSONObject().put("bytes", bytes.size).put("sha256", sha256(bytes)))
    }

    /** Verify persisted bytes independently; the normal decoder clears an already-checked checksum. */
    private fun assertSigned(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        val document = JSONObject(text)
        assertEquals(CanvasDocument.CURRENT_VERSION, document.getInt("version"))
        assertEquals(WIDTH, document.getInt("width"))
        assertEquals(HEIGHT, document.getInt("height"))
        assertEquals(DPI, document.getInt("dpi"))
        assertEquals("SRGB", document.getString("colorProfile"))
        val checksum = document.getString("checksum")
        assertTrue(checksum.matches(Regex("[0-9a-f]{64}")))
        val token = "\"checksum\":\"$checksum\""
        assertEquals(1, Regex(Regex.escape(token)).findAll(text).count())
        assertEquals(checksum, sha256(text.replace(token, "\"checksum\":null").toByteArray()))
        return checksum
    }

    private fun newReport(): JSONObject {
        val samples = JSONArray()
        SAMPLE_X.forEachIndexed { index, x ->
            samples.put(
                JSONObject()
                    .put("x", x)
                    .put("y", SAMPLE_Y)
                    .put("pressure", 1f)
                    .put("timestamp", TIMES[index]),
            )
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("complete", false)
            .put("scope", "ArtFlow repository pixels, history and real storage; no native-app, input, GL or display-latency comparison")
            .put("referenceCommit", "8050121a7a6e3a1d52854d3c398ad1d29a33bcfe")
            .put("referenceScope", "Exact full/no-preview-commit output captured from the unchanged published ArtFlow renderer")
            .put("pixelHashFormat", "SHA-256 of row-major big-endian ARGB int bytes; no dimensions prefix")
            .put("width", WIDTH)
            .put("height", HEIGHT)
            .put("dpi", DPI)
            .put("sourceArgb", hex(RED))
            .put("paintArgb", hex(BLUE))
            .put("brush", JSONObject(Json { encodeDefaults = true }.encodeToString(BrushParams.serializer(), brush)))
            .put("samples", samples)
            .put("expectedFirstArgbSha256", FIRST_SHA)
            .put("expectedFinalArgbSha256", FINAL_SHA)
            .put("runtime", JSONObject().put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL).put("fingerprint", Build.FINGERPRINT))
            .put("states", JSONObject())
            .put("dirtyBounds", JSONObject())
            .put("images", JSONObject())
            .put("files", JSONObject())
    }

    private fun bounds(rect: IntBounds?): Any =
        rect?.let {
            JSONObject()
                .put("left", it.left)
                .put("top", it.top)
                .put("right", it.right)
                .put("bottom", it.bottom)
        } ?: JSONObject.NULL

    private fun pixelHash(pixels: IntArray): String {
        val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
        pixels.forEach { bytes.putInt(it) }
        return sha256(bytes.array())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun hex(argb: Int): String = "%08x".format(Locale.ROOT, argb)

    private data class History(
        val undoDepth: Int,
        val redoDepth: Int,
        val mark: Long,
    )

    private companion object {
        const val WIDTH = 512
        const val HEIGHT = 128
        const val DPI = 72
        const val PROJECT_ID = 972L
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val SAMPLE_Y = 60.5f
        const val FIRST_SHA = "3722afbb26761662e7c8eb6c4f4ecaf46011eb88712811742c4a3bc175fcc340"
        const val FINAL_SHA = "27acdb833c4464b0cec7703c2b52d8443dd5bd3da6746add63cd0719119f12da"
        val SAMPLE_X = floatArrayOf(30.5f, 190.5f, 350.5f)
        val TIMES = longArrayOf(1000L, 1016L, 1032L)
    }
}
