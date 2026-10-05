package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * A canvas-sized image filled in tile by tile as areas are asked for, and reused while its key
 * stays the same. The live preview keeps the layers below the one being painted here, so each
 * frame of a stroke composites only that layer and those above it instead of the whole stack.
 */
class TileCache(
    private val tileSize: Int = DEFAULT_TILE,
) {
    private var key: Any? = null
    private var image: PixelBuffer? = null
    private var filled = BooleanArray(0)
    private var columns = 0

    /** Tiles rendered since the cache was created; lets tests see what was reused. */
    var renderedTiles = 0
        private set

    @Synchronized
    fun reset() {
        key = null
        filled.fill(false)
    }

    /** Drops the cached image itself, freeing its memory. */
    @Synchronized
    fun release() {
        reset()
        image = null
        filled = BooleanArray(0)
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
        val canvas = prepare(key, width, height)
        val clipped = IntBounds(max(0, area.left), max(0, area.top), min(width - 1, area.right), min(height - 1, area.bottom))
        require(!clipped.isEmpty) { "The area is outside the canvas" }
        fillMissing(canvas, clipped, render)
        return canvas.crop(clipped)
    }

    private fun prepare(
        key: Any,
        width: Int,
        height: Int,
    ): PixelBuffer {
        val current = image
        if (current == null || current.width != width || current.height != height) {
            image = PixelBuffer(width, height)
            columns = (width + tileSize - 1) / tileSize
            filled = BooleanArray(columns * ((height + tileSize - 1) / tileSize))
            this.key = null
        }
        if (this.key != key) {
            filled.fill(false)
            this.key = key
        }
        return requireNotNull(image)
    }

    /** Renders the smallest tile-aligned rectangle holding every unfilled tile of [area], once. */
    private fun fillMissing(
        canvas: PixelBuffer,
        area: IntBounds,
        render: (IntBounds) -> PixelBuffer,
    ) {
        val firstColumn = area.left / tileSize
        val lastColumn = area.right / tileSize
        val firstRow = area.top / tileSize
        val lastRow = area.bottom / tileSize
        var missing: IntBounds? = null
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                if (filled[row * columns + column]) continue
                val tile = tileBounds(column, row, canvas)
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
        for (y in 0 until todo.height) {
            System.arraycopy(pixels.pixels, y * todo.width, canvas.pixels, (todo.top + y) * canvas.width + todo.left, todo.width)
        }
        for (row in todo.top / tileSize..todo.bottom / tileSize) {
            for (column in todo.left / tileSize..todo.right / tileSize) {
                filled[row * columns + column] = true
                renderedTiles++
            }
        }
    }

    private fun tileBounds(
        column: Int,
        row: Int,
        canvas: PixelBuffer,
    ): IntBounds =
        IntBounds(
            column * tileSize,
            row * tileSize,
            min(canvas.width - 1, (column + 1) * tileSize - 1),
            min(canvas.height - 1, (row + 1) * tileSize - 1),
        )

    private companion object {
        const val DEFAULT_TILE = 128
    }
}
