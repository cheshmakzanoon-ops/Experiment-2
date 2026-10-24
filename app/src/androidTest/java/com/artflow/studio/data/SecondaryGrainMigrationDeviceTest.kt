package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.GrainStorage
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.GrainBlend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest

/** Real storage and Android PNG decoding for the checksum-first v3 vector-grain migration. */
@RunWith(AndroidJUnit4::class)
class SecondaryGrainMigrationDeviceTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "secondary-grain-migration-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
        assertEquals(TILE_ID, GrainStorage.save(GrainStorage.directory(context.filesDir), grayTile()))
    }

    @After
    fun tearDown() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            CustomGrains.register(TILE_ID, grayTile())
            StorageFileTree.delete(directory)
        }

    @Test
    fun signedV3VectorsKeepHistoricalCoverageWhileNewV4StrokesRetainTheirGrainModes() =
        runBlocking(Dispatchers.Main) {
            // Frozen bytes were generated with the published 2d57dbd v3 codec and renderer.
            // The asset and whole-image digest are historical ArtFlow evidence, not a native-app oracle.
            val original =
                InstrumentationRegistry
                    .getInstrumentation()
                    .context
                    .assets
                    .open("migration/secondary-grain-v3.artflow")
                    .use { it.readBytes() }
            assertEquals(4930, original.size)
            assertEquals(ORIGINAL_SHA, sha256(original))
            assertSigned(original, 3)
            evidence("secondary-grain-v3-before-load.artflow", original)
            val source = Json.decodeFromString(CanvasDocument.serializer(), original.toString(Charsets.UTF_8))
            val originalLayer = source.layers.single()
            val originalStroke = originalLayer.strokes.single()
            val originalBrush = originalStroke.brushParams
            val originalDual = requireNotNull(originalBrush.dual)
            assertEquals(GrainBlend.SUBTRACT, originalDual.params.grainBlend)
            assertEquals(TILE_ID, originalDual.params.textureId)
            val legacyLayer = originalLayer.id
            val expectedMigrated =
                originalBrush.copy(dual = originalDual.copy(params = originalDual.params.copy(grainBlend = GrainBlend.MULTIPLY)))
            val documentFile = storage.documentFile(PROJECT_ID)
            documentFile.writeBytes(original)

            // Both old values would migrate to Multiply. Rejecting this otherwise-valid edit proves
            // checksum validation happens before normalization can make the documents equivalent.
            val tampered =
                original
                    .toString(Charsets.UTF_8)
                    .replace("\"grainBlend\":\"SUBTRACT\"", "\"grainBlend\":\"HARD_MIX\"")
                    .toByteArray()
            assertNotEquals(original.toString(Charsets.UTF_8), tampered.toString(Charsets.UTF_8))
            documentFile.writeBytes(tampered)
            val error = runCatching { storage.loadDocument(PROJECT_ID) }.exceptionOrNull()
            assertTrue("Tampered legacy metadata must fail its checksum", error is IllegalArgumentException)
            assertTrue("Checksum rejection must identify the checksum", error?.message.orEmpty().contains("checksum"))
            assertArrayEquals(tampered, documentFile.readBytes())
            documentFile.writeBytes(original)

            reloadGrainFromDisk()
            requireNotNull(repository.loadCanvas(PROJECT_ID))
            val legacyPixels = pixels(legacyLayer)
            assertEquals(38, legacyPixels[CENTRE_INDEX] ushr 24)
            assertEquals(LEGACY_PIXELS_SHA, pixelHash(legacyPixels))
            assertArrayEquals("Loading must not rewrite the signed v3 file", original, documentFile.readBytes())
            assertMigratedBrush(expectedMigrated)

            repository.saveCanvas(PROJECT_ID)
            val migrated = documentFile.readBytes()
            assertSigned(migrated, CanvasDocument.CURRENT_VERSION)
            evidence("secondary-grain-v4-migrated.artflow", migrated)
            reopen()
            assertArrayEquals(legacyPixels, pixels(legacyLayer))
            assertMigratedBrush(expectedMigrated)
            assertArrayEquals(migrated, documentFile.readBytes())

            // New strokes on the saved/reopened document use the secondary modes introduced in v4.
            val subtractLayer = paint("New Subtract", originalBrush, GrainBlend.SUBTRACT)
            val subtractPixels = pixels(subtractLayer)
            assertEquals(13, subtractPixels[CENTRE_INDEX] ushr 24)
            val hardMixLayer = paint("New Hard Mix", originalBrush, GrainBlend.HARD_MIX)
            val hardMixPixels = pixels(hardMixLayer)
            assertEquals(128, hardMixPixels[CENTRE_INDEX] ushr 24)
            val composite = requireNotNull(repository.compositeBuffer())
            // Source-over of the three independently checked layers: 38 -> 49 -> 152.
            assertEquals(152, composite.pixels[CENTRE_INDEX] ushr 24)
            val expectedLayers = mapOf(legacyLayer to legacyPixels, subtractLayer to subtractPixels, hardMixLayer to hardMixPixels)
            repository.saveCanvas(PROJECT_ID)
            val saved = documentFile.readBytes()
            assertSigned(saved, CanvasDocument.CURRENT_VERSION)
            evidence("secondary-grain-v4-new-strokes.artflow", saved)
            val png = requireNotNull(storage.loadFlattenedBytes(PROJECT_ID))
            evidence("secondary-grain-v4-new-strokes.png", png)
            val decoded = requireNotNull(BitmapPixelBridge.fromEncodedBytes(png))
            assertEquals(WIDTH, decoded.width)
            assertEquals(HEIGHT, decoded.height)
            assertArrayEquals("Saved PNG must contain the actual composite", composite.pixels, decoded.pixels)

            reopen()
            expectedLayers.forEach { (id, expected) -> assertArrayEquals("Reopened layer $id", expected, pixels(id)) }
            assertArrayEquals(composite.pixels, requireNotNull(repository.compositeBuffer()).pixels)
            assertArrayEquals("Reopening must not rewrite a valid saved manifest", saved, documentFile.readBytes())
            assertArrayEquals(png, requireNotNull(storage.loadFlattenedBytes(PROJECT_ID)))
        }

    private suspend fun assertMigratedBrush(expected: BrushParams) {
        val document = requireNotNull(storage.loadDocument(PROJECT_ID))
        assertEquals(CanvasDocument.CURRENT_VERSION, document.version)
        val layer = document.layers.single()
        val actual = layer.strokes.single().brushParams
        // Full parameter equality also protects primary grain, retained source metadata and images.
        assertEquals(expected, actual)
        assertEquals(GrainBlend.MULTIPLY, actual.grainBlend)
        assertEquals(GrainBlend.MULTIPLY, requireNotNull(actual.dual).params.grainBlend)
    }

    private suspend fun paint(
        name: String,
        original: BrushParams,
        blend: GrainBlend,
    ): Long {
        val layer = repository.addLayer(name).id
        val dual = requireNotNull(original.dual)
        val brush = original.copy(dual = dual.copy(params = dual.params.copy(grainBlend = blend)))
        repository.setStrokeColor(INK)
        val stroke = repository.beginStroke(30.5f, 30.5f, 1f, brush, layer, timestamp = 0L)
        assertTrue(stroke > 0)
        repository.endStroke(stroke)
        return layer
    }

    private suspend fun reopen() {
        repository.dispose()
        reloadGrainFromDisk()
        storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
        requireNotNull(repository.loadCanvas(PROJECT_ID))
    }

    private suspend fun pixels(layerId: Long): IntArray = requireNotNull(repository.layerPixels(layerId)).pixels

    private fun reloadGrainFromDisk() {
        val grains = GrainStorage.directory(context.filesDir)
        val bytes = File(grains, "$TILE_ID.grain").readBytes()
        assertArrayEquals(grayTile().values, bytes)
        assertEquals(TILE_SHA, sha256(bytes))
        CustomGrains.register(TILE_ID, CustomGrains.Tile(CustomGrains.TILE, ByteArray(bytes.size)))
        val stale = requireNotNull(CustomGrains.get(TILE_ID))
        assertTrue("A fresh load must replace the deliberately stale cache", stale.values.all { it == 0.toByte() })
        GrainStorage.loadAll(grains)
        assertArrayEquals(bytes, requireNotNull(CustomGrains.get(TILE_ID)).values)
    }

    /** Independent raw-byte check: do not reuse the encoder whose migration is under test. */
    private fun assertSigned(
        bytes: ByteArray,
        version: Int,
    ) {
        val text = bytes.toString(Charsets.UTF_8)
        val json = JSONObject(text)
        assertEquals(version, json.getInt("version"))
        val checksum = json.getString("checksum")
        assertTrue(checksum.matches(Regex("[0-9a-f]{64}")))
        val token = "\"checksum\":\"$checksum\""
        assertEquals(1, Regex(Regex.escape(token)).findAll(text).count())
        val unsigned = text.replace(token, "\"checksum\":null").toByteArray()
        assertEquals("Raw signed manifest checksum", sha256(unsigned), checksum)
    }

    private fun evidence(
        name: String,
        bytes: ByteArray,
    ) {
        File(TestEvidence.directory(), name).writeBytes(bytes)
    }

    private fun grayTile(): CustomGrains.Tile =
        CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { 128.toByte() })

    private fun pixelHash(pixels: IntArray): String {
        val buffer = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
        pixels.forEach { buffer.putInt(it) }
        return sha256(buffer.array())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val PROJECT_ID = 9_610_001L
        const val WIDTH = 96
        const val HEIGHT = 64
        const val CENTRE_INDEX = 30 * WIDTH + 30
        const val INK = 0xFF114477.toInt()
        const val TILE_ID = "custom-f98fbcc085b62325"
        const val TILE_SHA = "086317bd0c9bcd77537c8a6cfe66f8e7dd84ded162673903b29f9b3f5e5ea244"
        const val ORIGINAL_SHA = "05fc40e5ae755d6947f4b55e6c310cef913d27bf01dcbbeaba670fcc7febc624"
        const val LEGACY_PIXELS_SHA = "e0b357972e668820aa76f54413f9ef9b92d6aa5ec5b0a4a9f27e1c392a7f334c"
    }
}
