package com.artflow.studio.data.renderer.gpu

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualTileCanvasTest {
    @Test
    fun tileDimensionsAndIndexMapping() {
        val canvas = VirtualTileCanvas(width = 600, height = 800, tileSize = 256)
        assertEquals(3, canvas.columns) // ceil(600 / 256)
        assertEquals(4, canvas.rows) // ceil(800 / 256)
        assertEquals(12, canvas.totalTiles)

        // Bounds of first and corner tiles
        val t0 = canvas.tileBounds(0, 0)
        assertEquals(0, t0.left)
        assertEquals(0, t0.top)
        assertEquals(255, t0.right)
        assertEquals(255, t0.bottom)

        val tLast = canvas.tileBounds(2, 3)
        assertEquals(512, tLast.left)
        assertEquals(768, tLast.top)
        assertEquals(599, tLast.right)
        assertEquals(799, tLast.bottom)
    }

    @Test
    fun sparseAllocationSavesMemory() {
        val canvas = VirtualTileCanvas(width = 4096, height = 4096, tileSize = 256)
        assertEquals(16 * 16, canvas.totalTiles)
        assertEquals(0, canvas.allocatedTileCount)
        assertEquals(0L, canvas.memoryUsageBytes)

        // Allocate only one tile at (5, 5)
        val tile = canvas.getOrCreateTile(5, 5)
        assertNotNull(tile)
        assertEquals(256, tile.width)
        assertEquals(256, tile.height)
        assertEquals(1, canvas.allocatedTileCount)
        assertEquals(256L * 256L * 4L, canvas.memoryUsageBytes)

        // Getting untagged tile returns null without allocation
        assertNull(canvas.getTile(0, 0))
        assertEquals(1, canvas.allocatedTileCount)
    }

    @Test
    fun markDirtyAndExpansion() {
        val canvas = VirtualTileCanvas(width = 512, height = 512, tileSize = 256)
        assertNull(canvas.dirtyBounds)

        canvas.getOrCreateTile(0, 0)
        canvas.clearDirty()
        assertNull(canvas.dirtyBounds)

        // Mark a region intersecting tile (0, 0) and tile (1, 0)
        val dirtyRegion = IntBounds(200, 50, 300, 100)
        canvas.markDirty(dirtyRegion)

        assertNotNull(canvas.dirtyBounds)
        assertEquals(200, canvas.dirtyBounds?.left)
        assertEquals(50, canvas.dirtyBounds?.top)
        assertEquals(300, canvas.dirtyBounds?.right)
        assertEquals(100, canvas.dirtyBounds?.bottom)

        val affected = canvas.getAffectedTileIndices(dirtyRegion)
        assertEquals(listOf(0, 1), affected)
    }

    @Test
    fun loadFromAndCompositeToBuffer() {
        val width = 300
        val height = 400
        val source = PixelBuffer(width, height)

        // Draw some non-zero pixels in tile (0, 0) and tile (1, 1)
        source.pixels[10 * width + 10] = -0x10000 // Red
        source.pixels[270 * width + 260] = -0xff0100 // Green

        val canvas = VirtualTileCanvas(width, height, tileSize = 256)
        canvas.loadFromBuffer(source)

        // Only tiles with non-zero pixels are allocated
        assertEquals(2, canvas.allocatedTileCount)

        val destination = PixelBuffer(width, height)
        canvas.compositeToBuffer(destination)

        assertEquals(-0x10000, destination.pixels[10 * width + 10])
        assertEquals(-0xff0100, destination.pixels[270 * width + 260])
        assertEquals(0, destination.pixels[0])
    }

    @Test
    fun renderBackendFactoryFallback() {
        val vulkanBackend =
            RenderBackendFactory.createBackend(
                preferredType = RenderBackendType.VULKAN,
                isEmulator = false,
                supportsVulkan = true,
            )
        assertEquals(RenderBackendType.VULKAN, vulkanBackend.backendType)

        val emulatorBackend =
            RenderBackendFactory.createBackend(
                preferredType = RenderBackendType.VULKAN,
                isEmulator = true,
                supportsVulkan = false,
            )
        assertEquals(RenderBackendType.GLES3, emulatorBackend.backendType)

        emulatorBackend.initialize(100, 100)
        assertTrue(emulatorBackend.isContextInitialized)
        emulatorBackend.release()
        assertFalse(emulatorBackend.isContextInitialized)
    }
}
