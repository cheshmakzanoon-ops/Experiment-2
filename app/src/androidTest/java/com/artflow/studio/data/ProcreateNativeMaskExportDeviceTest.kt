package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.export.BinaryPlist
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.KeyedArchive
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.LayerImports
import com.artflow.studio.data.export.ProcreateImport
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.renderedExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** The real export path must retain editable native masks, including disabled masks and ambiguous tile lengths. */
@RunWith(AndroidJUnit4::class)
class ProcreateNativeMaskExportDeviceTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var storage: ProjectStorage

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "native-mask-export-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
    }

    @After
    fun tearDown() = StorageFileTree.delete(directory)

    @Test
    fun nativeMaskExportRetainsTypedCoverageAndEnabledStateAfterFreshReopen(): Unit =
        runBlocking(Dispatchers.Main) {
            writePixels("native-mask-expected-enabled.png", expectedPixels(true))
            writePixels("native-mask-expected-disabled.png", expectedPixels(false))
            val source = CanvasRepositoryImpl(storage)
            try {
                source.loadOrCreate(SOURCE, SIDE, SIDE, 72)
                source.setCanvasBackgroundColor(0)
                val id = source.getActiveLayerId()
                source.setLayerName(id, "Editable native mask")
                assertTrue(source.applyRasterEdit(id, "Fixture ink") { expectedPixels(false).copyInto(it.pixels) })
                assertTrue(source.addLayerMask(id, SelectionMask(SIDE, SIDE, expectedCoverage())))
                for (enabled in listOf(true, false)) {
                    source.setLayerMaskEnabled(id, enabled)
                    val label = if (enabled) "enabled" else "disabled"
                    val result =
                        renderedExport(
                            source,
                            ArtworkExporter(context, storage),
                            SOURCE,
                            "Native mask $label",
                            ExportOptions(format = ExportFormat.PROCREATE),
                        ).getOrThrow()
                    val bytes = File(result.filePath).readBytes()
                    File(TestEvidence.directory(), "native-mask-$label-export.procreate").writeBytes(bytes)
                    inspectArchive(bytes, enabled, label)
                    importAndReopen(bytes, enabled, label)
                }
                assertArrayEquals(expectedPixels(false), requireNotNull(source.layerPixels(id)).pixels)
            } finally {
                source.dispose()
            }
        }

    private suspend fun importAndReopen(
        bytes: ByteArray,
        enabled: Boolean,
        label: String,
    ) {
        val project = if (enabled) ENABLED_PROJECT else DISABLED_PROJECT
        val imported = CanvasRepositoryImpl(storage)
        try {
            imported.loadOrCreate(project, SIDE, SIDE, 72)
            ProcreateImport.open(bytes.inputStream(), directory).use { opened ->
                val layer = opened.document.layers().single()
                assertEquals(enabled, layer.maskEnabled)
                assertArrayEquals(expectedCoverage(), requireNotNull(opened.document.mask(layer)).coverage)
                assertArrayEquals(expectedPixels(false), opened.document.pixels(layer).pixels)
                val result = LayerImports.importProcreate(imported, opened.document, preserveDocumentProfile = true)
                assertEquals(1, result.layers)
                assertFalse(result.flattened)
            }
            assertEditableMask(imported, enabled)
            assertTrue(imported.saveCanvas(project) != null)
        } finally {
            imported.dispose()
        }
        File(TestEvidence.directory(), "native-mask-$label-reopened.artflow").writeBytes(storage.documentFile(project).readBytes())
        val freshStorage = ProjectStorage(context)
        val reopened = CanvasRepositoryImpl(freshStorage)
        try {
            requireNotNull(reopened.loadCanvas(project))
            val id = assertEditableMask(reopened, enabled)
            assertPng(reopened, freshStorage, project, enabled, "native-mask-$label-reopened.png")
            assertTrue(reopened.setLayerMaskEnabled(id, !enabled))
            assertPng(reopened, freshStorage, project, !enabled, "native-mask-$label-toggled.png")
            assertTrue(reopened.setLayerMaskEnabled(id, enabled))
            assertEditableMask(reopened, enabled)
        } finally {
            reopened.dispose()
        }
    }

    private suspend fun assertEditableMask(
        repository: CanvasRepositoryImpl,
        enabled: Boolean,
    ): Long {
        val layer = repository.getAllLayers().single()
        assertTrue(layer.hasMask())
        assertEquals(enabled, layer.maskEnabled)
        assertArrayEquals(expectedPixels(false), requireNotNull(repository.layerPixels(layer.id)).pixels)
        val snapshot = repository.exportSnapshot(false, false, true, preserveLayerMasks = true)
        val mask = requireNotNull(snapshot.layerMasks[layer.id])
        assertArrayEquals(expectedCoverage(), ByteArray(mask.pixels.size) { (mask.pixels[it] ushr 24).toByte() })
        assertArrayEquals(expectedPixels(enabled), snapshot.frames.single().pixels)
        return layer.id
    }

    private suspend fun assertPng(
        repository: CanvasRepositoryImpl,
        freshStorage: ProjectStorage,
        project: Long,
        enabled: Boolean,
        name: String,
    ) {
        val exported =
            renderedExport(
                repository,
                ArtworkExporter(context, freshStorage),
                project,
                name.substringBeforeLast('.'),
                ExportOptions(format = ExportFormat.PNG),
            ).getOrThrow()
        val png = File(exported.filePath).readBytes()
        File(TestEvidence.directory(), name).writeBytes(png)
        val decoded = requireNotNull(BitmapPixelBridge.fromEncodedBytes(png))
        assertEquals(SIDE, decoded.width)
        assertEquals(SIDE, decoded.height)
        assertArrayEquals(expectedPixels(enabled), decoded.pixels)
    }

    /** Inspect the archive and decode raw tiles without ProcreateReader or the production LZ4 codec. */
    private fun inspectArchive(
        bytes: ByteArray,
        enabled: Boolean,
        label: String,
    ) {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        val archive = KeyedArchive(BinaryPlist.read(requireNotNull(entries["Document.archive"])))
        val document = requireNotNull(archive.root)
        val ink = requireNotNull(archive.map(archive.array(document["layers"]).single()))
        val mask = requireNotNull(archive.map(ink["mask"])) { "The editor export must retain an editable mask" }
        val uuid = requireNotNull(archive.string(mask["UUID"]))
        val maskFiles = entries.keys.filter { it.startsWith("$uuid/") }
        val raw = decodeTile(requireNotNull(entries["$uuid/0~0.lz4"]))
        File(TestEvidence.directory(), "native-mask-$label-tile.gray8").writeBytes(raw)
        File(TestEvidence.directory(), "native-mask-$label-metadata.txt").writeText(
            "size=${archive.string(document["size"])}\ntileSize=${document["tileSize"]}\n" +
                "maskType=${mask["type"]}\nmaskHidden=${mask["hidden"]}\nmaskTileCount=${maskFiles.size}\nrawMaskBytes=${raw.size}\n",
        )
        if (raw.size == TILE * TILE) {
            val gray = IntArray(raw.size) { 0xFF000000.toInt() or ((raw[it].toInt() and 255) * 0x010101) }
            File(TestEvidence.directory(), "native-mask-$label-tile.png").writeBytes(
                BitmapPixelBridge.toPngBytes(PixelBuffer(TILE, TILE, gray)),
            )
        }
        assertEquals("{$SIDE, $SIDE}", archive.string(document["size"]))
        assertEquals(TILE.toLong(), document["tileSize"])
        assertEquals(3L, mask["type"])
        assertEquals(!enabled, mask["hidden"])
        assertEquals(listOf("$uuid/0~0.lz4"), maskFiles)
        // 256² bytes also fit a cropped 128² RGBA tile. The declared native mask type must disambiguate it.
        assertEquals(SIDE * SIDE * 4, raw.size)
        val expected =
            ByteArray(TILE * TILE) { at ->
                if (at % TILE < SIDE && at / TILE < SIDE) coverageAt(at % TILE, at / TILE) else 255.toByte()
            }
        assertArrayEquals(expected, raw)
        val inkUuid = requireNotNull(archive.string(ink["UUID"]))
        val composite = requireNotNull(archive.map(document["composite"]))
        val compositeUuid = requireNotNull(archive.string(composite["UUID"]))
        assertRgbaTile(decodeTile(requireNotNull(entries["$inkUuid/0~0.lz4"])), enabled = false)
        assertRgbaTile(decodeTile(requireNotNull(entries["$compositeUuid/0~0.lz4"])), enabled)
    }

    private fun assertRgbaTile(
        raw: ByteArray,
        enabled: Boolean,
    ) {
        assertEquals(TILE * TILE * 4, raw.size)
        val expected = ByteArray(raw.size)
        val pixels = expectedPixels(enabled)
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                val pixel = pixels[y * SIDE + x]
                val alpha = pixel ushr 24
                val at = (y * TILE + x) * 4
                expected[at] = if (pixel and 0xFF0000 != 0) alpha.toByte() else 0
                expected[at + 2] = if (pixel and 255 != 0) alpha.toByte() else 0
                expected[at + 3] = alpha.toByte()
            }
        }
        assertArrayEquals(expected, raw)
    }

    /** Independent reader for the writer's bounded single-block Apple container. */
    private fun decodeTile(encoded: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buffer.get(it) }.toString(Charsets.ISO_8859_1)
        val length = buffer.int
        require(length in 1..TILE * TILE * 4)
        val decoded =
            when (magic) {
                "bv41" -> {
                    val packed = ByteArray(buffer.int).also { buffer.get(it) }
                    decodeBlock(packed, length)
                }
                "bvx-" -> ByteArray(length).also { buffer.get(it) }
                else -> error("Unexpected Apple tile framing: $magic")
            }
        assertEquals(4, buffer.remaining())
        assertEquals("bv4$", ByteArray(4).also { buffer.get(it) }.toString(Charsets.ISO_8859_1))
        return decoded
    }

    private fun decodeBlock(
        packed: ByteArray,
        length: Int,
    ): ByteArray {
        val decoded = ByteArray(length)
        var from = 0
        var to = 0

        fun count(initial: Int): Int {
            var total = initial
            if (initial == 15) {
                do {
                    require(from < packed.size)
                    val next = packed[from++].toInt() and 255
                    total += next
                } while (next == 255)
            }
            return total
        }
        while (from < packed.size) {
            val token = packed[from++].toInt() and 255
            val literals = count(token ushr 4)
            require(from + literals <= packed.size && to + literals <= decoded.size)
            packed.copyInto(decoded, to, from, from + literals)
            from += literals
            to += literals
            if (from == packed.size) break
            require(from + 2 <= packed.size)
            val distance = (packed[from].toInt() and 255) or ((packed[from + 1].toInt() and 255) shl 8)
            from += 2
            require(distance in 1..to)
            val matches = count(token and 15) + 4
            require(to + matches <= decoded.size)
            repeat(matches) {
                decoded[to] = decoded[to - distance]
                to++
            }
        }
        assertEquals(length, to)
        return decoded
    }

    private fun expectedCoverage(): ByteArray = ByteArray(SIDE * SIDE) { coverageAt(it % SIDE, it / SIDE) }

    private fun coverageAt(
        x: Int,
        y: Int,
    ): Byte = intArrayOf(0, 128, 255)[(x / 16 + y / 16) % 3].toByte()

    private fun expectedPixels(enabled: Boolean): IntArray =
        IntArray(SIDE * SIDE) { at ->
            val alpha = if (enabled) coverageAt(at % SIDE, at / SIDE).toInt() and 255 else 255
            if (alpha == 0) 0 else (alpha shl 24) or (if (at % SIDE < SIDE / 2) 0xFF0000 else 0x0000FF)
        }

    private fun writePixels(
        name: String,
        pixels: IntArray,
    ) {
        File(TestEvidence.directory(), name).writeBytes(BitmapPixelBridge.toPngBytes(PixelBuffer(SIDE, SIDE, pixels)))
    }

    private companion object {
        const val SIDE = 128
        const val TILE = 256
        const val SOURCE = 663L
        const val ENABLED_PROJECT = 664L
        const val DISABLED_PROJECT = 665L
    }
}
