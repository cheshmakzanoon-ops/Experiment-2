package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artflow.studio.SelectionCanvasTestActivity
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.core.three.MeshPicker
import com.artflow.studio.core.three.ModelPackage
import com.artflow.studio.core.three.ObjParser
import com.artflow.studio.core.three.OrbitCamera
import com.artflow.studio.core.three.PreparedModelImport
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.ModelImports
import com.artflow.studio.data.export.PendingImports
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.components.canvas.ModelView
import com.artflow.studio.presentation.ui.viewmodel.ModelController
import com.artflow.studio.presentation.ui.viewmodel.renderedExport
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Production model import, real storage/GL, and independent Android decoding of exported scalar data. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ModelMaterialImportDeviceTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        hilt.inject()
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "model-material-import-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
    }

    @After
    fun cleanUp() {
        repository.dispose()
        StorageFileTree.delete(directory)
    }

    @Test
    fun asymmetricMapsKeepUvsAndIsolatedHistoryAcrossFreshReopenAndReexport(): Unit =
        runBlocking(Dispatchers.Main) {
            val prefix = "material-import-asymmetric"
            val expected = authoredPlanes()
            val bytes = authoredZip(expected)
            val metadata = JSONObject().put("fixtureSha256", sha256(bytes)).put("width", WIDTH).put("height", HEIGHT)
            File(TestEvidence.directory(), "$prefix-source.zip").writeBytes(bytes)
            record(prefix, metadata, "source preserved")
            val colorLayer = install(bytes, expected, MODEL, prefix, metadata)
            assertArrayEquals(EXPECTED_UVS, ObjParser.parse(requireNotNull(repository.loadModel())).uvs, 0f)
            val metallicLayer = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
            val beforePaint = repository.undoDepth
            repository.setMaterialPaintValue(1f)
            val stroke =
                repository.beginStroke(
                    PAINT_X + 0.5f,
                    PAINT_Y + 0.5f,
                    1f,
                    BrushParams(size = 4f, pressureToSize = 0f, pressureToOpacity = 0f),
                    metallicLayer,
                )
            assertTrue(stroke > 0)
            repository.endStroke(stroke)
            val painted = planes()
            preservePlanes(prefix, "painted", painted)
            metadata.put("undoBeforePaint", beforePaint).put("undoAfterPaint", repository.undoDepth)
            record(prefix, metadata, "metallic stroke committed")
            assertEquals(beforePaint + 1, repository.undoDepth)
            assertArrayEquals(expected.getValue(MaterialChannel.COLOR).pixels, painted.getValue(MaterialChannel.COLOR).pixels)
            assertArrayEquals(expected.getValue(MaterialChannel.ROUGHNESS).pixels, painted.getValue(MaterialChannel.ROUGHNESS).pixels)
            val paintIndex = PAINT_Y * WIDTH + PAINT_X
            assertEquals(0, expected.getValue(MaterialChannel.METALLIC).pixels[paintIndex] and 255)
            assertEquals(255, painted.getValue(MaterialChannel.METALLIC).pixels[paintIndex] and 255)
            assertFalse(expected.getValue(MaterialChannel.METALLIC).pixels.contentEquals(painted.getValue(MaterialChannel.METALLIC).pixels))
            assertTrue(repository.undo())
            val undone = planes()
            preservePlanes(prefix, "undo", undone)
            assertPlanes(expected, undone)
            assertTrue(repository.redo())
            val redone = planes()
            preservePlanes(prefix, "redo", redone)
            assertPlanes(painted, redone)
            reopen(prefix, metadata, colorLayer, MODEL, painted)
            reexport(prefix, metadata, MODEL, painted)
            val reopened = planes()
            withContext(Dispatchers.Default) { preview(prefix, metadata, MODEL, reopened) }
            record(prefix, metadata.put("completed", true), "complete")
        }

    @Test
    fun pinnedOwnExportReimportsEverySavedMapAndReexportsWithoutChangingValues(): Unit =
        runBlocking(Dispatchers.Main) {
            val prefix = "material-import-run318"
            val bytes =
                InstrumentationRegistry
                    .getInstrumentation()
                    .context.assets
                    .open("models/run318-material-model-export.zip")
                    .use { it.readBytes() }
            File(TestEvidence.directory(), "$prefix-source.zip").writeBytes(bytes)
            assertEquals(1258, bytes.size)
            assertEquals(PINNED_SHA256, sha256(bytes))
            val entries = unzip(bytes)
            val expected =
                mapOf(
                    MaterialChannel.COLOR to decoded(entries.getValue("texture.png")),
                    MaterialChannel.METALLIC to decoded(entries.getValue("metallic.png")),
                    MaterialChannel.ROUGHNESS to decoded(entries.getValue("roughness.png")),
                )
            val model = entries.getValue("model.obj").decodeToString()
            val metadata = JSONObject().put("fixtureSha256", sha256(bytes)).put("sourceRun", 318)
            record(prefix, metadata, "pinned source preserved")
            val colorLayer = install(bytes, expected, model, prefix, metadata)
            assertEquals(0, expected.getValue(MaterialChannel.METALLIC).pixels[0] and 255)
            assertEquals(255, expected.getValue(MaterialChannel.METALLIC).pixels[16 * 32 + 8] and 255)
            assertEquals(153, expected.getValue(MaterialChannel.ROUGHNESS).pixels[0] and 255)
            assertEquals(64, expected.getValue(MaterialChannel.ROUGHNESS).pixels[16 * 32 + 24] and 255)
            reopen(prefix, metadata, colorLayer, model, expected)
            reexport(prefix, metadata, model, expected)
            record(prefix, metadata.put("completed", true), "complete")
        }

    @Test
    fun completedInitialSaveCanRetryAfterFailureWithoutDuplicatingReloadedMaterialLayers(): Unit =
        runBlocking(Dispatchers.Main) {
            val prefix = "material-import-retry"
            val expected = authoredPlanes()
            val bytes = authoredZip(expected)
            val prepared = PreparedModelImport.prepare(ModelPackage.read(bytes), BitmapPixelBridge::fromEncodedBytes)
            val metadata = JSONObject().put("fixtureSha256", sha256(bytes)).put("width", WIDTH).put("height", HEIGHT)
            File(TestEvidence.directory(), "$prefix-source.zip").writeBytes(bytes)
            repository.loadOrCreate(PROJECT, prepared.width, prepared.height, 72)
            repository.setCanvasBackgroundColor(0)
            val beforeImport = repository.undoDepth
            val firstRepository = repository
            val failingSave =
                object : CanvasRepository by firstRepository {
                    override suspend fun saveCanvas(projectId: Long): String? {
                        val thumbnail = firstRepository.saveCanvas(projectId)
                        preservePlanes(prefix, "saved-before-failure", planes())
                        storage
                            .documentFile(
                                projectId,
                            ).copyTo(File(TestEvidence.directory(), "$prefix-document-before-failure.json"), overwrite = true)
                        record(
                            prefix,
                            metadata.put("savedThumbnailPresent", thumbnail != null),
                            "real save completed before injected failure",
                        )
                        checkNotNull(thumbnail) { "The real initial save did not finish" }
                        throw IOException("Injected failure after durable model save")
                    }
                }
            PendingImports.putModelBundle(PROJECT, prepared)
            try {
                val failed = runCatching { ModelController(failingSave, this) {}.initialize(PROJECT) }.exceptionOrNull()
                val installedLayers = repository.getAllLayers()
                val installedIds = installedLayers.map { it.id }
                val installedChannels = installedLayers.map { it.materialChannel }
                metadata
                    .put("failure", failed?.message)
                    .put("layerIdsAfterFailure", JSONArray(installedIds))
                    .put("channelsAfterFailure", JSONArray(installedChannels.map { it.name }))
                    .put("undoBeforeImport", beforeImport)
                    .put("undoAfterImport", repository.undoDepth)
                    .put("pendingAfterFailure", PendingImports.modelBundle(PROJECT) === prepared)
                record(prefix, metadata, "failed initial save retains pending bundle")
                assertTrue(failed is IOException)
                assertEquals("Injected failure after durable model save", failed?.message)
                assertSame(prepared, PendingImports.modelBundle(PROJECT))
                assertEquals(4, installedLayers.size)
                assertEquals(beforeImport + 1, repository.undoDepth)
                assertPlanes(expected, planes())
                repository.dispose()
                storage = ProjectStorage(context)
                repository = CanvasRepositoryImpl(storage)
                requireNotNull(repository.loadCanvas(PROJECT))
                val reopened = planes()
                preservePlanes(prefix, "reopened", reopened)
                File(TestEvidence.directory(), "$prefix-reopened.obj").writeText(requireNotNull(repository.loadModel()))
                val reopenedIds = repository.getAllLayers().map { it.id }
                metadata
                    .put("layerIdsAfterReopen", JSONArray(reopenedIds))
                    .put("undoBeforeRetry", repository.undoDepth)
                record(prefix, metadata, "fresh repository loaded durable model and material planes")
                assertEquals(installedIds, reopenedIds)
                assertEquals(MODEL, repository.loadModel())
                assertPlanes(expected, reopened)
                val history = repository.undoDepth
                val controller = ModelController(repository, this) {}
                controller.initialize(PROJECT)
                val retried = planes()
                preservePlanes(prefix, "retried", retried)
                storage.documentFile(PROJECT).copyTo(File(TestEvidence.directory(), "$prefix-document-after-retry.json"), overwrite = true)
                metadata
                    .put("layerIdsAfterRetry", JSONArray(repository.getAllLayers().map { it.id }))
                    .put("channelsAfterRetry", JSONArray(repository.getAllLayers().map { it.materialChannel.name }))
                    .put("undoAfterRetry", repository.undoDepth)
                    .put("activeChannelAfterRetry", activeChannel().name)
                    .put("pendingAfterRetry", PendingImports.modelBundle(PROJECT) != null)
                record(prefix, metadata, "retry reused exact saved insertion")
                assertEquals(installedIds, repository.getAllLayers().map { it.id })
                assertEquals(installedChannels, repository.getAllLayers().map { it.materialChannel })
                assertEquals(history, repository.undoDepth)
                assertEquals(MaterialChannel.COLOR, activeChannel())
                assertFalse(repository.hasUnsavedChanges())
                assertNull(PendingImports.modelBundle(PROJECT))
                assertArrayEquals(EXPECTED_UVS, requireNotNull(controller.mesh.value).uvs, 0f)
                assertPlanes(expected, retried)
                record(prefix, metadata.put("completed", true), "complete")
            } finally {
                PendingImports.completeModelBundle(PROJECT, prepared)
            }
        }

    private suspend fun install(
        bytes: ByteArray,
        expected: Map<MaterialChannel, PixelBuffer>,
        model: String,
        prefix: String,
        metadata: JSONObject,
    ): Long {
        val prepared = PreparedModelImport.prepare(ModelPackage.read(bytes), BitmapPixelBridge::fromEncodedBytes)
        assertEquals(model, prepared.objText)
        val color = expected.getValue(MaterialChannel.COLOR)
        assertEquals(color.width, prepared.width)
        assertEquals(color.height, prepared.height)
        repository.loadOrCreate(PROJECT, prepared.width, prepared.height, 72)
        repository.setCanvasBackgroundColor(0)
        val before = repository.undoDepth
        assertTrue(ModelImports.install(repository, prepared))
        val colorLayer = repository.getActiveLayerId()
        val actual = planes()
        preservePlanes(prefix, "imported", actual)
        metadata
            .put("width", prepared.width)
            .put("height", prepared.height)
            .put("undoBeforeImport", before)
            .put("undoAfterImport", repository.undoDepth)
            .put("activeChannelAfterImport", activeChannel().name)
        record(prefix, metadata, "installed")
        assertEquals(before + 1, repository.undoDepth)
        assertEquals(MaterialChannel.COLOR, activeChannel())
        assertEquals(model, repository.loadModel())
        assertPlanes(expected, actual)
        return colorLayer
    }

    private suspend fun reopen(
        prefix: String,
        metadata: JSONObject,
        colorLayer: Long,
        model: String,
        expected: Map<MaterialChannel, PixelBuffer>,
    ) {
        assertTrue(repository.setActiveLayer(colorLayer))
        assertTrue(repository.saveCanvas(PROJECT) != null)
        storage.documentFile(PROJECT).copyTo(File(TestEvidence.directory(), "$prefix-document.json"), overwrite = true)
        repository.dispose()
        storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
        requireNotNull(repository.loadCanvas(PROJECT))
        val reopened = planes()
        preservePlanes(prefix, "reopened", reopened)
        val reopenedModel = requireNotNull(repository.loadModel())
        File(TestEvidence.directory(), "$prefix-reopened.obj").writeText(reopenedModel)
        record(prefix, metadata.put("activeChannelAfterReopen", activeChannel().name), "reopened from fresh repository")
        assertEquals(model, reopenedModel)
        assertEquals(MaterialChannel.COLOR, activeChannel())
        assertFalse(repository.hasUnsavedChanges())
        assertPlanes(expected, reopened)
    }

    private suspend fun reexport(
        prefix: String,
        metadata: JSONObject,
        model: String,
        expected: Map<MaterialChannel, PixelBuffer>,
    ) {
        val result =
            renderedExport(
                repository,
                ArtworkExporter(context, storage),
                PROJECT,
                prefix,
                ExportOptions(format = ExportFormat.MODEL_OBJ),
            ).getOrThrow()
        val bytes = File(result.filePath).readBytes()
        File(TestEvidence.directory(), "$prefix-reexport.zip").writeBytes(bytes)
        record(prefix, metadata.put("reexportSha256", sha256(bytes)), "reexport preserved")
        val entries = unzip(bytes)
        assertEquals(setOf("model.obj", "model.mtl", "texture.png", "metallic.png", "roughness.png"), entries.keys)
        val material =
            entries
                .getValue("model.mtl")
                .decodeToString()
                .lineSequence()
                .map(String::trim)
                .toList()
        assertTrue(material.containsAll(listOf("map_Kd texture.png", "map_Pm metallic.png", "map_Pr roughness.png")))
        assertEquals(1f, material.single { it.startsWith("Pm ") }.substringAfter(' ').toFloat(), 0f)
        assertEquals(1f, material.single { it.startsWith("Pr ") }.substringAfter(' ').toFloat(), 0f)
        assertEquals(geometry(model), geometry(entries.getValue("model.obj").decodeToString()))
        assertPlanes(
            expected,
            mapOf(
                MaterialChannel.COLOR to decoded(entries.getValue("texture.png")),
                MaterialChannel.METALLIC to decoded(entries.getValue("metallic.png")),
                MaterialChannel.ROUGHNESS to decoded(entries.getValue("roughness.png")),
            ),
        )
    }

    private suspend fun planes(): Map<MaterialChannel, PixelBuffer> =
        MaterialChannel.entries.associateWith { channel ->
            requireNotNull(
                if (channel == MaterialChannel.COLOR) repository.compositeBuffer() else repository.compositeMaterial(channel),
            ).copy()
        }

    private fun activeChannel(): MaterialChannel =
        repository.getAllLayers().single { it.id == repository.getActiveLayerId() }.materialChannel

    private fun assertPlanes(
        expected: Map<MaterialChannel, PixelBuffer>,
        actual: Map<MaterialChannel, PixelBuffer>,
    ) {
        expected.forEach { (channel, source) ->
            val observed = actual.getValue(channel)
            assertEquals(channel.name, source.width, observed.width)
            assertEquals(channel.name, source.height, observed.height)
            assertArrayEquals(channel.name, source.pixels, observed.pixels)
        }
    }

    private fun preservePlanes(
        prefix: String,
        stage: String,
        planes: Map<MaterialChannel, PixelBuffer>,
    ) {
        planes.forEach { (channel, pixels) ->
            File(TestEvidence.directory(), "$prefix-$stage-${channel.name.lowercase()}.png").writeBytes(png(pixels))
        }
    }

    private fun record(
        prefix: String,
        metadata: JSONObject,
        stage: String,
    ) {
        metadata.put("stage", stage)
        File(TestEvidence.directory(), "$prefix-metadata.json").writeText(metadata.toString(2))
    }

    private fun authoredPlanes(): Map<MaterialChannel, PixelBuffer> =
        MaterialChannel.entries.associateWith { channel ->
            PixelBuffer(
                WIDTH,
                HEIGHT,
                IntArray(WIDTH * HEIGHT) { index ->
                    val x = index % WIDTH
                    val y = index / WIDTH
                    if (channel == MaterialChannel.COLOR) {
                        (255 shl 24) or (((x * 43 + y * 17 + 31) % 256) shl 16) or
                            (((x * 11 + y * 61 + 47) % 256) shl 8) or ((x * 73 + y * 7 + 83) % 256)
                    } else {
                        val at = if (channel == MaterialChannel.METALLIC) x + 3 * y else 2 * x + 5 * y + 3
                        val value = SCALARS[at % SCALARS.size]
                        (255 shl 24) or (value shl 16) or (value shl 8) or value
                    }
                },
            )
        }

    /** Authored directly from pixels and text, without using the production OBJ or material writer. */
    private fun authoredZip(planes: Map<MaterialChannel, PixelBuffer>): ByteArray {
        val entries =
            linkedMapOf(
                "model.obj" to MODEL.encodeToByteArray(),
                "surface.mtl" to
                    "newmtl painted\nKd 1 1 1\nmap_Kd color.png\nPm 1\nPr 1\nmap_Pm metal.png\nmap_Pr rough.png\n".encodeToByteArray(),
                "color.png" to sourcePng(planes.getValue(MaterialChannel.COLOR)),
                "metal.png" to sourcePng(planes.getValue(MaterialChannel.METALLIC)),
                "rough.png" to sourcePng(planes.getValue(MaterialChannel.ROUGHNESS)),
            )
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** Independently authored RGBA rows have no color tags that could reinterpret scalar numbers. */
    private fun sourcePng(pixels: PixelBuffer): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { stream ->
            val row = ByteArray(1 + pixels.width * 4)
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val pixel = pixels.pixels[y * pixels.width + x]
                    val offset = 1 + x * 4
                    row[offset] = (pixel ushr 16).toByte()
                    row[offset + 1] = (pixel ushr 8).toByte()
                    row[offset + 2] = pixel.toByte()
                    row[offset + 3] = (pixel ushr 24).toByte()
                }
                stream.write(row)
            }
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use {
            it.writeInt(pixels.width)
            it.writeInt(pixels.height)
            it.write(byteArrayOf(8, 6, 0, 0, 0))
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { stream ->
            stream.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))

            fun chunk(
                name: String,
                payload: ByteArray,
            ) {
                val type = name.toByteArray(Charsets.US_ASCII)
                stream.writeInt(payload.size)
                stream.write(type)
                stream.write(payload)
                val checksum =
                    CRC32().apply {
                        update(type)
                        update(payload)
                    }
                stream.writeInt(checksum.value.toInt())
            }
            chunk("IHDR", header.toByteArray())
            chunk("IDAT", compressed.toByteArray())
            chunk("IEND", ByteArray(0))
        }
        return bytes.toByteArray()
    }

    /** Android's independent PNG codec provides the interchange oracle, without scalar conversion helpers. */
    private fun decoded(bytes: ByteArray): PixelBuffer {
        val bitmap =
            requireNotNull(
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply {
                        inPremultiplied =
                            false
                    },
                ),
            )
        return try {
            PixelBuffer(bitmap.width, bitmap.height).also {
                bitmap.getPixels(it.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun png(pixels: PixelBuffer): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels.pixels, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { bytes ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> =
        buildMap {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    check(put(entry.name, zip.readBytes()) == null) { "Duplicate exported ZIP entry" }
                    entry = zip.nextEntry
                }
            }
        }

    private fun geometry(model: String): List<String> =
        model
            .lineSequence()
            .map(String::trim)
            .filter { line -> listOf("v ", "vt ", "vn ", "f ").any(line::startsWith) }
            .toList()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun preview(
        prefix: String,
        metadata: JSONObject,
        model: String,
        planes: Map<MaterialChannel, PixelBuffer>,
    ) {
        val scenario = ActivityScenario.launch(SelectionCanvasTestActivity::class.java)
        lateinit var view: ModelView
        var attached: ModelView? = null
        try {
            scenario.onActivity { activity ->
                view = ModelView(activity)
                attached = view
                view.mesh = ObjParser.parse(model)
                val bitmaps =
                    planes.mapValues { (_, pixels) ->
                        Bitmap.createBitmap(pixels.pixels, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
                    }
                try {
                    view.setTexture(bitmaps.getValue(MaterialChannel.COLOR))
                    view.setMaterialMaps(bitmaps.filterKeys { it.isScalar })
                } finally {
                    bitmaps.values.forEach(Bitmap::recycle)
                }
                activity.canvasContainer.addView(
                    view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                view.onResume()
            }
            val deadline = SystemClock.uptimeMillis() + 20_000
            var samples = emptyList<Int>()
            var rendered = false
            while (!rendered && SystemClock.uptimeMillis() < deadline) {
                var points = emptyList<Pair<Int, Int>>()
                scenario.onActivity { if (view.width > 0 && view.height > 0) points = previewPoints(view) }
                samples = if (points.isEmpty()) emptyList() else TestEvidence.colorsAtScreenPoints(points).orEmpty()
                rendered =
                    samples.size >= 2 &&
                    (0..16 step 8).all { shift -> abs((samples.last() ushr shift and 255) - 41) <= 3 } &&
                    samples.dropLast(1).any { color -> (0..16 step 8).any { shift -> abs((color ushr shift and 255) - 41) >= 15 } }
                if (!rendered) SystemClock.sleep(50)
            }
            TestEvidence.screenshot("$prefix-model-preview.png")
            record(
                prefix,
                metadata.put("previewSamplesArgb", JSONArray(samples.map { it.toUInt().toString(16) })),
                "actual model preview captured",
            )
            assertTrue("Imported model and maps must reach the actual GL surface: $samples", rendered)
        } finally {
            scenario.onActivity { attached?.onPause() }
            scenario.close()
        }
    }

    private fun previewPoints(view: ModelView): List<Pair<Int, Int>> {
        val mesh = requireNotNull(view.mesh)
        val camera = OrbitCamera()
        val points = (1..9).flatMap { y -> (1..9).map { x -> x * view.width / 10 to y * view.height / 10 } }
        val (inside, outside) =
            points.partition { (x, y) ->
                val ray = camera.ray(x.toFloat(), y.toFloat(), view.width.toFloat(), view.height.toFloat())
                MeshPicker.hit(mesh, ray.first, ray.second) != null
            }
        if (inside.isEmpty() || outside.isEmpty()) return emptyList()
        val origin = IntArray(2).also(view::getLocationOnScreen)
        return (inside.take(6) + outside.first()).map { (x, y) -> x + origin[0] to y + origin[1] }
    }

    private companion object {
        const val PROJECT = 1L
        const val WIDTH = 21
        const val HEIGHT = 14
        const val PAINT_X = 4
        const val PAINT_Y = 1
        val SCALARS = intArrayOf(0, 1, 64, 128, 153, 254, 255)

        // Unchanged 1,258-byte material-model-export.zip from run 318's API 36 / 16 KB device evidence.
        const val PINNED_SHA256 = "dfddda79f54ab990afcad746c2107b70dc695581f2ecfe3324781be193620798"
        const val MODEL =
            "mtllib surface.mtl\nusemtl painted\n" +
                "v -1.7 -0.6 0\nv 0.9 -0.6 0\nv 0.8 1.3 0\nv -1.4 0.7 0\n" +
                "vt 0.08 0.17\nvt 0.91 0.22\nvt 0.76 0.94\nvt 0.19 0.73\n" +
                "f 1/1 2/2 3/3\nf 1/1 3/3 4/4\n"
        val EXPECTED_UVS = floatArrayOf(0.08f, 0.17f, 0.91f, 0.22f, 0.76f, 0.94f, 0.08f, 0.17f, 0.76f, 0.94f, 0.19f, 0.73f)
    }
}
