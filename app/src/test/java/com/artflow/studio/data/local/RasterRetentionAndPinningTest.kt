package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.runBlocking
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

class RasterRetentionAndPinningTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var storage: ProjectStorage
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = temporary.newFolder("files")
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        storage = ProjectStorage(context)
    }

    @Test
    fun art004_pruneRastersRetainsReferencedRastersWithNonCanonicalPaths() =
        runBlocking {
            val projectId = 1L
            val pngBytes = byteArrayOf(1, 2, 3, 4)

            // Write 3 rasters
            val raster1 = storage.writeRaster(projectId, 1L, 1, pngBytes)
            val raster2 = storage.writeRaster(projectId, 2L, 1, pngBytes)
            val staleRaster = storage.writeRaster(projectId, 3L, 1, pngBytes)

            assertTrue(storage.rasterExists(projectId, raster1))
            assertTrue(storage.rasterExists(projectId, raster2))
            assertTrue(storage.rasterExists(projectId, staleRaster))

            // Create document that references raster1 using a non-canonical path with redundant slashes and ./
            val nonCanonicalPath = "./${raster1.replace("layers/", "layers//")}"
            val layer1 = Layer(id = 1L, name = "Layer 1", index = 0, rasterFile = nonCanonicalPath)
            val doc =
                CanvasDocument(
                    width = 64,
                    height = 64,
                    dpi = 72,
                    backgroundColor = 0,
                    activeLayerId = 1L,
                    nextLayerId = 3L,
                    layers = listOf(layer1),
                )
            storage.saveDocument(projectId, doc)

            // keep parameter also passes raster2 with leading slash and ./
            val keepNonCanonical = setOf("/$raster2", "./$raster2")

            // Prune rasters
            val deleted = storage.pruneRasters(projectId, keepNonCanonical)

            // staleRaster should be pruned (1 file)
            assertEquals(1, deleted)

            // raster1 and raster2 must both still exist!
            assertTrue("Referenced raster with non-canonical path in document must be retained", storage.rasterExists(projectId, raster1))
            assertTrue("Active raster passed in keep with non-canonical path must be retained", storage.rasterExists(projectId, raster2))
            assertFalse("Unreferenced stale raster must be pruned", storage.rasterExists(projectId, staleRaster))
        }

    @Test
    fun art005_rasterPinningProtectsInFlightExportFromConcurrentPruning() =
        runBlocking {
            val projectId = 2L
            val pngBytes = byteArrayOf(5, 6, 7, 8)

            // Write an export snapshot raster
            val inFlightRaster = storage.writeRaster(projectId, 10L, 1, pngBytes)
            val staleRaster = storage.writeRaster(projectId, 11L, 1, pngBytes)

            // The document on disk does NOT reference inFlightRaster (e.g. earlier revision or blank)
            val doc =
                CanvasDocument(
                    width = 64,
                    height = 64,
                    dpi = 72,
                    backgroundColor = 0,
                    activeLayerId = 1L,
                    nextLayerId = 2L,
                    layers = listOf(Layer(id = 1L, name = "Base", index = 0)),
                )
            storage.saveDocument(projectId, doc)

            // Pin the inFlightRaster
            storage.pinRasters(projectId, listOf(inFlightRaster))

            // Concurrent prune passes empty keep set
            val deleted1 = storage.pruneRasters(projectId, emptySet())

            // Only staleRaster should be pruned, inFlightRaster was protected by pin!
            assertEquals(1, deleted1)
            assertTrue("Pinned in-flight raster must survive pruning", storage.rasterExists(projectId, inFlightRaster))
            assertFalse("Unreferenced raster must be deleted", storage.rasterExists(projectId, staleRaster))

            // Now unpin after export completes
            storage.unpinRasters(projectId, listOf(inFlightRaster))

            // Next prune should now clean up inFlightRaster
            val deleted2 = storage.pruneRasters(projectId, emptySet())
            assertEquals(1, deleted2)
            assertFalse("Unpinned raster should be pruned on next prune", storage.rasterExists(projectId, inFlightRaster))
        }

    @Test
    fun savedAndRecoveryAssetsWithParentSegmentsSurvivePruning() =
        runBlocking {
            val projectId = 3L
            val saved = storage.writeRaster(projectId, 1, 1, byteArrayOf(1))
            val recovered = storage.writeRaster(projectId, 2, 1, byteArrayOf(2))
            val mask = storage.writeAuxiliaryImage(projectId, 2, "mask", 1, byteArrayOf(3))
            val paint = storage.writeAuxiliaryBinary(projectId, 2, "paint", 1, "phys", byteArrayOf(4))
            val stale = storage.writeRaster(projectId, 3, 1, byteArrayOf(5))
            storage.saveDocument(projectId, document(Layer(id = 1, name = "Saved", index = 0, rasterFile = alias(saved))))
            storage.saveAutosave(
                projectId,
                document(
                    Layer(
                        id = 2,
                        name = "Recoverable",
                        index = 0,
                        rasterFile = alias(recovered),
                        maskFile = alias(mask),
                        physicalPaintFile = alias(paint),
                    ),
                ),
            )

            assertEquals(1, storage.pruneRasters(projectId, emptySet()))

            listOf(saved, recovered, mask, paint).forEach { path ->
                assertTrue("A manifest still owns $path", storage.rasterExists(projectId, path))
                assertTrue("The recorded alias must stay readable", storage.readRaster(projectId, alias(path))!!.isNotEmpty())
            }
            assertFalse(storage.rasterExists(projectId, stale))
        }

    @Test
    fun aliasedExportPinsShareTheCanonicalReferenceCount() =
        runBlocking {
            val projectId = 4L
            val raster = storage.writeRaster(projectId, 1, 1, byteArrayOf(1))
            storage.pinRasters(projectId, listOf(raster))
            storage.pinRasters(projectId, listOf(alias(raster)))

            assertEquals(0, storage.pruneRasters(projectId, emptySet()))
            storage.unpinRasters(projectId, listOf(raster))
            assertEquals(0, storage.pruneRasters(projectId, emptySet()))
            assertTrue(storage.rasterExists(projectId, raster))

            storage.unpinRasters(projectId, listOf(alias(raster)))
            assertEquals(1, storage.pruneRasters(projectId, emptySet()))
            assertFalse(storage.rasterExists(projectId, raster))
        }

    @Test
    fun activeAliasedRastersRemainRetainedAndInvalidKeepPathsFailBeforeDeletion() =
        runBlocking {
            val projectId = 5L
            val active = storage.writeRaster(projectId, 1, 1, byteArrayOf(1))
            val stale = storage.writeRaster(projectId, 2, 1, byteArrayOf(2))

            val failure = runCatching { storage.pruneRasters(projectId, setOf("layers/../../outside.png")) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(storage.rasterExists(projectId, active))
            assertTrue(storage.rasterExists(projectId, stale))

            assertEquals(1, storage.pruneRasters(projectId, setOf(alias(active))))
            assertTrue(storage.rasterExists(projectId, active))
            assertFalse(storage.rasterExists(projectId, stale))
        }

    private fun alias(path: String) = path.replace("layers/", "layers/unused/../")

    private fun document(layer: Layer) =
        CanvasDocument(
            width = 64,
            height = 64,
            dpi = 72,
            backgroundColor = 0,
            activeLayerId = layer.id,
            nextLayerId = layer.id + 1,
            layers = listOf(layer),
        )
}
