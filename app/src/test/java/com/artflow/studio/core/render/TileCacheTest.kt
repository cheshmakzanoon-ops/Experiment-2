package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TileCacheTest {
    // Every pixel encodes its own canvas position, so misplaced tiles show up.
    private fun render(area: IntBounds): PixelBuffer =
        PixelBuffer(area.width, area.height).also { buffer ->
            for (y in 0 until area.height) {
                for (x in 0 until area.width) buffer.pixels[y * area.width + x] = (area.top + y) * 1000 + area.left + x
            }
        }

    @Test
    fun areasAreRenderedOnceAndReadBackInPlace() {
        val cache = TileCache(tileSize = 16)
        val requests = mutableListOf<IntBounds>()
        val area = IntBounds(5, 7, 40, 20)
        val first =
            cache.read("k", 100, 50, area) {
                requests += it
                render(it)
            }
        assertArrayEquals(render(area).pixels, first.pixels)
        assertEquals(listOf(IntBounds(0, 0, 47, 31)), requests)
        // Inside tiles already filled: nothing is rendered again.
        cache.read("k", 100, 50, IntBounds(10, 10, 30, 30)) {
            requests += it
            render(it)
        }
        assertEquals(1, requests.size)
        // Moving right only renders the new column of tiles.
        val moved =
            cache.read("k", 100, 50, IntBounds(30, 10, 60, 25)) {
                requests += it
                render(it)
            }
        assertEquals(IntBounds(48, 0, 63, 31), requests.last())
        assertArrayEquals(render(IntBounds(30, 10, 60, 25)).pixels, moved.pixels)
    }

    @Test
    fun aNewKeyOrResetStartsOverAndEdgesAreClipped() {
        val cache = TileCache(tileSize = 16)
        var renders = 0
        cache.read("a", 40, 40, IntBounds(0, 0, 10, 10)) {
            renders++
            render(it)
        }
        cache.read("b", 40, 40, IntBounds(0, 0, 10, 10)) {
            renders++
            render(it)
        }
        cache.reset()
        val edge =
            cache.read("b", 40, 40, IntBounds(30, 30, 60, 60)) {
                renders++
                render(it)
            }
        assertEquals(3, renders)
        assertEquals(10, edge.width)
        assertArrayEquals(render(IntBounds(30, 30, 39, 39)).pixels, edge.pixels)
        cache.release()
        cache.read("b", 40, 40, IntBounds(0, 0, 3, 3)) {
            renders++
            render(it)
        }
        assertEquals(4, renders)
    }
}
