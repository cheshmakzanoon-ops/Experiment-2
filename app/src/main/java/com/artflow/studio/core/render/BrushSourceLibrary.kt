package com.artflow.studio.core.render

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Curated built-in Shape and Grain Source Library, mirroring Procreate's Source Library.
 * Every source is generated deterministically on demand and cached in memory so saved strokes
 * and custom brushes replay identically across devices without bundling large binary assets.
 */
object BrushSourceLibrary {
    const val SHAPE_PREFIX = "source-shape-"
    const val GRAIN_PREFIX = "source-grain-"
    private const val TILE_SIZE = 128
    private const val TWO_PI = (2.0 * PI).toFloat()

    enum class ShapeSource(
        val id: String,
        val label: String,
    ) {
        SOFT_ROUND("${SHAPE_PREFIX}soft-round", "Soft round"),
        AIRBRUSH("${SHAPE_PREFIX}airbrush", "Airbrush"),
        FLAT_CHISEL("${SHAPE_PREFIX}flat-chisel", "Flat chisel"),
        CALLIGRAPHY("${SHAPE_PREFIX}calligraphy", "Calligraphy"),
        BRISTLE_FAN("${SHAPE_PREFIX}bristle-fan", "Bristle fan"),
        DRY_BRISTLE("${SHAPE_PREFIX}dry-bristle", "Dry bristle"),
        CHARCOAL("${SHAPE_PREFIX}charcoal", "Charcoal"),
        CHALK("${SHAPE_PREFIX}chalk", "Chalk"),
        INK_SPLATTER("${SHAPE_PREFIX}ink-splatter", "Splatter"),
        STIPPLE("${SHAPE_PREFIX}stipple", "Stipple"),
        WATERCOLOR("${SHAPE_PREFIX}watercolor", "Wash blot"),
        ORGANIC_LEAF("${SHAPE_PREFIX}leaf", "Leaf"),
    }

    enum class GrainSource(
        val id: String,
        val label: String,
    ) {
        COLD_PRESS("${GRAIN_PREFIX}cold-press", "Cold press"),
        HOT_PRESS("${GRAIN_PREFIX}hot-press", "Hot press"),
        ROUGH_LINEN("${GRAIN_PREFIX}rough-linen", "Rough linen"),
        WOODGRAIN("${GRAIN_PREFIX}woodgrain", "Woodgrain"),
        CONCRETE("${GRAIN_PREFIX}concrete", "Concrete"),
        PASTEL_TOOTH("${GRAIN_PREFIX}pastel", "Pastel tooth"),
    }

    private val shapeCache = ConcurrentHashMap<ShapeSource, CustomGrains.Tile>()
    private val grainCache = ConcurrentHashMap<GrainSource, CustomGrains.Tile>()

    private val shapesById = ShapeSource.entries.associateBy { it.id }
    private val grainsById = GrainSource.entries.associateBy { it.id }

    fun isBuiltInShape(id: String?): Boolean = id != null && id in shapesById

    fun isBuiltInGrain(id: String?): Boolean = id != null && id in grainsById

    fun shapeTile(source: ShapeSource): CustomGrains.Tile = shapeCache.computeIfAbsent(source, ::buildShape)

    fun grainTile(source: GrainSource): CustomGrains.Tile = grainCache.computeIfAbsent(source, ::buildGrain)

    /** Resolves either a built-in Shape Source or a user-imported custom shape tile. */
    fun resolveShape(id: String?): CustomGrains.Tile? {
        if (id == null) return null
        shapesById[id]?.let { return shapeTile(it) }
        return CustomGrains.get(id)
    }

    /** Resolves either a built-in Grain Source or a user-imported custom grain tile. */
    fun resolveGrain(id: String?): CustomGrains.Tile? {
        if (id == null) return null
        grainsById[id]?.let { return grainTile(it) }
        return CustomGrains.get(id)
    }

    /** Generates grayscale ARGB preview pixels for UI thumbnails. */
    fun previewPixels(
        tile: CustomGrains.Tile,
        size: Int,
    ): IntArray {
        require(size > 0) { "Preview size must be positive" }
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val v = (y + 0.5f) / size
            val row = y * size
            for (x in 0 until size) {
                val u = (x + 0.5f) / size
                val level = (tile.sample(u, v) * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[row + x] = (0xFF shl 24) or (level shl 16) or (level shl 8) or level
            }
        }
        return pixels
    }

    private fun buildShape(source: ShapeSource): CustomGrains.Tile {
        val values = ByteArray(TILE_SIZE * TILE_SIZE)
        for (y in 0 until TILE_SIZE) {
            val ny = ((y + 0.5f) / TILE_SIZE) * 2f - 1f
            val row = y * TILE_SIZE
            for (x in 0 until TILE_SIZE) {
                val nx = ((x + 0.5f) / TILE_SIZE) * 2f - 1f
                val coverage = evaluateShape(source, nx, ny).coerceIn(0f, 1f)
                values[row + x] = (coverage * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return CustomGrains.Tile(TILE_SIZE, values)
    }

    private fun evaluateShape(
        source: ShapeSource,
        nx: Float,
        ny: Float,
    ): Float {
        val r = hypot(nx, ny)
        return when (source) {
            ShapeSource.SOFT_ROUND -> if (r >= 1f) 0f else exp(-3.5f * r * r) * (1f - r * r)
            ShapeSource.AIRBRUSH -> {
                if (r >= 1f) return 0f
                val envelope = (1f - r).let { it * it }
                val grain = 0.45f + 0.55f * valueNoise(nx * 14f, ny * 14f, 11)
                envelope * grain
            }
            ShapeSource.FLAT_CHISEL -> {
                val ax = abs(nx)
                val ay = abs(ny)
                val edgeX = smoothstep(0.92f, 0.78f, ax)
                val edgeY = smoothstep(0.36f, 0.24f, ay)
                edgeX * edgeY
            }
            ShapeSource.CALLIGRAPHY -> {
                val rx = nx * 0.866f + ny * 0.5f
                val ry = -nx * 0.5f + ny * 0.866f
                val d = hypot(rx / 0.88f, ry / 0.22f)
                smoothstep(1.0f, 0.84f, d)
            }
            ShapeSource.BRISTLE_FAN -> {
                if (r >= 0.96f) return 0f
                val env = smoothstep(0.96f, 0.65f, r)
                val bristles = valueNoise(nx * 22f, ny * 4f, 23)
                val tuft = smoothstep(0.32f, 0.72f, bristles)
                env * tuft
            }
            ShapeSource.DRY_BRISTLE -> {
                val ax = abs(nx)
                val ay = abs(ny)
                if (ax >= 0.95f || ay >= 0.55f) return 0f
                val env = smoothstep(0.95f, 0.7f, ax) * smoothstep(0.55f, 0.35f, ay)
                val streaks = valueNoise(nx * 3.5f, ny * 28f, 37)
                val breakup = valueNoise(nx * 12f, ny * 12f, 41)
                env * smoothstep(0.38f, 0.78f, streaks * 0.75f + breakup * 0.25f)
            }
            ShapeSource.CHARCOAL -> {
                val angle = atan2(ny, nx)
                val wobble = 0.14f * sin(angle * 3f + 0.7f) + 0.08f * cos(angle * 5f - 0.4f)
                val edge = smoothstep(0.92f + wobble, 0.62f + wobble, r)
                val grit = valueNoise(nx * 11f, ny * 11f, 53) * 0.6f + valueNoise(nx * 24f, ny * 24f, 59) * 0.4f
                edge * smoothstep(0.22f, 0.75f, grit)
            }
            ShapeSource.CHALK -> {
                val env = smoothstep(0.88f, 0.72f, abs(nx)) * smoothstep(0.62f, 0.48f, abs(ny))
                val pits = valueNoise(nx * 16f, ny * 16f, 67)
                env * smoothstep(0.28f, 0.68f, pits)
            }
            ShapeSource.INK_SPLATTER -> evaluateSplatter(nx, ny, r)
            ShapeSource.STIPPLE -> evaluateStipple(nx, ny, r)
            ShapeSource.WATERCOLOR -> {
                val angle = atan2(ny, nx)
                val wave = 0.12f * sin(angle * 4f) + 0.07f * cos(angle * 7f + 1.1f)
                val edge = smoothstep(0.92f + wave, 0.68f + wave, r)
                val rim = 0.72f + 0.28f * smoothstep(0.45f, 0.85f, r)
                val wash = 0.75f + 0.25f * valueNoise(nx * 6f, ny * 6f, 79)
                edge * rim * wash
            }
            ShapeSource.ORGANIC_LEAF -> {
                val u = (nx + 0.9f) / 1.8f
                if (u <= 0f || u >= 1f) return 0f
                val halfWidth = sin(u * PI.toFloat()) * (0.48f - 0.14f * u)
                val d = abs(ny) / halfWidth.coerceAtLeast(0.001f)
                val vein = 0.88f + 0.12f * smoothstep(0.08f, 0.0f, abs(ny))
                smoothstep(1.0f, 0.82f, d) * vein
            }
        }
    }

    private fun evaluateSplatter(
        nx: Float,
        ny: Float,
        r: Float,
    ): Float {
        val core = smoothstep(0.48f, 0.34f, r)
        var best = core
        for (i in 0 until 14) {
            val a = hashFloat(i * 17 + 3) * TWO_PI
            val dist = 0.38f + hashFloat(i * 31 + 7) * 0.52f
            val rad = 0.05f + hashFloat(i * 43 + 11) * 0.11f
            val d = hypot(nx - cos(a) * dist, ny - sin(a) * dist)
            val drop = smoothstep(rad, rad * 0.55f, d)
            if (drop > best) best = drop
        }
        return best
    }

    private fun evaluateStipple(
        nx: Float,
        ny: Float,
        r: Float,
    ): Float {
        if (r >= 0.98f) return 0f
        var best = 0f
        for (i in 0 until 24) {
            val a = hashFloat(i * 19 + 5) * TWO_PI
            val dist = sqrt(hashFloat(i * 29 + 13)) * 0.86f
            val rad = 0.055f + hashFloat(i * 37 + 17) * 0.055f
            val d = hypot(nx - cos(a) * dist, ny - sin(a) * dist)
            val dot = smoothstep(rad, rad * 0.45f, d)
            if (dot > best) best = dot
        }
        return best
    }

    private fun buildGrain(source: GrainSource): CustomGrains.Tile {
        val values = ByteArray(TILE_SIZE * TILE_SIZE)
        for (y in 0 until TILE_SIZE) {
            val row = y * TILE_SIZE
            for (x in 0 until TILE_SIZE) {
                val level = evaluateGrain(source, x, y).coerceIn(0f, 1f)
                values[row + x] = (level * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return CustomGrains.Tile(TILE_SIZE, values)
    }

    private fun evaluateGrain(
        source: GrainSource,
        x: Int,
        y: Int,
    ): Float =
        when (source) {
            GrainSource.COLD_PRESS -> {
                val coarse = periodicNoise(x, y, 16, 101)
                val medium = periodicNoise(x, y, 8, 103)
                val fine = periodicNoise(x, y, 4, 107)
                (0.28f + 0.72f * (coarse * 0.5f + medium * 0.35f + fine * 0.15f))
            }
            GrainSource.HOT_PRESS -> {
                val fine = periodicNoise(x, y, 4, 109)
                val micro = periodicNoise(x, y, 2, 113)
                (0.58f + 0.42f * (fine * 0.65f + micro * 0.35f))
            }
            GrainSource.ROUGH_LINEN -> {
                val warp = sin((x / TILE_SIZE.toFloat()) * TWO_PI * 16f) * 0.5f + 0.5f
                val weft = sin((y / TILE_SIZE.toFloat()) * TWO_PI * 16f) * 0.5f + 0.5f
                val slub = periodicNoise(x, y, 8, 127)
                (0.22f + 0.78f * (warp * 0.42f + weft * 0.42f + slub * 0.16f))
            }
            GrainSource.WOODGRAIN -> {
                val nx = x / TILE_SIZE.toFloat()
                val ny = y / TILE_SIZE.toFloat()
                val distort = periodicNoise(x, y, 16, 131) * 0.35f
                val rings = sin((nx * 6f + distort + 0.12f * sin(ny * TWO_PI * 2f)) * TWO_PI) * 0.5f + 0.5f
                val fibers = periodicNoise(x, y, 4, 137)
                (0.25f + 0.75f * (rings * 0.7f + fibers * 0.3f))
            }
            GrainSource.CONCRETE -> {
                val base = periodicNoise(x, y, 16, 139)
                val grit = periodicNoise(x, y, 4, 149)
                val pit = if (periodicNoise(x, y, 2, 151) < 0.22f) 0.15f else 1f
                (0.2f + 0.8f * (base * 0.45f + grit * 0.55f)) * pit
            }
            GrainSource.PASTEL_TOOTH -> {
                val tooth = periodicNoise(x, y, 3, 157)
                val paper = periodicNoise(x, y, 8, 163)
                smoothstep(0.18f, 0.84f, tooth * 0.7f + paper * 0.3f)
            }
        }

    /** Seamless periodic bilinear lattice noise across [TILE_SIZE]. */
    private fun periodicNoise(
        x: Int,
        y: Int,
        cell: Int,
        seed: Int,
    ): Float {
        val period = (TILE_SIZE / cell).coerceAtLeast(1)
        val fx = x.toFloat() / cell
        val fy = y.toFloat() / cell
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = smooth(fx - x0)
        val ty = smooth(fy - y0)
        val ix0 = Math.floorMod(x0, period)
        val iy0 = Math.floorMod(y0, period)
        val ix1 = Math.floorMod(x0 + 1, period)
        val iy1 = Math.floorMod(y0 + 1, period)
        val c00 = hash2d(ix0, iy0, seed)
        val c10 = hash2d(ix1, iy0, seed)
        val c01 = hash2d(ix0, iy1, seed)
        val c11 = hash2d(ix1, iy1, seed)
        val top = c00 + (c10 - c00) * tx
        val bottom = c01 + (c11 - c01) * tx
        return top + (bottom - top) * ty
    }

    private fun valueNoise(
        x: Float,
        y: Float,
        seed: Int,
    ): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val tx = smooth(x - x0)
        val ty = smooth(y - y0)
        val c00 = hash2d(x0, y0, seed)
        val c10 = hash2d(x0 + 1, y0, seed)
        val c01 = hash2d(x0, y0 + 1, seed)
        val c11 = hash2d(x0 + 1, y0 + 1, seed)
        val top = c00 + (c10 - c00) * tx
        val bottom = c01 + (c11 - c01) * tx
        return top + (bottom - top) * ty
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    private fun smoothstep(
        edge0: Float,
        edge1: Float,
        x: Float,
    ): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun hash2d(
        x: Int,
        y: Int,
        seed: Int,
    ): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun hashFloat(index: Int): Float = hash2d(index, index xor 0x5A5A, 19)
}
