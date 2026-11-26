package com.artflow.studio.core.render

import com.artflow.studio.core.color.SpectralMixer
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sparse per-layer physical paint simulation buffer storing wetness, mobile pigment load,
 * and 3D impasto ridge height in 64x64 tiles allocated lazily only where physical brushes paint.
 */
class PhysicalPaintPlane(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "PhysicalPaintPlane dimensions must be positive" }
    }

    internal class Tile {
        val wetness = FloatArray(TILE_PIXELS)
        val impastoHeight = FloatArray(TILE_PIXELS)
        val pigmentLoad = FloatArray(TILE_PIXELS)

        fun hasState(): Boolean = wetness.any { it != 0f } || impastoHeight.any { it != 0f } || pigmentLoad.any { it != 0f }

        fun hasStateAt(index: Int): Boolean = wetness[index] != 0f || impastoHeight[index] != 0f || pigmentLoad[index] != 0f

        fun clearAt(index: Int) {
            wetness[index] = 0f
            impastoHeight[index] = 0f
            pigmentLoad[index] = 0f
        }
    }

    /** Visits each in-bounds cell of the tile at [key]: its index in the tile's arrays and its plane coordinates. */
    private inline fun forEachCell(
        key: Long,
        visit: (index: Int, x: Int, y: Int) -> Unit,
    ) {
        val originX = (key ushr 32).toInt() * TILE_SIZE
        val originY = (key and 0xFFFFFFFFL).toInt() * TILE_SIZE
        val columns = min(TILE_SIZE, width - originX)
        val rows = min(TILE_SIZE, height - originY)
        for (ly in 0 until rows) {
            for (lx in 0 until columns) visit(ly * TILE_SIZE + lx, originX + lx, originY + ly)
        }
    }

    /** Copies the tile at ([tx], [ty]) out of [packed], allocating it only when a cell in it carries state. */
    private fun absorbPackedTile(
        packed: PixelBuffer,
        tx: Int,
        ty: Int,
    ) {
        var tile: Tile? = null
        forEachCell(tileKey(tx, ty)) { i, x, y ->
            val argb = packed.pixels[y * packed.width + x]
            val alpha = (argb ushr 24) / 255f
            val wet = ((argb shr 16) and 0xFF) / 255f * alpha
            val relief = reliefFromByte((argb shr 8) and 0xFF) * alpha
            val pigment = (argb and 0xFF) / 255f * alpha
            // Resampling noise around flat relief (one level either side) is flat, not a ridge.
            val height = if (kotlin.math.abs(relief) <= ZERO_RELIEF_BAND) 0f else relief
            if (wet >= HALF_LEVEL || height != 0f || pigment >= HALF_LEVEL) {
                val target = tile ?: obtainTile(tx, ty).also { tile = it }
                target.wetness[i] = wet.coerceIn(0f, 1f)
                target.impastoHeight[i] = height.coerceIn(-1f, 1f)
                target.pigmentLoad[i] = pigment.coerceIn(0f, 1f)
            }
        }
    }

    private fun PixelBuffer.paintedAt(
        x: Int,
        y: Int,
    ): Boolean = x < this.width && y < this.height && (pixels[y * this.width + x] ushr 24) != 0

    private val tiles = LinkedHashMap<Long, Tile>()

    fun isEmpty(): Boolean = tiles.isEmpty()

    fun allocatedTileCount(): Int = tiles.size

    fun estimatedBytes(): Long = tiles.size.toLong() * TILE_PIXELS * 3L * 4L

    fun wetnessAt(
        x: Int,
        y: Int,
    ): Float {
        if (x !in 0 until width || y !in 0 until height) return 0f
        val tile = tiles[tileKey(x / TILE_SIZE, y / TILE_SIZE)] ?: return 0f
        return tile.wetness[localIndex(x, y)]
    }

    fun impastoAt(
        x: Int,
        y: Int,
    ): Float {
        if (x !in 0 until width || y !in 0 until height) return 0f
        val tile = tiles[tileKey(x / TILE_SIZE, y / TILE_SIZE)] ?: return 0f
        return tile.impastoHeight[localIndex(x, y)]
    }

    fun pigmentAt(
        x: Int,
        y: Int,
    ): Float {
        if (x !in 0 until width || y !in 0 until height) return 0f
        val tile = tiles[tileKey(x / TILE_SIZE, y / TILE_SIZE)] ?: return 0f
        return tile.pigmentLoad[localIndex(x, y)]
    }

    fun setCell(
        x: Int,
        y: Int,
        wetness: Float,
        impasto: Float,
        pigment: Float,
    ) {
        if (x !in 0 until width || y !in 0 until height) return
        val w = wetness.coerceIn(0f, 1f)
        val h = impasto.coerceIn(-1f, 1f)
        val p = pigment.coerceIn(0f, 1f)
        val isZero = w == 0f && h == 0f && p == 0f
        if (isZero && tiles[tileKey(x / TILE_SIZE, y / TILE_SIZE)] == null) return
        val tile = obtainTile(x / TILE_SIZE, y / TILE_SIZE)
        val idx = localIndex(x, y)
        tile.wetness[idx] = w
        tile.impastoHeight[idx] = h
        tile.pigmentLoad[idx] = p
    }

    /** Adds scalar state retained by a selection after its selected contribution has moved. */
    internal fun addUnselectedTo(
        target: PhysicalPaintPlane,
        selection: com.artflow.studio.core.pixels.SelectionMask,
    ) {
        for ((key, tile) in tiles) {
            forEachCell(key) { i, x, y ->
                val retained = 1f - selection.alphaAt(y * width + x)
                if (retained > 0f && tile.hasStateAt(i)) {
                    target.setCell(
                        x,
                        y,
                        target.wetnessAt(x, y) + tile.wetness[i] * retained,
                        target.impastoAt(x, y) + tile.impastoHeight[i] * retained,
                        target.pigmentAt(x, y) + tile.pigmentLoad[i] * retained,
                    )
                }
            }
        }
    }

    /** Locks all wet watercolor in place while preserving 3D impasto ridges. */
    fun dryAll() {
        val iterator = tiles.entries.iterator()
        while (iterator.hasNext()) {
            val tile = iterator.next().value
            tile.wetness.fill(0f)
            tile.pigmentLoad.fill(0f)
            val hasImpasto = tile.impastoHeight.any { it != 0f }
            if (!hasImpasto) iterator.remove()
        }
    }

    /** Re-activates dried paint in a circular region for watercolor lifting or wet-on-wet blending. */
    fun rewetRegion(
        centerX: Float,
        centerY: Float,
        radius: Float,
        amount: Float,
    ) {
        val r = radius.coerceAtLeast(1f)
        val strength = amount.coerceIn(0f, 1f)
        if (strength <= 0f) return
        val minX = max(0, floor(centerX - r).toInt())
        val maxX = min(width - 1, ceil(centerX + r).toInt())
        val minY = max(0, floor(centerY - r).toInt())
        val maxY = min(height - 1, ceil(centerY + r).toInt())
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dist = hypot(x + 0.5f - centerX, y + 0.5f - centerY)
                if (dist > r) continue
                val falloff = 1f - dist / r
                val tile = obtainTile(x / TILE_SIZE, y / TILE_SIZE)
                val idx = localIndex(x, y)
                tile.wetness[idx] = max(tile.wetness[idx], strength * falloff).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * True when any allocated tile still contains moisture the simulation will advance. The default is the
     * simulation's own processing floor ([ACTIVE_WETNESS]); a lower threshold would report cells as active
     * that the step skips, so a settling loop could never reach quiescence.
     */
    fun hasActiveWetness(threshold: Float = ACTIVE_WETNESS): Boolean = tiles.values.any { tile -> tile.wetness.any { it >= threshold } }

    /** Re-activates dried paint across all non-transparent pixels in [buffer]. */
    fun rewetPaintedPixels(
        buffer: PixelBuffer,
        amount: Float = 0.7f,
    ) {
        val strength = amount.coerceIn(0f, 1f)
        if (strength <= 0f) return
        val maxW = min(width, buffer.width)
        val maxH = min(height, buffer.height)
        for (y in 0 until maxH) {
            val row = y * buffer.width
            for (x in 0 until maxW) {
                val alpha = (buffer.pixels[row + x] ushr 24) and 0xFF
                if (alpha > 4) {
                    val tile = obtainTile(x / TILE_SIZE, y / TILE_SIZE)
                    val idx = localIndex(x, y)
                    val target = strength * (alpha / 255f)
                    tile.wetness[idx] = max(tile.wetness[idx], target).coerceIn(0f, 1f)
                    tile.pigmentLoad[idx] = max(tile.pigmentLoad[idx], target * 0.8f).coerceIn(0f, 1f)
                }
            }
        }
    }

    /**
     * Packs wetness, impasto and pigment into the red, green and blue channels of an opaque image of
     * the plane's size (impasto is mapped from -1..1 to 0..1) so any geometric pixel operation (move,
     * scale, rotate, crop, flip, resample) can transform the physical state with exactly the mapping it
     * applies to the colour pixels. Resampled gaps keep alpha 0 and decode back to an empty cell.
     */
    fun toPackedPixels(): PixelBuffer {
        val packed = PixelBuffer(width, height)
        packed.pixels.fill(PACKED_BLANK)
        for ((key, tile) in tiles) {
            forEachCell(key) { i, x, y ->
                packed.pixels[y * width + x] =
                    (0xFF shl 24) or
                    (packByte(tile.wetness[i]) shl 16) or
                    (packByte(tile.impastoHeight[i] * 0.5f + 0.5f) shl 8) or
                    packByte(tile.pigmentLoad[i])
            }
        }
        return packed
    }

    /** True when some cell still holds physical state although [raster] is fully transparent there. */
    fun hasStateBeneathTransparent(raster: PixelBuffer): Boolean {
        for ((key, tile) in tiles) {
            forEachCell(key) { i, x, y ->
                if (tile.hasStateAt(i) && !raster.paintedAt(x, y)) return true
            }
        }
        return false
    }

    /**
     * Drops physical state beneath fully transparent colour, so an erased layer cannot later reveal
     * stale relief or wetness. Soft erasing keeps the state of cells that still hold some paint.
     */
    fun pruneTransparent(raster: PixelBuffer) {
        val iterator = tiles.entries.iterator()
        while (iterator.hasNext()) {
            val (key, tile) = iterator.next()
            var any = false
            forEachCell(key) { i, x, y ->
                if (!raster.paintedAt(x, y)) {
                    tile.clearAt(i)
                } else if (tile.hasStateAt(i)) {
                    any = true
                }
            }
            if (!any) iterator.remove()
        }
    }

    /**
     * The part of this plane inside the rectangle at ([left], [top]) of size [newWidth] x [newHeight], re-based so
     * the rectangle's corner is the new origin. A preview of one region of the canvas needs the physical state of
     * that same region; handing it the whole plane puts relief in the wrong place and lets preview strokes write
     * into the live document.
     */
    fun cropped(
        left: Int,
        top: Int,
        newWidth: Int,
        newHeight: Int,
    ): PhysicalPaintPlane {
        val out = PhysicalPaintPlane(newWidth, newHeight)
        for ((key, tile) in tiles) {
            forEachCell(key) { i, x, y ->
                if (x in left until left + newWidth && y in top until top + newHeight && tile.hasStateAt(i)) {
                    out.setCell(x - left, y - top, tile.wetness[i], tile.impastoHeight[i], tile.pigmentLoad[i])
                }
            }
        }
        return out
    }

    /** Exact cell-for-cell equality, ignoring tiles that hold only zeros. */
    fun contentEquals(other: PhysicalPaintPlane?): Boolean {
        if (other == null) return tiles.values.none { it.hasState() }
        if (width != other.width || height != other.height) return false
        val keys = (tiles.keys + other.tiles.keys)
        return keys.all { key ->
            val a = tiles[key]
            val b = other.tiles[key]
            when {
                a == null -> !b!!.hasState()
                b == null -> !a.hasState()
                else ->
                    a.wetness.contentEquals(b.wetness) &&
                        a.impastoHeight.contentEquals(b.impastoHeight) &&
                        a.pigmentLoad.contentEquals(b.pigmentLoad)
            }
        }
    }

    fun copy(): PhysicalPaintPlane {
        val clone = PhysicalPaintPlane(width, height)
        for ((key, tile) in tiles) {
            val copyTile = Tile()
            tile.wetness.copyInto(copyTile.wetness)
            tile.impastoHeight.copyInto(copyTile.impastoHeight)
            tile.pigmentLoad.copyInto(copyTile.pigmentLoad)
            clone.tiles[key] = copyTile
        }
        return clone
    }

    internal fun allocatedTileCoords(): List<Pair<Int, Int>> =
        tiles.keys.map { key -> (key ushr 32).toInt() to (key and 0xFFFFFFFFL).toInt() }

    internal fun obtainTile(
        tx: Int,
        ty: Int,
    ): Tile = tiles.getOrPut(tileKey(tx, ty)) { Tile() }

    /** Compact GZIP-compressed sparse binary encoding for `.artflow` v8 persistence. */
    fun encodeBinary(): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            DataOutputStream(gzip).use { data ->
                data.writeInt(BINARY_MAGIC)
                data.writeInt(BINARY_VERSION)
                data.writeInt(width)
                data.writeInt(height)
                data.writeInt(tiles.size)
                for ((key, tile) in tiles) {
                    writeTile(data, key, tile)
                }
            }
        }
        return out.toByteArray()
    }

    private fun writeTile(
        data: DataOutputStream,
        key: Long,
        tile: Tile,
    ) {
        data.writeInt((key ushr 32).toInt())
        data.writeInt((key and 0xFFFFFFFFL).toInt())
        for (i in 0 until TILE_PIXELS) {
            data.writeByte((tile.wetness[i] * 255f + 0.5f).toInt().coerceIn(0, 255))
            data.writeByte(((tile.impastoHeight[i] * 0.5f + 0.5f) * 255f + 0.5f).toInt().coerceIn(0, 255))
            data.writeByte((tile.pigmentLoad[i] * 255f + 0.5f).toInt().coerceIn(0, 255))
        }
    }

    companion object {
        const val TILE_SIZE = 64
        const val TILE_PIXELS = TILE_SIZE * TILE_SIZE

        /** Wetness below this is settled: the diffusion step dries it out, and activity checks ignore it. */
        const val ACTIVE_WETNESS = 0.02f
        private const val BINARY_MAGIC = 0x41465050 // "AFPP"
        private const val BINARY_VERSION = 1
        private const val MAX_TILES = 16_384

        /**
         * A cell with no physical state: alpha 255 marks "this cell exists" (resampling gaps stay alpha 0) and
         * the relief channel holds its midpoint (128), which is flat. Zero there would decode as the deepest valley.
         */
        private const val PACKED_BLANK = (0xFF shl 24) or (128 shl 8)

        private fun packByte(unit: Float): Int = (unit * 255f + 0.5f).toInt().coerceIn(0, 255)

        /** Relief is stored in one byte, whose midpoint (128) stands for flat; anything this close is flat. */
        private const val ZERO_RELIEF_BAND = 1.5f / 255f

        /** Half of one 8-bit level: below this a packed channel is rounding noise, not state. */
        private const val HALF_LEVEL = 0.5f / 255f

        /** The byte 128 is exactly flat, so a zero-height cell does not come back as a faint ridge. */
        private fun reliefFromByte(value: Int): Float = if (value == 128) 0f else (value / 255f) * 2f - 1f

        /**
         * Inverse of [toPackedPixels]. Cells are attenuated by their resampled alpha, so the edge of
         * a moved or scaled layer fades rather than gaining paint, and only tiles that still carry
         * state are allocated.
         */
        fun fromPackedPixels(packed: PixelBuffer): PhysicalPaintPlane {
            val plane = PhysicalPaintPlane(packed.width, packed.height)
            val tilesX = (packed.width + TILE_SIZE - 1) / TILE_SIZE
            val tilesY = (packed.height + TILE_SIZE - 1) / TILE_SIZE
            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) plane.absorbPackedTile(packed, tx, ty)
            }
            return plane
        }

        private fun tileKey(
            tx: Int,
            ty: Int,
        ): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

        private fun localIndex(
            x: Int,
            y: Int,
        ): Int = (y % TILE_SIZE) * TILE_SIZE + (x % TILE_SIZE)

        fun decodeBinary(bytes: ByteArray): PhysicalPaintPlane {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                DataInputStream(gzip).use { data ->
                    require(data.readInt() == BINARY_MAGIC) { "Invalid physical paint plane header" }
                    require(data.readInt() == BINARY_VERSION) { "Unsupported physical paint plane version" }
                    val width = data.readInt()
                    val height = data.readInt()
                    val tileCount = data.readInt()
                    require(width in 1..8192 && height in 1..8192) { "Invalid physical paint dimensions" }
                    require(tileCount in 0..MAX_TILES) { "Physical paint tile count exceeds budget" }
                    val plane = PhysicalPaintPlane(width, height)
                    repeat(tileCount) {
                        val tx = data.readInt()
                        val ty = data.readInt()
                        require(tx in 0 until (width + TILE_SIZE - 1) / TILE_SIZE && ty in 0 until (height + TILE_SIZE - 1) / TILE_SIZE) {
                            "Physical paint tile lies outside the plane"
                        }
                        val tile = plane.obtainTile(tx, ty)
                        for (i in 0 until TILE_PIXELS) {
                            tile.wetness[i] = data.readUnsignedByte() / 255f
                            tile.impastoHeight[i] = reliefFromByte(data.readUnsignedByte())
                            tile.pigmentLoad[i] = data.readUnsignedByte() / 255f
                        }
                    }
                    return plane
                }
            }
        }
    }
}

/**
 * Physical watercolor diffusion, paper tooth granulation, tilt-to-flow gravity,
 * 3D impasto oil bristle ridge deposition, and real-time raking-light normal shading.
 */
object PhysicalPaintEngine {
    /**
     * Deposits wetness and mobile pigment into [plane] for a watercolor/gouache dab.
     */
    fun depositWetness(
        plane: PhysicalPaintPlane,
        centerX: Float,
        centerY: Float,
        radius: Float,
        waterAmount: Float,
        pigmentLoad: Float,
        coverage: ((Int, Int) -> Float)? = null,
    ) {
        val r = radius.coerceAtLeast(1f)
        val water = waterAmount.coerceIn(0f, 1f)
        val pigment = pigmentLoad.coerceIn(0f, 1f)
        if (water <= 0f && pigment <= 0f) return
        val minX = max(0, floor(centerX - r).toInt())
        val maxX = min(plane.width - 1, ceil(centerX + r).toInt())
        val minY = max(0, floor(centerY - r).toInt())
        val maxY = min(plane.height - 1, ceil(centerY + r).toInt())
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dist = hypot(x + 0.5f - centerX, y + 0.5f - centerY)
                val amount = (coverage?.invoke(x, y) ?: 1f).coerceIn(0f, 1f)
                if (dist > r || amount <= 0f) continue
                val falloff = (1f - (dist / r).let { it * it }) * amount
                val nextW = max(plane.wetnessAt(x, y), water * falloff)
                val nextP = max(plane.pigmentAt(x, y), pigment * falloff)
                plane.setCell(x, y, nextW, plane.impastoAt(x, y), nextP)
            }
        }
    }

    /**
     * Deposits directional 3D impasto bristle ridges into [plane] along stroke direction ([dirX], [dirY]).
     */
    fun depositImpasto(
        plane: PhysicalPaintPlane,
        centerX: Float,
        centerY: Float,
        radius: Float,
        thickness: Float,
        dirX: Float,
        dirY: Float,
        coverage: ((Int, Int) -> Float)? = null,
    ) {
        val r = radius.coerceAtLeast(1f)
        val thick = thickness.coerceIn(0f, 1f)
        if (thick <= 0f) return
        val len = hypot(dirX, dirY)
        val nx = if (len > 1e-4f) dirX / len else 1f
        val ny = if (len > 1e-4f) dirY / len else 0f
        val perpX = -ny
        val perpY = nx
        val minX = max(0, floor(centerX - r).toInt())
        val maxX = min(plane.width - 1, ceil(centerX + r).toInt())
        val minY = max(0, floor(centerY - r).toInt())
        val maxY = min(plane.height - 1, ceil(centerY + r).toInt())
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x + 0.5f - centerX
                val dy = y + 0.5f - centerY
                val dist = hypot(dx, dy)
                val amount = (coverage?.invoke(x, y) ?: 1f).coerceIn(0f, 1f)
                if (dist > r || amount <= 0f) continue
                val normR = dist / r
                val across = (dx * perpX + dy * perpY) / r
                // Bristle grooves across the brush width + subtle paint ridge heaped along the stroke rim.
                val bristles = 0.55f + 0.45f * sin(across * 18f)
                val rimPile = if (normR > 0.65f) 1.25f * (1f - (normR - 0.65f) / 0.35f) else 0.85f
                val deposit = thick * bristles * rimPile * (1f - normR * normR)
                val current = plane.impastoAt(x, y)
                val nextH = (current + (deposit - current * 0.35f) * amount).coerceIn(-1f, 1f)
                plane.setCell(x, y, plane.wetnessAt(x, y), nextH, plane.pigmentAt(x, y))
            }
        }
    }

    /**
     * Advances wet-on-wet watercolor pigment diffusion, paper-tooth granulation, backrun edge
     * darkening, and tilt-to-flow gravity across all currently wet tiles.
     */
    fun stepWatercolorDiffusion(
        colorBuffer: PixelBuffer,
        plane: PhysicalPaintPlane,
        paperGrain: BrushTexture? = null,
        granulation: Float = 0.35f,
        gravityX: Float = 0f,
        gravityY: Float = 0f,
        iterations: Int = 1,
    ) {
        if (plane.isEmpty() || iterations <= 0) return
        val w = min(colorBuffer.width, plane.width)
        val h = min(colorBuffer.height, plane.height)
        val gran = granulation.coerceIn(0f, 1f)
        val gx = gravityX.coerceIn(-1f, 1f)
        val gy = gravityY.coerceIn(-1f, 1f)

        repeat(iterations) {
            val activeTiles = plane.allocatedTileCoords()
            val nextPixels = colorBuffer.pixels.copyOf()
            val nextPlane = plane.copy()

            for ((tx, ty) in activeTiles) {
                // A tile with nothing wet enough to move has no work: diffusion only updates wet cells.
                if (plane.obtainTile(tx, ty).wetness.none { it > 0f }) continue
                val x0 = max(1, tx * PhysicalPaintPlane.TILE_SIZE)
                val y0 = max(1, ty * PhysicalPaintPlane.TILE_SIZE)
                val x1 = min(w - 2, (tx + 1) * PhysicalPaintPlane.TILE_SIZE - 1)
                val y1 = min(h - 2, (ty + 1) * PhysicalPaintPlane.TILE_SIZE - 1)

                for (y in y0..y1) {
                    val row = y * colorBuffer.width
                    for (x in x0..x1) {
                        val wet = plane.wetnessAt(x, y)
                        if (wet < PhysicalPaintPlane.ACTIVE_WETNESS) {
                            // Residual moisture settles to dry, so the simulation always reaches quiescence
                            // instead of leaving cells that are active to the loop but never advanced.
                            if (wet > 0f) nextPlane.setCell(x, y, 0f, plane.impastoAt(x, y), 0f)
                            continue
                        }

                        val tooth = paperGrain?.coverage(x, y) ?: 0.5f
                        // Paper valleys (lower tooth) trap more granulating pigment.
                        val valleyHold = 1f - gran * 0.45f * (1f - tooth)
                        val flowRate = (wet * 0.24f * valleyHold).coerceIn(0f, 0.35f)

                        // Directional neighbor weights with tilt-to-flow gravity bias.
                        val wLeft = max(0.05f, 0.25f + gx * 0.18f) * plane.wetnessAt(x - 1, y)
                        val wRight = max(0.05f, 0.25f - gx * 0.18f) * plane.wetnessAt(x + 1, y)
                        val wUp = max(0.05f, 0.25f + gy * 0.18f) * plane.wetnessAt(x, y - 1)
                        val wDown = max(0.05f, 0.25f - gy * 0.18f) * plane.wetnessAt(x, y + 1)
                        val wSum = wLeft + wRight + wUp + wDown

                        val centerColor = colorBuffer.pixels[row + x]
                        var outColor = centerColor
                        if (wSum > 0.01f) {
                            val nColor =
                                weightedNeighborColor(
                                    colorBuffer.pixels[row + x - 1],
                                    wLeft,
                                    colorBuffer.pixels[row + x + 1],
                                    wRight,
                                    colorBuffer.pixels[row - colorBuffer.width + x],
                                    wUp,
                                    colorBuffer.pixels[row + colorBuffer.width + x],
                                    wDown,
                                )
                            if ((nColor ushr 24) > 0) {
                                outColor =
                                    if ((centerColor ushr 24) == 0) {
                                        Channels.scaleAlpha(nColor, flowRate * 1.4f)
                                    } else {
                                        SpectralMixer.mixArgb(centerColor, nColor, flowRate)
                                    }
                            }
                        }

                        // Backrun / wet-edge darkening where wet paint meets dry paper.
                        val dryNeighbors =
                            (if (plane.wetnessAt(x - 1, y) < 0.05f) 1 else 0) +
                                (if (plane.wetnessAt(x + 1, y) < 0.05f) 1 else 0) +
                                (if (plane.wetnessAt(x, y - 1) < 0.05f) 1 else 0) +
                                (if (plane.wetnessAt(x, y + 1) < 0.05f) 1 else 0)
                        if (dryNeighbors > 0 && (outColor ushr 24) > 0) {
                            val rimDarken = 1f - 0.045f * dryNeighbors * wet
                            outColor = scaleRgb(outColor, rimDarken)
                        }

                        nextPixels[row + x] = outColor
                        val nextWet = (wet * 0.92f - 0.01f).coerceAtLeast(0f)
                        nextPlane.setCell(x, y, nextWet, plane.impastoAt(x, y), plane.pigmentAt(x, y) * 0.95f)
                    }
                }
            }
            evaporateBorder(plane, nextPlane, w, h)
            nextPixels.copyInto(colorBuffer.pixels)
            for ((tx, ty) in nextPlane.allocatedTileCoords()) {
                val src = nextPlane.obtainTile(tx, ty)
                val dst = plane.obtainTile(tx, ty)
                src.wetness.copyInto(dst.wetness)
                src.pigmentLoad.copyInto(dst.pigmentLoad)
            }
        }
    }

    /**
     * The one-cell border has no full neighbourhood to diffuse into, but it must still evaporate: otherwise paint
     * touching the canvas edge stays wet forever and the diffusion loop never settles.
     */
    private fun evaporateBorder(
        plane: PhysicalPaintPlane,
        nextPlane: PhysicalPaintPlane,
        w: Int,
        h: Int,
    ) {
        fun evaporate(
            x: Int,
            y: Int,
        ) {
            val wet = plane.wetnessAt(x, y)
            if (wet <= 0f) return
            val next = if (wet < PhysicalPaintPlane.ACTIVE_WETNESS) 0f else (wet * 0.92f - 0.01f).coerceAtLeast(0f)
            nextPlane.setCell(x, y, next, plane.impastoAt(x, y), if (next == 0f) 0f else plane.pigmentAt(x, y) * 0.95f)
        }
        for (x in 0 until w) {
            evaporate(x, 0)
            evaporate(x, h - 1)
        }
        for (y in 1 until h - 1) {
            evaporate(0, y)
            evaporate(w - 1, y)
        }
    }

    /**
     * Applies dynamic raking-light normal-map shading from [plane]'s 3D impasto heightmap
     * onto [colorBuffer] (Blinn-Phong diffuse + specular highlights).
     */
    fun applyRakingLight(
        colorBuffer: PixelBuffer,
        plane: PhysicalPaintPlane,
        lightAngleDeg: Float = 45f,
        lightElevationDeg: Float = 35f,
        impastoDepth: Float = 0.5f,
        gloss: Float = 0.35f,
    ) {
        if (plane.isEmpty() || impastoDepth <= 0f) return
        val w = min(colorBuffer.width, plane.width)
        val h = min(colorBuffer.height, plane.height)
        val az = Math.toRadians(lightAngleDeg.toDouble())
        val el = Math.toRadians(lightElevationDeg.coerceIn(5f, 85f).toDouble())
        val lx = (cos(el) * cos(az)).toFloat()
        val ly = (cos(el) * sin(az)).toFloat()
        val lz = sin(el).toFloat()

        // Half-vector between light direction L and view direction V=(0,0,1).
        val hLen = sqrt(lx * lx + ly * ly + (lz + 1f) * (lz + 1f))
        val hx = lx / hLen
        val hy = ly / hLen
        val hz = (lz + 1f) / hLen
        val params =
            RakingLightParams(lx, ly, lz, hx, hy, hz, impastoDepth.coerceIn(0f, 1f) * 3.2f, gloss.coerceIn(0f, 1f) * 0.38f)
        for ((tx, ty) in plane.allocatedTileCoords()) {
            val x0 = max(1, tx * PhysicalPaintPlane.TILE_SIZE)
            val y0 = max(1, ty * PhysicalPaintPlane.TILE_SIZE)
            val x1 = min(w - 2, (tx + 1) * PhysicalPaintPlane.TILE_SIZE - 1)
            val y1 = min(h - 2, (ty + 1) * PhysicalPaintPlane.TILE_SIZE - 1)
            shadeRakingTile(colorBuffer, plane, x0, y0, x1, y1, params)
        }
    }

    private class RakingLightParams(
        val lx: Float,
        val ly: Float,
        val lz: Float,
        val hx: Float,
        val hy: Float,
        val hz: Float,
        val slopeScale: Float,
        val specStrength: Float,
    )

    private fun shadeRakingTile(
        colorBuffer: PixelBuffer,
        plane: PhysicalPaintPlane,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        p: RakingLightParams,
    ) {
        for (y in y0..y1) {
            val row = y * colorBuffer.width
            for (x in x0..x1) {
                val pixel = colorBuffer.pixels[row + x]
                val alpha = pixel ushr 24
                val dx = (plane.impastoAt(x + 1, y) - plane.impastoAt(x - 1, y)) * p.slopeScale
                val dy = (plane.impastoAt(x, y + 1) - plane.impastoAt(x, y - 1)) * p.slopeScale
                if (alpha == 0 || (dx == 0f && dy == 0f)) continue
                val invLen = 1f / sqrt(dx * dx + dy * dy + 1f)
                val nx = -dx * invLen
                val ny = -dy * invLen
                val nz = invLen

                val ndotl = (nx * p.lx + ny * p.ly + nz * p.lz).coerceIn(0f, 1f)
                val diffuseBoost = 1f + (ndotl - p.lz) * 0.65f
                val ndoth = (nx * p.hx + ny * p.hy + nz * p.hz).coerceIn(0f, 1f)
                val spec = ndoth.pow(24f) * p.specStrength * 255f

                val r = (((pixel shr 16) and 0xFF) * diffuseBoost + spec).roundToInt().coerceIn(0, 255)
                val g = (((pixel shr 8) and 0xFF) * diffuseBoost + spec).roundToInt().coerceIn(0, 255)
                val b = ((pixel and 0xFF) * diffuseBoost + spec).roundToInt().coerceIn(0, 255)
                colorBuffer.pixels[row + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun weightedNeighborColor(
        c1: Int,
        w1: Float,
        c2: Int,
        w2: Float,
        c3: Int,
        w3: Float,
        c4: Int,
        w4: Float,
    ): Int {
        var sumW = 0f
        var sumA = 0f
        var sumR = 0f
        var sumG = 0f
        var sumB = 0f

        fun add(
            c: Int,
            w: Float,
        ) {
            val a = (c ushr 24) / 255f
            if (a <= 0f || w <= 0f) return
            val wt = w * a
            sumW += wt
            sumA += w * a
            sumR += ((c shr 16) and 0xFF) * wt
            sumG += ((c shr 8) and 0xFF) * wt
            sumB += (c and 0xFF) * wt
        }
        add(c1, w1)
        add(c2, w2)
        add(c3, w3)
        add(c4, w4)
        if (sumW <= 1e-4f) return 0
        val totalWeights = (w1 + w2 + w3 + w4).coerceAtLeast(1e-4f)
        return Channels.argb(
            ((sumA / totalWeights) * 255f).roundToInt().coerceIn(0, 255),
            (sumR / sumW).roundToInt().coerceIn(0, 255),
            (sumG / sumW).roundToInt().coerceIn(0, 255),
            (sumB / sumW).roundToInt().coerceIn(0, 255),
        )
    }

    private fun scaleRgb(
        argb: Int,
        factor: Float,
    ): Int {
        val a = argb ushr 24
        val r = (((argb shr 16) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
