package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.KnownIccProfiles
import com.artflow.studio.core.export.BinaryPlist
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.KeyedArchive
import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.LayerImports
import com.artflow.studio.data.export.ProcreateImport
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.layer.BlendMode
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
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** Actual editor export keeps native P3 channels and the native hierarchy, then reopens from disk. */
@RunWith(AndroidJUnit4::class)
class ProcreateNativeExportDeviceTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var storage: ProjectStorage

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "native-export-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
    }

    @After
    fun tearDown() = StorageFileTree.delete(directory)

    @Test
    fun p3ExportKeepsNativeChannelsAndHiddenNestedGroupsThroughImportAndDiskReopen() =
        runBlocking(Dispatchers.Main) {
            val native = IntArray(WIDTH * HEIGHT) { NATIVE[(it % WIDTH) / BAND] }
            val source = CanvasRepositoryImpl(storage)
            val output: ByteArray
            try {
                source.loadOrCreate(SOURCE, WIDTH, HEIGHT, 144)
                assertTrue(source.setColorProfile(ColorProfile.DISPLAY_P3, undoable = false))
                source.setCanvasBackgroundColor(BACKGROUND, ColorProfile.DISPLAY_P3)
                val base = source.getActiveLayerId()
                source.setLayerName(base, "Native bands")
                assertTrue(source.applyRasterEdit(base, "Fixture colours") { native.copyInto(it.pixels) })
                val ink = source.addLayer("Concealed ink").id
                assertTrue(source.applyRasterEdit(ink, "Fixture hidden colour") { it.pixels.fill(HIDDEN) })
                val inner = requireNotNull(source.groupLayers(listOf(ink)))
                source.setLayerName(inner, "Concealed")
                source.setLayerVisibility(inner, false)
                val outer = requireNotNull(source.groupLayers(listOf(inner)))
                source.setLayerName(outer, "Outer")
                source.setLayerOpacity(outer, 0.5f)
                assertTree(source, native)
                assertArrayEquals(native, requireNotNull(source.compositeBuffer()).pixels)
                val exported =
                    renderedExport(
                        source,
                        ArtworkExporter(context, storage),
                        SOURCE,
                        "Native export",
                        ExportOptions(
                            format = ExportFormat.PROCREATE,
                            colorProfile = ColorProfile.DISPLAY_P3,
                            backgroundColor = BACKGROUND,
                            flattenOntoBackground = true,
                            dpi = 144,
                        ),
                    ).getOrThrow()
                output = File(exported.filePath).readBytes()
                assertArchive(output)
                assertArrayEquals(native, requireNotNull(source.layerPixels(base)).pixels)
            } finally {
                source.dispose()
            }
            File(TestEvidence.directory(), "procreate-native-export.procreate").writeBytes(output)

            val imported = CanvasRepositoryImpl(storage)
            try {
                imported.loadOrCreate(REOPENED, WIDTH, HEIGHT, 144)
                ProcreateImport.open(output.inputStream(), directory).use { opened ->
                    val document = opened.document
                    assertEquals(ColorProfile.DISPLAY_P3, document.colorProfile)
                    assertFalse(document.hasUnsupportedColorProfile)
                    assertEquals(BACKGROUND, document.background)
                    assertEquals(listOf("Outer", "Native bands"), document.nodes.map { it.name })
                    val outer = document.nodes.first() as ProcreateReader.Group
                    assertFalse(outer.children.single().visible)
                    assertArrayEquals(native, requireNotNull(document.composite()).pixels)
                    val result = LayerImports.importProcreate(imported, document, preserveDocumentProfile = true)
                    assertEquals(2, result.layers)
                    assertFalse(result.flattened)
                    assertFalse(result.hasUnsupportedColorProfile)
                }
                assertTree(imported, native)
                assertEquals(ColorProfile.DISPLAY_P3, imported.getColorProfile())
                assertEquals(BACKGROUND, imported.getBackgroundColor())
                assertTrue(imported.saveCanvas(REOPENED) != null)
            } finally {
                imported.dispose()
            }
            val reopened = CanvasRepositoryImpl(ProjectStorage(context))
            try {
                requireNotNull(reopened.loadCanvas(REOPENED))
                assertEquals(ColorProfile.DISPLAY_P3, reopened.getColorProfile())
                assertEquals(BACKGROUND, reopened.getBackgroundColor())
                assertTree(reopened, native)
                assertArrayEquals(native, requireNotNull(reopened.compositeBuffer()).pixels)
                val png =
                    renderedExport(
                        reopened,
                        ArtworkExporter(context, storage),
                        REOPENED,
                        "Native export preview",
                        ExportOptions(format = ExportFormat.PNG, colorProfile = reopened.getColorProfile()),
                    ).getOrThrow()
                val bytes = File(png.filePath).readBytes()
                val displayed = requireNotNull(BitmapPixelBridge.fromEncodedBytes(bytes))
                for (at in displayed.pixels.indices) {
                    val expected = SRGB[(at % WIDTH) / BAND]
                    assertEquals(255, displayed.pixels[at] ushr 24)
                    for (shift in intArrayOf(16, 8, 0)) {
                        assertTrue(
                            "Independent ICC colour at " + at + ", channel " + shift,
                            abs(((displayed.pixels[at] ushr shift) and 255) - ((expected ushr shift) and 255)) <= 1,
                        )
                    }
                }
                File(TestEvidence.directory(), "procreate-native-export-preview.png").writeBytes(bytes)
            } finally {
                reopened.dispose()
            }
            File(
                TestEvidence.directory(),
                "procreate-native-export-reopened.artflow",
            ).writeBytes(storage.documentFile(REOPENED).readBytes())
        }

    @Test
    fun aHiddenNativeBackgroundReplacesNewCanvasWhiteWhileInsertionKeepsItsBackground() =
        runBlocking(Dispatchers.Main) {
            val native = IntArray(WIDTH * HEIGHT) { at -> if (at % WIDTH < WIDTH / 2) 0 else NATIVE[0] }
            val source = CanvasRepositoryImpl(storage)
            val output: ByteArray
            try {
                source.loadOrCreate(643, WIDTH, HEIGHT, 72)
                source.setCanvasBackgroundColor(0)
                source.setLayerName(source.getActiveLayerId(), "Transparent artwork")
                assertTrue(source.applyRasterEdit(source.getActiveLayerId(), "Fixture") { native.copyInto(it.pixels) })
                val exported =
                    renderedExport(
                        source,
                        ArtworkExporter(context, storage),
                        643,
                        "Hidden background",
                        ExportOptions(format = ExportFormat.PROCREATE),
                    ).getOrThrow()
                output = File(exported.filePath).readBytes()
            } finally {
                source.dispose()
            }
            File(TestEvidence.directory(), "procreate-hidden-background-export.procreate").writeBytes(output)

            val imported = CanvasRepositoryImpl(storage)
            try {
                imported.loadOrCreate(644, WIDTH, HEIGHT, 72)
                assertEquals(0xFFFFFFFF.toInt(), imported.getBackgroundColor())
                ProcreateImport.open(output.inputStream(), directory).use { opened ->
                    assertEquals(null, opened.document.background)
                    LayerImports.importProcreate(imported, opened.document, preserveDocumentProfile = true)
                }
                assertEquals(0, imported.getBackgroundColor())
                assertArrayEquals(native, requireNotNull(imported.compositeBuffer()).pixels)
                assertTrue(imported.saveCanvas(644) != null)
            } finally {
                imported.dispose()
            }
            val reopened = CanvasRepositoryImpl(ProjectStorage(context))
            try {
                requireNotNull(reopened.loadCanvas(644))
                assertEquals(0, reopened.getBackgroundColor())
                assertArrayEquals(native, requireNotNull(reopened.compositeBuffer()).pixels)
                val png = storage.flattenedFile(644).readBytes()
                assertArrayEquals(native, requireNotNull(BitmapPixelBridge.fromEncodedBytes(png)).pixels)
                File(TestEvidence.directory(), "procreate-hidden-background-preview.png").writeBytes(png)
            } finally {
                reopened.dispose()
            }
            File(TestEvidence.directory(), "procreate-hidden-background-reopened.artflow").writeBytes(storage.documentFile(644).readBytes())

            val insertion = CanvasRepositoryImpl(storage)
            try {
                insertion.loadOrCreate(645, WIDTH, HEIGHT, 72)
                insertion.setCanvasBackgroundColor(0xFF102030.toInt())
                ProcreateImport.open(output.inputStream(), directory).use { opened ->
                    LayerImports.importProcreate(insertion, opened.document, preserveDocumentProfile = false)
                }
                assertEquals(0xFF102030.toInt(), insertion.getBackgroundColor())
                assertEquals(ColorProfile.SRGB, insertion.getColorProfile())
            } finally {
                insertion.dispose()
            }
        }

    private suspend fun assertTree(
        repository: CanvasRepositoryImpl,
        native: IntArray,
    ) {
        val layers = repository.getAllLayers()
        assertEquals(4, layers.size)
        val outer = layers.single { it.name == "Outer" }
        val inner = layers.single { it.name == "Concealed" }
        val ink = layers.single { it.name == "Concealed ink" }
        val base = layers.single { it.name == "Native bands" }
        assertTrue(outer.isGroup && inner.isGroup)
        assertEquals(BlendMode.PASS_THROUGH, outer.blendMode)
        assertEquals(BlendMode.PASS_THROUGH, inner.blendMode)
        assertEquals(0.5f, outer.opacity, 0f)
        assertTrue(outer.isVisible)
        assertFalse(inner.isVisible)
        assertEquals(outer.id, inner.parentGroupId)
        assertEquals(inner.id, ink.parentGroupId)
        assertEquals(null, base.parentGroupId)
        assertArrayEquals(native, requireNotNull(repository.layerPixels(base.id)).pixels)
        assertArrayEquals(IntArray(WIDTH * HEIGHT) { HIDDEN }, requireNotNull(repository.layerPixels(ink.id)).pixels)
    }

    private fun assertArchive(bytes: ByteArray) {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        val archive = KeyedArchive(BinaryPlist.read(requireNotNull(entries["Document.archive"])))
        val document = requireNotNull(archive.root)

        fun refs(value: Any?) = (requireNotNull(archive.map(value))["NS.objects"] as List<*>).map { it as BinaryPlist.Uid }
        val flat = refs(document["layers"])
        val tree = refs(document["unwrappedLayers"])
        assertEquals(listOf("Concealed ink", "Native bands"), flat.map { archive.string(archive.map(it)?.get("name")) })
        assertEquals(2, flat.toSet().size)
        assertTrue(flat.all { archive.className(it) == "SilicaLayer" })
        val outer = requireNotNull(archive.map(tree.first()))
        val inner = requireNotNull(archive.map(refs(outer["children"]).single()))
        assertEquals(true, inner["isHidden"])
        assertEquals(flat, refs(inner["children"]) + tree.last())
        val profile = requireNotNull(archive.map(document["colorProfile"]))
        assertEquals("SiColorProfile", archive.className(profile))
        assertEquals(
            ColorProfile.DISPLAY_P3,
            KnownIccProfiles.recognize(requireNotNull(archive.data(profile["SiColorProfileArchiveICCDataKey"]))),
        )
    }

    private companion object {
        const val WIDTH = 32
        const val HEIGHT = 16
        const val BAND = 8
        const val SOURCE = 641L
        const val REOPENED = 642L
        const val BACKGROUND = -1040806 // 0xFFF01E5A, native Display P3.
        const val HIDDEN = -65536
        val NATIVE = intArrayOf(0xFFE6462D.toInt(), 0xFF14B478.toInt(), 0xFF2846E6.toInt(), 0xFFBE1EB9.toInt())

        // LittleCMS 2.19 relative-colorimetric results for the four native P3 colours.
        val SRGB = intArrayOf(0xFFFA3117.toInt(), 0xFF00B773.toInt(), 0xFF1D47EF.toInt(), 0xFFD000BF.toInt())
    }
}
