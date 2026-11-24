package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * A sparse tile cache filled in tile by tile as areas are asked for, and reused while its key
 * stays the same. The live preview keeps the layers below the one being painted here, so each
 * frame of a stroke composites only that layer and those above it instead of the whole stack.
 *
 * Only tiles that have actually been requested are allocated, and least-recently-used tiles are
 * evicted once [maxBytes] is exceeded so large canvases never pin a full-canvas buffer in RAM.
 */
class TileCache(
    private val tileSize: Int = DEFAULT_TILE,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private var key: Any? = null
    private var canvasWidth = 0
    private var canvasHeight = 0

    /** Access-ordered LRU map from packed `(row, column)` to tile pixels sized to that tile's bounds. */
    private val tiles = LinkedHashMap<Long, IntArray>(INITIAL_MAP_CAPACITY, 0.75f, true)

    /** Total pixel-array bytes currently retained by cached tiles. */
    var allocatedBytes: Long = 0L
        private set

    /** Number of tiles currently retained in the cache. */
    val cachedTileCount: Int
        @Synchronized get() = tiles.size

    /** Tiles rendered since the cache was created; lets tests see what was reused. */
    var renderedTiles = 0
        private set

    @Synchronized
    fun reset() {
        key = null
        tiles.clear()
        allocatedBytes = 0L
    }

    /** Drops all cached tiles immediately, freeing their memory. */
    @Synchronized
    fun release() {
        reset()
        canvasWidth = 0
        canvasHeight = 0
    }

    /**
     * The cached pixels of [area] (in canvas coordinates) for a [width] × [height] canvas. Tiles
     * of [area] not yet filled for [key] are rendered first: [render] gets a tile-aligned area and
     * returns its pixels at that area's size.
     */
    @Synchronized
    fun read(
        key: Any,
        width: Int,
        height: Int,
        area: IntBounds,
        render: (IntBounds) -> PixelBuffer,
    ): PixelBuffer {
        prepare(key, width, height)
        val clipped = IntBounds(max(0, area.left), max(0, area.top), min(width - 1, area.right), min(height - 1, area.bottom))
        require(!clipped.isEmpty) { "The area is outside the canvas" }
        val firstColumn = clipped.left / tileSize
        val lastColumn = clipped.right / tileSize
        val firstRow = clipped.top / tileSize
        val lastRow = clipped.bottom / tileSize
        fillMissing(firstColumn, lastColumn, firstRow, lastRow, render)
        val result = assemble(clipped, firstColumn, lastColumn, firstRow, lastRow)
        evictIfNeeded(firstColumn, lastColumn, firstRow, lastRow)
        return result
    }

    private fun prepare(
        key: Any,
        width: Int,
        height: Int,
    ) {
        if (canvasWidth != width || canvasHeight != height || this.key != key) {
            tiles.clear()
            allocatedBytes = 0L
            canvasWidth = width
            canvasHeight = height
            this.key = key
        }
    }

    /** Renders the smallest tile-aligned rectangle holding every unfilled tile of the requested range, once. */
    private fun fillMissing(
        firstColumn: Int,
        lastColumn: Int,
        firstRow: Int,
        lastRow: Int,
        render: (IntBounds) -> PixelBuffer,
    ) {
        var missing: IntBounds? = null
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                if (tiles.containsKey(tileKey(column, row))) continue
                val tile = tileBounds(column, row)
                missing =
                    missing?.let {
                        IntBounds(
                            min(it.left, tile.left),
                            min(it.top, tile.top),
                            max(it.right, tile.right),
                            max(it.bottom, tile.bottom),
                        )
                    }
                        ?: tile
            }
        }
        val todo = missing ?: return
        val pixels = render(todo)
        require(pixels.width == todo.width && pixels.height == todo.height) { "Rendered tiles must match the requested area" }
        val srcPixels = pixels.pixels
        for (row in todo.top / tileSize..todo.bottom / tileSize) {
            for (column in todo.left / tileSize..todo.right / tileSize) {
                val bounds = tileBounds(column, row)
                val tileW = bounds.width
                val tileH = bounds.height
                val tileKey = tileKey(column, row)
                val tileData = IntArray(tileW * tileH)
                val srcOffsetX = bounds.left - todo.left
                val srcOffsetY = bounds.top - todo.top
                for (y in 0 until tileH) {
                    System.arraycopy(srcPixels, (srcOffsetY + y) * todo.width + srcOffsetX, tileData, y * tileW, tileW)
                }
                val previous = tiles.put(tileKey, tileData)
                if (previous != null) allocatedBytes -= previous.size.toLong() * Int.SIZE_BYTES
                allocatedBytes += tileData.size.toLong() * Int.SIZE_BYTES
                renderedTiles++
            }
        }
    }

    private fun assemble(
        clipped: IntBounds,
        firstColumn: Int,
        lastColumn: Int,
        firstRow: Int,
        lastRow: Int,
    ): PixelBuffer {
        val result = PixelBuffer(clipped.width, clipped.height)
        val dstPixels = result.pixels
        val dstWidth = result.width
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                val bounds = tileBounds(column, row)
                val tileData = checkNotNull(tiles[tileKey(column, row)]) { "Missing cached tile ($column, $row)" }
                val overlapLeft = max(clipped.left, bounds.left)
                val overlapTop = max(clipped.top, bounds.top)
                val overlapRight = min(clipped.right, bounds.right)
                val overlapBottom = min(clipped.bottom, bounds.bottom)
                val copyWidth = overlapRight - overlapLeft + 1
                val tileWidth = bounds.width
                val srcX = overlapLeft - bounds.left
                val dstX = overlapLeft - clipped.left
                for (y in overlapTop..overlapBottom) {
                    val srcOffset = (y - bounds.top) * tileWidth + srcX
                    val dstOffset = (y - clipped.top) * dstWidth + dstX
                    System.arraycopy(tileData, srcOffset, dstPixels, dstOffset, copyWidth)
                }
            }
        }
        return result
    }

    private fun evictIfNeeded(
        keepFirstColumn: Int,
        keepLastColumn: Int,
        keepFirstRow: Int,
        keepLastRow: Int,
    ) {
        if (allocatedBytes <= maxBytes) return
        val iterator = tiles.entries.iterator()
        while (allocatedBytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            val packed = entry.key
            val row = (packed ushr 32).toInt()
            val col = packed.toInt()
            if (row in keepFirstRow..keepLastRow && col in keepFirstColumn..keepLastColumn) continue
            allocatedBytes -= entry.value.size.toLong() * Int.SIZE_BYTES
            iterator.remove()
        }
    }

    private fun tileBounds(
        column: Int,
        row: Int,
    ): IntBounds =
        IntBounds(
            column * tileSize,
            row * tileSize,
            min(canvasWidth - 1, (column + 1) * tileSize - 1),
            min(canvasHeight - 1, (row + 1) * tileSize - 1),
        )

    private fun tileKey(
        column: Int,
        row: Int,
    ): Long = (row.toLong() shl 32) or (column.toLong() and 0xFFFFFFFFL)

    companion object {
        const val DEFAULT_TILE = 128

        /** 16 MiB default cap — plenty for active stroke tiles without pinning full 4K–8K canvases. */
        const val DEFAULT_MAX_BYTES = 16L * 1024L * 1024L

        private const val INITIAL_MAP_CAPACITY = 64
    }
}
