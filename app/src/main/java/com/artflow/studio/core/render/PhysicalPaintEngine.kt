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
    }

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
        if (w == 0f && h == 0f && p == 0f && tiles[tileKey(x / TILE_SIZE, y / TILE_SIZE)] == null) return
        val tile = obtainTile(x / TILE_SIZE, y / TILE_SIZE)
        val idx = localIndex(x, y)
        tile.wetness[idx] = w
        tile.impastoHeight[idx] = h
        tile.pigmentLoad[idx] = p
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
                    data.writeInt((key ushr 32).toInt())
                    data.writeInt((key and 0xFFFFFFFFL).toInt())
                    for (i in 0 until TILE_PIXELS) {
                        data.writeByte((tile.wetness[i] * 255f + 0.5f).toInt().coerceIn(0, 255))
                        data.writeByte(((tile.impastoHeight[i] * 0.5f + 0.5f) * 255f + 0.5f).toInt().coerceIn(0, 255))
                        data.writeByte((tile.pigmentLoad[i] * 255f + 0.5f).toInt().coerceIn(0, 255))
                    }
                }
            }
        }
        return out.toByteArray()
    }

    companion object {
        const val TILE_SIZE = 64
        const val TILE_PIXELS = TILE_SIZE * TILE_SIZE
        private const val BINARY_MAGIC = 0x41465050 // "AFPP"
        private const val BINARY_VERSION = 1
        private const val MAX_TILES = 16_384

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
                        val tile = plane.obtainTile(tx, ty)
                        for (i in 0 until TILE_PIXELS) {
                            tile.wetness[i] = data.readUnsignedByte() / 255f
                            tile.impastoHeight[i] = (data.readUnsignedByte() / 255f) * 2f - 1f
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
                if (dist > r) continue
                val falloff = 1f - (dist / r).let { it * it }
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
                if (dist > r) continue
                val normR = dist / r
                val across = (dx * perpX + dy * perpY) / r
                // Bristle grooves across the brush width + subtle paint ridge heaped along the stroke rim.
                val bristles = 0.55f + 0.45f * sin(across * 18f)
                val rimPile = if (normR > 0.65f) 1.25f * (1f - (normR - 0.65f) / 0.35f) else 0.85f
                val deposit = thick * bristles * rimPile * (1f - normR * normR)
                val current = plane.impastoAt(x, y)
                val nextH = (current * 0.65f + deposit).coerceIn(-1f, 1f)
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
                val x0 = max(1, tx * PhysicalPaintPlane.TILE_SIZE)
                val y0 = max(1, ty * PhysicalPaintPlane.TILE_SIZE)
                val x1 = min(w - 2, (tx + 1) * PhysicalPaintPlane.TILE_SIZE - 1)
                val y1 = min(h - 2, (ty + 1) * PhysicalPaintPlane.TILE_SIZE - 1)

                for (y in y0..y1) {
                    val row = y * colorBuffer.width
                    for (x in x0..x1) {
                        val wet = plane.wetnessAt(x, y)
                        if (wet < 0.02f) continue

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
        val slopeScale = impastoDepth.coerceIn(0f, 1f) * 3.2f
        val specStrength = gloss.coerceIn(0f, 1f) * 0.38f

        for ((tx, ty) in plane.allocatedTileCoords()) {
            val x0 = max(1, tx * PhysicalPaintPlane.TILE_SIZE)
            val y0 = max(1, ty * PhysicalPaintPlane.TILE_SIZE)
            val x1 = min(w - 2, (tx + 1) * PhysicalPaintPlane.TILE_SIZE - 1)
            val y1 = min(h - 2, (ty + 1) * PhysicalPaintPlane.TILE_SIZE - 1)
            for (y in y0..y1) {
                val row = y * colorBuffer.width
                for (x in x0..x1) {
                    val pixel = colorBuffer.pixels[row + x]
                    val alpha = pixel ushr 24
                    if (alpha == 0) continue
                    val dx = (plane.impastoAt(x + 1, y) - plane.impastoAt(x - 1, y)) * slopeScale
                    val dy = (plane.impastoAt(x, y + 1) - plane.impastoAt(x, y - 1)) * slopeScale
                    if (dx == 0f && dy == 0f) continue
                    val invLen = 1f / sqrt(dx * dx + dy * dy + 1f)
                    val nx = -dx * invLen
                    val ny = -dy * invLen
                    val nz = invLen

                    val ndotl = (nx * lx + ny * ly + nz * lz).coerceIn(0f, 1f)
                    val flatNdotL = lz
                    // Relative diffuse modulation around flat lighting so unridged paint stays unchanged.
                    val diffuseBoost = 1f + (ndotl - flatNdotL) * 0.65f
                    val ndoth = (nx * hx + ny * hy + nz * hz).coerceIn(0f, 1f)
                    val spec = ndoth.pow(24f) * specStrength * 255f

                    val r = (((pixel shr 16) and 0xFF) * diffuseBoost + spec).roundToInt().coerceIn(0, 255)
                    val g = (((pixel shr 8) and 0xFF) * diffuseBoost + spec).roundToInt().coerceIn(0, 255)
                    val b = ((pixel and 0xFF) * diffuseBoost + spec).roundToInt().coerceIn(0, 255)
                    colorBuffer.pixels[row + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
                }
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
