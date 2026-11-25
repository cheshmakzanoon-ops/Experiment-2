package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.render.PhysicalPaintEngine
import com.artflow.studio.core.render.PhysicalPaintPlane
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/**
 * One asset-dependency contract: duplication, packaging, import validation and pruning must all see the
 * physical-paint plane a manifest names, not just colour and mask rasters.
 */
class PhysicalAssetCompletenessTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var storage: ProjectStorage
    private lateinit var files: File
    private val pixels = byteArrayOf(3, 1, 4, 1, 5, 9)
    private lateinit var plane: ByteArray
    private lateinit var raster: String
    private lateinit var physical: String
    private lateinit var document: CanvasDocument

    @Before fun setup() {
        runBlocking {
            files = temporary.newFolder("files")
            val context = mock(Context::class.java)
            `when`(context.filesDir).thenReturn(files)
            `when`(context.cacheDir).thenReturn(temporary.newFolder("cache"))
            storage = ProjectStorage(context)
            val paint = PhysicalPaintPlane(64, 64)
            PhysicalPaintEngine.depositWetness(paint, 20f, 20f, 8f, waterAmount = 0.9f, pigmentLoad = 0.6f)
            plane = paint.encodeBinary()
            raster = storage.writeRaster(5L, 1L, 1, pixels)
            physical = storage.writeAuxiliaryBinary(5L, 1L, "phys", 1, "phys", plane)
            document =
                CanvasDocument(
                    width = 64,
                    height = 64,
                    dpi = 72,
                    backgroundColor = -1,
                    activeLayerId = 1,
                    nextLayerId = 2,
                    layers = listOf(Layer(1, "Watercolor", 0, rasterFile = raster, physicalPaintFile = physical)),
                )
            storage.saveDocument(5L, document)
        }
    }

    @Test fun referencedFilesNamesEveryAssetKind() {
        assertEquals(listOf(raster, physical), storage.referencedFiles(document))
    }

    @Test fun duplicatedProjectOwnsItsPhysicalPlane() =
        runBlocking {
            storage.copyProject(5L, 6L)
            val copied = requireNotNull(storage.loadDocument(6L))
            val copiedPhysical = requireNotNull(copied.layers.single().physicalPaintFile)
            assertArrayEquals(plane, storage.readRaster(6L, copiedPhysical))
            // Fresh process: the copy must reopen with no reference to the source project.
            val reopened = ProjectStorage(contextFor(files))
            assertNotNull(reopened.loadDocument(6L))
            assertTrue(storage.resolve(6L, copiedPhysical).isFile)
        }

    @Test fun packageAcceptsAndRestoresPhysicalPlane() =
        runBlocking {
            val packaged =
                ArtflowPackage.write(
                    "Watercolor",
                    mapOf(
                        ArtflowPackage.DOCUMENT to CanvasDocumentCodec.encode(document),
                        raster to pixels,
                        physical to plane,
                    ),
                )
            val contents = ArtflowPackage.read(packaged)
            assertArrayEquals(plane, contents.files.getValue(physical))
            val imported = storage.importProject(7L, contents.files)
            assertEquals(physical, imported.layers.single().physicalPaintFile)
            assertArrayEquals(plane, storage.readRaster(7L, physical))
        }

    @Test fun importRejectsAManifestWhoseReferencedPhysicalPlaneIsMissing() {
        val packaged =
            ArtflowPackage.write(
                "Broken",
                mapOf(ArtflowPackage.DOCUMENT to CanvasDocumentCodec.encode(document), raster to pixels),
            )
        val contents = ArtflowPackage.read(packaged)
        val failure = runCatching { runBlocking { storage.importProject(8L, contents.files) } }.exceptionOrNull()
        assertNotNull("An archive naming a physical plane it does not contain must not import", failure)
        assertTrue(!storage.projectDirectoryExists(8L) || !storage.documentFile(8L).exists())
    }

    @Test fun pruningKeepsTheReferencedPhysicalPlane() =
        runBlocking {
            storage.pruneRasters(5L, emptySet())
            assertTrue(storage.resolve(5L, physical).isFile)
            assertTrue(storage.resolve(5L, raster).isFile)
        }

    private fun contextFor(root: File): Context =
        mock(Context::class.java).also {
            `when`(it.filesDir).thenReturn(root)
            `when`(it.cacheDir).thenReturn(temporary.newFolder())
        }
}
