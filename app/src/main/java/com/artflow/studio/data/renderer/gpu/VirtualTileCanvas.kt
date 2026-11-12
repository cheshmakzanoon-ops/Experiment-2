package com.artflow.studio.data.renderer.gpu

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Sparse 2D virtual tile manager for high-performance GPU and memory-efficient canvas representation.
 *
 * Canvases up to 16k × 16k can be represented with zero memory overhead for unpainted regions.
 * Each tile is allocated on-demand upon first paint, and dirty regions are tracked per-tile for
 * low-latency GPU FBO / texture updates.
 */
class VirtualTileCanvas(
    val width: Int,
    val height: Int,
    val tileSize: Int = DEFAULT_TILE_SIZE,
) {
    init {
        require(width > 0 && height > 0) { "Canvas dimensions must be positive (got ${width}x$height)" }
        require(tileSize > 0 && (tileSize and (tileSize - 1)) == 0) {
            "Tile size must be a power of two (got $tileSize)"
        }
    }

    val columns: Int = (width + tileSize - 1) / tileSize
    val rows: Int = (height + tileSize - 1) / tileSize
    val totalTiles: Int = columns * rows

    data class Tile(
        val column: Int,
        val row: Int,
        val buffer: PixelBuffer,
        var isDirty: Boolean = false,
    )

    private val tiles = HashMap<Int, Tile>()
    private var dirtyBoundsInternal: IntBounds? = null

    /** Current aggregated dirty rectangle across all modified tiles, or null if clean. */
    val dirtyBounds: IntBounds?
        @Synchronized get() = dirtyBoundsInternal

    /** Number of currently allocated tiles. */
    val allocatedTileCount: Int
        @Synchronized get() = tiles.size

    /** Memory usage in bytes of all currently allocated tile pixel buffers. */
    val memoryUsageBytes: Long
        @Synchronized get() =
            tiles.values.sumOf {
                it.buffer.pixels.size
                    .toLong() * 4L
            }

    @Synchronized
    fun getTileIndex(
        column: Int,
        row: Int,
    ): Int {
        require(column in 0 until columns && row in 0 until rows) {
            "Tile coordinates ($column, $row) out of bounds ($columns x $rows)"
        }
        return row * columns + column
    }

    @Synchronized
    fun tileBounds(
        column: Int,
        row: Int,
    ): IntBounds {
        require(column in 0 until columns && row in 0 until rows) {
            "Tile coordinates ($column, $row) out of bounds ($columns x $rows)"
        }
        val left = column * tileSize
        val top = row * tileSize
        val right = min(width - 1, left + tileSize - 1)
        val bottom = min(height - 1, top + tileSize - 1)
        return IntBounds(left, top, right, bottom)
    }

    @Synchronized
    fun getTile(
        column: Int,
        row: Int,
    ): PixelBuffer? {
        val index = getTileIndex(column, row)
        return tiles[index]?.buffer
    }

    @Synchronized
    fun getOrCreateTile(
        column: Int,
        row: Int,
    ): PixelBuffer {
        val index = getTileIndex(column, row)
        val existing = tiles[index]
        if (existing != null) return existing.buffer

        val bounds = tileBounds(column, row)
        val buffer = PixelBuffer(bounds.width, bounds.height)
        val newTile = Tile(column, row, buffer, isDirty = true)
        tiles[index] = newTile
        expandDirtyBounds(bounds)
        return buffer
    }

    @Synchronized
    fun getAffectedTileIndices(bounds: IntBounds): List<Int> {
        val clipped = clipBounds(bounds) ?: return emptyList()
        val firstCol = clipped.left / tileSize
        val lastCol = clipped.right / tileSize
        val firstRow = clipped.top / tileSize
        val lastRow = clipped.bottom / tileSize

        val result = ArrayList<Int>((lastCol - firstCol + 1) * (lastRow - firstRow + 1))
        for (r in firstRow..lastRow) {
            for (c in firstCol..lastCol) {
                result.add(r * columns + c)
            }
        }
        return result
    }

    @Synchronized
    fun markDirty(bounds: IntBounds) {
        val clipped = clipBounds(bounds) ?: return
        expandDirtyBounds(clipped)
        val affected = getAffectedTileIndices(clipped)
        for (index in affected) {
            tiles[index]?.isDirty = true
        }
    }

    @Synchronized
    fun getDirtyTileIndices(): List<Int> = tiles.entries.filter { it.value.isDirty }.map { it.key }

    @Synchronized
    fun clearDirty() {
        for (tile in tiles.values) {
            tile.isDirty = false
        }
        dirtyBoundsInternal = null
    }

    @Synchronized
    fun loadFromBuffer(source: PixelBuffer) {
        require(source.width == width && source.height == height) {
            "Source buffer dimensions (${source.width}x${source.height}) do not match canvas (${width}x$height)"
        }
        tiles.clear()
        dirtyBoundsInternal = null

        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val tb = tileBounds(c, r)
                val tileBuf = extractTile(source, tb)
                if (tileBuf != null) {
                    val index = r * columns + c
                    tiles[index] = Tile(c, r, tileBuf, isDirty = true)
                }
            }
        }
        if (tiles.isNotEmpty()) {
            expandDirtyBounds(IntBounds(0, 0, width - 1, height - 1))
        }
    }

    private fun extractTile(
        source: PixelBuffer,
        tb: IntBounds,
    ): PixelBuffer? {
        var hasNonZero = false
        val tileBuf = PixelBuffer(tb.width, tb.height)
        for (y in 0 until tb.height) {
            val srcOffset = (tb.top + y) * width + tb.left
            val dstOffset = y * tb.width
            for (x in 0 until tb.width) {
                val px = source.pixels[srcOffset + x]
                tileBuf.pixels[dstOffset + x] = px
                if ((px ushr 24) != 0) {
                    hasNonZero = true
                }
            }
        }
        return if (hasNonZero) tileBuf else null
    }

    @Synchronized
    fun compositeToBuffer(
        target: PixelBuffer,
        bounds: IntBounds? = null,
    ) {
        require(target.width == width && target.height == height) {
            "Target buffer dimensions (${target.width}x${target.height}) do not match canvas (${width}x$height)"
        }
        val region = if (bounds != null) clipBounds(bounds) ?: return else IntBounds(0, 0, width - 1, height - 1)

        val firstCol = region.left / tileSize
        val lastCol = region.right / tileSize
        val firstRow = region.top / tileSize
        val lastRow = region.bottom / tileSize

        for (r in firstRow..lastRow) {
            for (c in firstCol..lastCol) {
                val index = r * columns + c
                val tile = tiles[index] ?: continue
                val tb = tileBounds(c, r)

                val overlapLeft = max(region.left, tb.left)
                val overlapTop = max(region.top, tb.top)
                val overlapRight = min(region.right, tb.right)
                val overlapBottom = min(region.bottom, tb.bottom)

                for (y in overlapTop..overlapBottom) {
                    val tileY = y - tb.top
                    val tileOffset = tileY * tb.width + (overlapLeft - tb.left)
                    val targetOffset = y * width + overlapLeft
                    val copyWidth = overlapRight - overlapLeft + 1
                    System.arraycopy(tile.buffer.pixels, tileOffset, target.pixels, targetOffset, copyWidth)
                }
            }
        }
    }

    @Synchronized
    fun clear() {
        tiles.clear()
        dirtyBoundsInternal = null
    }

    private fun clipBounds(bounds: IntBounds): IntBounds? {
        if (bounds.isEmpty) return null
        val left = max(0, bounds.left)
        val top = max(0, bounds.top)
        val right = min(width - 1, bounds.right)
        val bottom = min(height - 1, bounds.bottom)
        if (right < left || bottom < top) return null
        return IntBounds(left, top, right, bottom)
    }

    private fun expandDirtyBounds(bounds: IntBounds) {
        dirtyBoundsInternal =
            dirtyBoundsInternal?.let {
                IntBounds(
                    min(it.left, bounds.left),
                    min(it.top, bounds.top),
                    max(it.right, bounds.right),
                    max(it.bottom, bounds.bottom),
                )
            } ?: bounds
    }

    companion object {
        const val DEFAULT_TILE_SIZE: Int = 256
        const val COMPACT_TILE_SIZE: Int = 128
    }
}
