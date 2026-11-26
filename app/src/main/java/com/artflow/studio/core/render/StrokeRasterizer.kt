package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.PixelBufferPool
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.Stamping
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.GrainBlend
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.BlendMode
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Rasterises the vector stroke model into pixels.
 *
 * One implementation serves the live canvas, the saved composite and every export, so what the
 * artist sees is exactly what gets saved. Replaces the previous `android.graphics`-based renderer,
 * which could only approximate several brush parameters and could not be unit-tested.
 *
 * Key behaviours:
 * - Segments are stamped as capsules (round caps), which is what makes pressure-varying widths
 *   look continuous rather than scalloped.
 * - A stroke's overall opacity is applied **once** through a scratch buffer, so a semi-transparent
 *   stroke does not darken itself where its own segments overlap.
 * - Colour jitter, spacing, scatter, count and velocity dynamics are honoured per dab.
 * - Dab placement is O(N): the spacing grid carries across segment boundaries, taper depth is
 *   accumulated along the path instead of rescanning the point list, and the path end receives at
 *   most one closing dab.
 */
class StrokeRasterizer(
    /** Canvas position of the target's top-left pixel, so grain stays anchored when rendering a crop. */
    private val originX: Int = 0,
    private val originY: Int = 0,
) {
    /**
     * Bounded pool of scratch buffers reused across strokes. Unlike a [ThreadLocal], a shared
     * bounded pool never leaks permanent full-canvas buffers on worker threads and [release]
     * immediately frees memory allocated by every thread.
     */
    private val scratchPool = PixelBufferPool(maxCapacity = SCRATCH_POOL_CAPACITY, maxBytes = SCRATCH_POOL_MAX_BYTES)

    /** Statistics from the last rasterisation, for the performance panel. */
    @Volatile
    var lastDabCount: Int = 0
        private set

    /** Accumulator behind [lastDabCount]; incremented from the dab loops. */
    private val dabCount = AtomicInteger(0)

    /**
     * Draws [stroke] into [target], honouring [BrushParams] dynamics.
     *
     * @param alphaLock restricts paint to pixels that are already opaque.
     * @param mask optional selection coverage.
     */
    fun draw(
        target: PixelBuffer,
        stroke: Stroke,
        alphaLock: Boolean = false,
        mask: SelectionMask? = null,
        physicalPlane: PhysicalPaintPlane? = null,
    ) {
        val points = stroke.points
        if (points.isEmpty()) return

        require(points.all { it.x.isFinite() && it.y.isFinite() && it.pressure.isFinite() }) {
            "Stroke coordinates and pressure must be finite"
        }
        val strokeAlpha = strokeAlpha(stroke)
        if (strokeAlpha <= 0f) return
        // Global opacity is applied below exactly once. Per-sample pressure and flow stay local.
        val params = stroke.brushParams.copy(opacity = 1f)
        val random = Random(stroke.id)
        dabCount.set(0)
        lastDabCount = 0

        if (stroke.isEraser) {
            eraseStroke(target, stroke, params, points, strokeAlpha, mask, random)
            return
        }

        val reach = StrokeReach.bounds(points, params, target.width, target.height)
        if (reach.isEmpty) return
        val buffer = scratchPool.obtain(target.width, target.height, clear = false)
        val maskedPhysical =
            physicalPlane != null &&
                (params.waterAmount > 0f || params.impastoThickness > 0f) &&
                (params.blendTexture || params.dual != null)
        var dual: DualMarks? = null
        try {
            clear(buffer, reach)
            drawStrokeInto(
                buffer,
                stroke,
                params,
                points,
                alphaLock = false,
                mask = null,
                random = random,
                canvas = target,
                physicalPlane = physicalPlane,
                physicalCoverage = if (physicalPlane == null) null else physicalCoverage(target, buffer, strokeAlpha, mask, alphaLock),
                rasterize = !maskedPhysical,
            )
            dual = params.dual?.let { drawDual(target, stroke, it, reach) }
            val how = Deposit(params, strokeAlpha, mask, alphaLock, grainOrigin(params, points), grainPressure(params, points), random)
            if (maskedPhysical) {
                // Grain jitter uses the post-dab random state. The first traversal resolves that
                // state without painting or retaining dabs; now both material and color can use
                // the same texture and dual marks. Only this masked physical path walks twice.
                drawStrokeInto(
                    buffer,
                    stroke,
                    params,
                    points,
                    alphaLock = false,
                    mask = null,
                    random = Random(stroke.id),
                    canvas = target,
                    physicalPlane = physicalPlane,
                    physicalCoverage = physicalCoverage(target, buffer, strokeAlpha, mask, alphaLock, how, dual),
                )
            }
            depositCoverage(
                target,
                buffer,
                reach,
                how,
                dual,
            )
            lastDabCount = dabCount.get()
        } finally {
            dual?.let { scratchPool.release(it.marks) }
            scratchPool.release(buffer)
        }
    }

    /** Physical paint obeys selection, alpha lock and opacity, plus the resolved grain and dual marks when present. */
    private fun physicalCoverage(
        canvas: PixelBuffer,
        marks: PixelBuffer,
        opacity: Float,
        selection: SelectionMask?,
        alphaLock: Boolean,
        how: Deposit? = null,
        dual: DualMarks? = null,
    ): (Int, Int) -> Float =
        coverage@{ x, y ->
            if (x !in 0 until marks.width || y !in 0 until marks.height) return@coverage 0f
            val canvasX = originX + x
            val canvasY = originY + y
            val index = y * marks.width + x
            val source = dual?.combine(marks.pixels[index], index, canvasX, canvasY) ?: marks.pixels[index]
            val mark = (source ushr 24) / 255f
            if (mark <= 0f) return@coverage 0f
            val selected = selection?.let { it.coverageAt(x, y) / 255f } ?: 1f
            if (selected <= 0f) return@coverage 0f
            val existing = if (alphaLock) (canvas.getSafe(x, y) ushr 24) / 255f else 1f
            if (existing <= 0f) return@coverage 0f
            val textureFactor =
                how?.let {
                    val grain = it.texture?.coverage(canvasX - it.grain.first, canvasY - it.grain.second) ?: 1f
                    grainFactor(source, grain, it.params) * wetEdgeFactor(source, it.wetEdges)
                } ?: 1f
            // Keep continuous material amounts, but do not allocate material for a visible mark
            // that the grain/dual mask reduces below the final color buffer's alpha precision.
            if (how != null && (Channels.scaleAlpha(source, opacity * selected * textureFactor) ushr 24) == 0) return@coverage 0f
            mark * opacity * selected * existing * textureFactor
        }

    /** How a stroke's coverage becomes paint on its layer. */
    private class Deposit(
        val params: BrushParams,
        val strokeAlpha: Float,
        val mask: SelectionMask?,
        val alphaLock: Boolean,
        val grain: Pair<Int, Int>,
        strokePressure: Float,
        grainRandom: Random,
    ) {
        val texture =
            if (BrushTexture.hasPressureCurves(params)) {
                BrushTexture.from(params, strokePressure, grainRandom)
            } else {
                BrushTexture.from(params)
            }
        val wetEdges = params.wetEdges.coerceIn(0f, 1f)
        val burntEdges = params.burntEdges.coerceIn(0f, 1f)
    }

    /** Lays the stroke's coverage in [buffer] onto [target] within [reach]: grain, wet edges, opacity, blend. */
    private fun depositCoverage(
        target: PixelBuffer,
        buffer: PixelBuffer,
        reach: IntBounds,
        how: Deposit,
        dual: DualMarks? = null,
        srcOffsetX: Int = 0,
        srcOffsetY: Int = 0,
    ) {
        val targetPixels = target.pixels
        val bufferPixels = buffer.pixels
        val targetWidth = target.width
        val bufferWidth = buffer.width
        val mode = how.params.blendMode
        val isNormal = mode == BlendMode.NORMAL || mode == BlendMode.PASS_THROUGH
        val isSimple =
            dual == null &&
                how.mask == null &&
                how.texture == null &&
                how.wetEdges <= 0f &&
                how.burntEdges <= 0f &&
                isNormal

        if (isSimple) {
            depositSimpleCoverage(target, buffer, reach, how, srcOffsetX, srcOffsetY)
            return
        }

        val grainX = how.grain.first
        val grainY = how.grain.second
        val texture = how.texture
        val mask = how.mask
        val maskMatches = mask != null && mask.width == targetWidth && mask.height == target.height
        val wetEdges = how.wetEdges
        val burntEdges = how.burntEdges
        val strokeAlpha = how.strokeAlpha
        val params = how.params
        val alphaLock = how.alphaLock
        for (y in reach.top..reach.bottom) {
            val dstRow = y * targetWidth
            val srcRow = (y + srcOffsetY) * bufferWidth + srcOffsetX
            val canvasY = originY + y
            for (x in reach.left..reach.right) {
                val dstIndex = dstRow + x
                val srcIndex = srcRow + x
                val canvasX = originX + x
                val source = dual?.combine(bufferPixels[srcIndex], srcIndex, canvasX, canvasY) ?: bufferPixels[srcIndex]
                if ((source ushr 24) == 0) continue
                val coverage =
                    when {
                        mask == null -> 1f
                        maskMatches -> mask.alphaAt(dstIndex)
                        else -> mask.coverageAt(x, y) / 255f
                    }
                val grain = texture?.coverage(canvasX - grainX, canvasY - grainY) ?: 1f
                val effective = strokeAlpha * coverage * grainFactor(source, grain, params) * wetEdgeFactor(source, wetEdges)
                if (effective <= 0f) continue
                val paint = Channels.scaleAlpha(burnt(source, burntEdges), effective)
                targetPixels[dstIndex] = deposit(targetPixels[dstIndex], paint, params, alphaLock)
            }
        }
    }

    private fun depositSimpleCoverage(
        target: PixelBuffer,
        buffer: PixelBuffer,
        reach: IntBounds,
        how: Deposit,
        srcOffsetX: Int,
        srcOffsetY: Int,
    ) {
        val targetPixels = target.pixels
        val targetWidth = target.width
        val bufferPixels = buffer.pixels
        val bufferWidth = buffer.width
        val fullAlpha = how.strokeAlpha >= 1f
        val alphaLock = how.alphaLock
        val strokeAlpha = how.strokeAlpha
        for (y in reach.top..reach.bottom) {
            val dstRow = y * targetWidth
            val srcRow = (y + srcOffsetY) * bufferWidth + srcOffsetX
            for (x in reach.left..reach.right) {
                val source = bufferPixels[srcRow + x]
                if ((source ushr 24) == 0) continue
                val paint = if (fullAlpha) source else Channels.scaleAlpha(source, strokeAlpha)
                val dstIndex = dstRow + x
                targetPixels[dstIndex] =
                    if (alphaLock) {
                        BlendModes.sourceAtop(targetPixels[dstIndex], paint)
                    } else {
                        BlendModes.sourceOver(targetPixels[dstIndex], paint)
                    }
            }
        }
    }

    // --- Live strokes ---------------------------------------------------------------------------

    /**
     * A stroke being drawn, kept from frame to frame: the dabs its points have produced so far stay
     * in [coverage] (canvas coordinates), so each frame stamps only the dabs new points add. Drawing
     * it gives exactly what [draw] gives for the stroke so far. Not thread-safe: callers serialise.
     */
    class LiveStroke internal constructor(
        internal val coverage: PixelBuffer,
        internal val random: ReplayableRandom,
    ) {
        internal var tip: Stamping.TipShape? = null
        internal var next = 1
        internal val walk = DabWalk()
    }

    /** Starts [stroke] live on a [width] × [height] canvas; check [canDrawLive] first. */
    fun startLive(
        stroke: Stroke,
        width: Int,
        height: Int,
    ): LiveStroke = LiveStroke(PixelBuffer(width, height), ReplayableRandom(stroke.id))

    /**
     * Paints the live [stroke] (canvas coordinates) into [target], the layer's pixels starting at
     * this rasterizer's origin, exactly as [draw] would paint the whole stroke so far. [canvas] is
     * the whole layer being painted on, in canvas coordinates, for wet mix.
     */
    fun drawLive(
        target: PixelBuffer,
        live: LiveStroke,
        stroke: Stroke,
        canvas: PixelBuffer?,
        alphaLock: Boolean = false,
        mask: SelectionMask? = null,
    ) {
        val points = stroke.points
        val strokeAlpha = strokeAlpha(stroke)
        if (points.isEmpty() || strokeAlpha <= 0f) return
        val params = stroke.brushParams.copy(opacity = 1f)
        val tip = live.tip ?: tipFor(params, live.random).also { live.tip = it }
        // New segments join the kept coverage; the stroke's own random sequence carries on.
        walkSegments(DabContext(live.coverage, stroke, params, 0f, false, null, live.random, tip, canvas), points, live.next, live.walk)
        live.next = max(live.next, points.size)

        val coverage = live.coverage
        val covered = StrokeReach.bounds(points, params, coverage.width, coverage.height)
        val left = max(covered.left, originX)
        val top = max(covered.top, originY)
        val right = min(covered.right, originX + target.width - 1)
        val bottom = min(covered.bottom, originY + target.height - 1)
        if (right < left || bottom < top) return
        // The closing dab belongs to this frame only: it is stamped where a full redraw stamps it,
        // with a copy of the random, deposited directly from [coverage], and restored afterwards.
        val end = StrokeReach.bounds(listOf(points.last()), params, coverage.width, coverage.height)
        // A closing dab far off the canvas reaches no pixels, and then there is nothing to keep.
        val kept = if (end.isEmpty) null else coverage.crop(end)
        val carried = live.walk.carried
        try {
            // Grain jitter follows the closing dab in replay, without consuming the next live dab's random state.
            val frameRandom = live.random.copy()
            stampEnd(DabContext(coverage, stroke, params, 0f, false, null, frameRandom, tip, canvas), points.takeLast(2), live.walk)
            val reach = IntBounds(left - originX, top - originY, right - originX, bottom - originY)
            depositCoverage(
                target = target,
                buffer = coverage,
                reach = reach,
                how =
                    Deposit(
                        params,
                        strokeAlpha,
                        mask,
                        alphaLock,
                        grainOrigin(params, points, canvasCoordinates = true),
                        grainPressure(params, points),
                        frameRandom,
                    ),
                srcOffsetX = originX,
                srcOffsetY = originY,
            )
        } finally {
            live.walk.carried = carried
            if (kept != null) {
                for (y in end.top..end.bottom) {
                    System.arraycopy(kept.pixels, (y - end.top) * kept.width, coverage.pixels, y * coverage.width + end.left, kept.width)
                }
            }
        }
    }

    /** Merges [paint] into [backdrop] with the brush's blend mode; alpha lock keeps the backdrop's alpha. */
    private fun deposit(
        backdrop: Int,
        paint: Int,
        params: BrushParams,
        alphaLock: Boolean,
    ): Int {
        val mode = params.blendMode
        if (mode == BlendMode.NORMAL || mode == BlendMode.PASS_THROUGH) {
            return if (alphaLock) BlendModes.sourceAtop(backdrop, paint) else BlendModes.sourceOver(backdrop, paint)
        }
        val blended = BlendModes.blend(backdrop, paint, mode)
        return if (alphaLock) Channels.withAlpha(blended, backdrop ushr 24) else blended
    }

    /**
     * Wet edges thin the middle of a stroke and pool paint where its coverage falls off, like
     * watercolour drying at its rim. Returns the factor applied to the stroke's alpha there.
     */
    private fun wetEdgeFactor(
        source: Int,
        wetEdges: Float,
    ): Float {
        if (wetEdges <= 0f) return 1f
        val a = (source ushr 24) / 255f
        val rim = 4f * a * (1f - a)
        return ((1f - 0.5f * wetEdges) + wetEdges * rim * 0.75f / a.coerceAtLeast(0.01f)).coerceIn(0f, 1f / a.coerceAtLeast(0.01f))
    }

    /**
     * Burnt edges darken the paint where the stroke's coverage falls off, multiplying the colour
     * into itself toward its rim (Procreate's Burnt Edges, in its default Multiply mode).
     */
    private fun burnt(
        source: Int,
        burntEdges: Float,
    ): Int {
        if (burntEdges <= 0f) return source
        val a = (source ushr 24) / 255f
        val darken = 1f - burntEdges * BURNT_DEPTH * 4f * a * (1f - a)

        fun channel(shift: Int) = (((source shr shift) and 0xFF) * darken + 0.5f).toInt().coerceIn(0, 255)
        return (source and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /**
     * Applies an eraser stroke: coverage is measured once for the whole stroke, so overlapping
     * dabs inside a single stroke cannot erase more than the stroke's opacity.
     */
    private fun eraseStroke(
        target: PixelBuffer,
        stroke: Stroke,
        params: BrushParams,
        points: List<StrokePoint>,
        strokeAlpha: Float,
        mask: SelectionMask?,
        random: Random,
    ) {
        val reach = StrokeReach.bounds(points, params, target.width, target.height)
        if (reach.isEmpty) return
        val buffer = scratchPool.obtain(target.width, target.height, clear = false)
        var dual: DualMarks? = null
        try {
            clear(buffer, reach)
            // Erasing depends on brush coverage, never on the selected ink color's alpha.
            val eraseShape = stroke.copy(color = 0xFFFFFFFF.toInt())
            drawStrokeInto(buffer, eraseShape, params, points, alphaLock = false, mask = null, random = random)
            dual = params.dual?.let { drawDual(target, eraseShape, it, reach) }
            val how =
                Deposit(
                    params,
                    strokeAlpha,
                    mask,
                    alphaLock = false,
                    grain = grainOrigin(params, points),
                    strokePressure = grainPressure(params, points),
                    grainRandom = random,
                )
            applyEraseCoverage(target, buffer, reach, how, dual)
            lastDabCount = dabCount.get()
        } finally {
            dual?.let { scratchPool.release(it.marks) }
            scratchPool.release(buffer)
        }
    }

    private fun applyEraseCoverage(
        target: PixelBuffer,
        buffer: PixelBuffer,
        reach: IntBounds,
        how: Deposit,
        dual: DualMarks?,
    ) {
        val texture = how.texture
        val grainX = how.grain.first
        val grainY = how.grain.second
        val targetPixels = target.pixels
        val bufferPixels = buffer.pixels
        val targetWidth = target.width
        val mask = how.mask
        val maskMatches = mask != null && mask.width == targetWidth && mask.height == target.height
        for (y in reach.top..reach.bottom) {
            val row = y * targetWidth
            val canvasY = originY + y
            for (x in reach.left..reach.right) {
                val i = row + x
                val canvasX = originX + x
                val source = dual?.combine(bufferPixels[i], i, canvasX, canvasY) ?: bufferPixels[i]
                val sourceAlpha = source ushr 24
                val destination = targetPixels[i]
                val destinationAlpha = destination ushr 24
                if (sourceAlpha == 0 || destinationAlpha == 0) continue
                val selectionCoverage =
                    when {
                        mask == null -> 1f
                        maskMatches -> mask.alphaAt(i)
                        else -> mask.coverageAt(x, y) / 255f
                    }
                if (selectionCoverage <= 0f) continue
                val sourceCoverage = sourceAlpha / 255f
                val grain = texture?.coverage(canvasX - grainX, canvasY - grainY) ?: 1f
                val erase = (how.strokeAlpha * sourceCoverage * selectionCoverage * grainFactor(source, grain, how.params)).coerceIn(0f, 1f)
                val outAlpha = (destinationAlpha * (1f - erase)).roundToInt().coerceIn(0, 255)
                targetPixels[i] = (outAlpha shl 24) or (destination and 0x00FFFFFF)
            }
        }
    }

    private fun drawStrokeInto(
        target: PixelBuffer,
        stroke: Stroke,
        params: BrushParams,
        points: List<StrokePoint>,
        alphaLock: Boolean,
        mask: SelectionMask?,
        random: Random,
        canvas: PixelBuffer? = null,
        physicalPlane: PhysicalPaintPlane? = null,
        physicalCoverage: ((Int, Int) -> Float)? = null,
        rasterize: Boolean = true,
    ) {
        if (canUseCapsule(params, points) && physicalPlane == null) {
            drawSegment(target, stroke, params, points.first(), points.last(), alphaLock, mask, random)
            return
        }

        // Total path length, needed by the taper maths; zero cost for untapered brushes.
        val totalLength =
            if (params.taperStart > 0f || params.taperEnd > 0f) stroke.calculateLength() else 0f

        val context =
            DabContext(
                target,
                stroke,
                params,
                totalLength,
                alphaLock,
                mask,
                random,
                tipFor(params, random),
                canvas,
                physicalPlane,
                physicalCoverage,
                rasterize,
            )
        val walk = DabWalk()
        walkSegments(context, points, 1, walk)
        stampEnd(context, points, walk)
    }

    /** The dab outline; a randomized tip draws its angle first, as every stroke starts. */
    private fun tipFor(
        params: BrushParams,
        random: Random,
    ): Stamping.TipShape {
        val shape = BrushSourceLibrary.resolveShape(params.shapeId)
        // Randomized starts each stroke at its own angle; the stroke's seeded random keeps it repeatable.
        val angle = params.rotation + if (params.tipRandomized) random.nextFloat() * FULL_TURN else 0f
        return Stamping.TipShape(
            params.roundness,
            angle,
            shape?.let { tile ->
                { u, v -> tile.sample(if (params.tipFlipX) 1f - u else u, if (params.tipFlipY) 1f - v else v) }
            },
        )
    }

    /**
     * Speed along a segment, in pixels per millisecond. Samples that share a timestamp, as batched input does, have no
     * measurable time between them, so they keep the speed of the last measured segment. Reading them as a 1 ms gap
     * would make the stroke look infinitely fast and drive the speed dynamics to their limit.
     */
    private fun segmentVelocity(
        previous: StrokePoint,
        current: StrokePoint,
        distance: Float,
        carried: Float,
    ): Float {
        if (distance <= 0f) return 0f
        val elapsedMs = current.timestamp - previous.timestamp
        return if (elapsedMs > 0L) distance / elapsedMs.toFloat() else carried
    }

    /** Where the spacing grid stands after the segments stamped so far. */
    internal class DabWalk {
        var carry = 0f
        var accumulated = 0f

        // Speed of the last segment that had elapsed time; a segment without any keeps it.
        var velocity = 0f

        // Distance along the entire path of the most recent dab. A short segment can have no dabs,
        // so a segment-local position cannot tell whether the spacing grid reached the path end.
        var lastDabDistance = Float.NaN

        // Wet paint the brush carries from dab to dab when pull is on; null before the first dab.
        var carried: Int? = null
    }

    /** Stamps the spacing grid along the segments ending at points [from] and later. */
    private fun walkSegments(
        context: DabContext,
        points: List<StrokePoint>,
        from: Int,
        walk: DabWalk,
    ) {
        for (i in from until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            val dx = current.x - previous.x
            val dy = current.y - previous.y
            val distance = sqrt(dx * dx + dy * dy)

            if (distance <= 0.0001f) {
                // Duplicate sample: a single dab keeps a tap visible without double-darkening.
                drawDabAt(context, walk, previous, current, 0f, distance, walk.accumulated, walk.velocity)
                continue
            }

            val velocity = segmentVelocity(previous, current, distance, walk.velocity)
            walk.velocity = velocity
            var travelled = walk.carry
            while (travelled <= distance) {
                drawDabAt(context, walk, previous, current, travelled / distance, distance, walk.accumulated + travelled, velocity)
                walk.lastDabDistance = walk.accumulated + travelled
                travelled += dabSpacing(context, previous, current, travelled / distance, walk.accumulated + travelled, velocity)
            }
            walk.carry = travelled - distance
            walk.accumulated += distance
        }
    }

    /**
     * The distance to the next dab: a fraction of the diameter the dab here will have. Spacing from the nominal size
     * left gaps wherever light pressure or a taper made the dabs smaller, which read as beads along the line. The
     * estimate leaves out size jitter, so it draws no random numbers and the dab sequence stays the same.
     */
    private fun dabSpacing(
        context: DabContext,
        previous: StrokePoint,
        current: StrokePoint,
        t: Float,
        accumulatedDistance: Float,
        velocity: Float,
    ): Float {
        val params = context.params
        val pressure = previous.pressure + (current.pressure - previous.pressure) * t
        val tilt = ((previous.tiltX + (current.tiltX - previous.tiltX) * t) / HALF_PI).coerceIn(0f, 1f)
        val tiltEffect = params.tiltInfluence.coerceIn(0f, 1f) * tilt
        val taper = taperFactor(params, accumulatedDistance, context.totalLength)
        val diameter = params.calculateEffectiveSize(pressure, velocity) * (1f + TILT_SIZE_GAIN * tiltEffect) * taper
        val spacingCurve = params.optionalChannelPressure(pressure, "dynamicsPressurePlotSpacingJitter")
        val spacingScale = if ("dynamicsPressurePlotSpacingJitter" in params.importedAuxiliaryCurves) (0.25f + 0.75f * spacingCurve) else 1f
        // A minimum of one pixel keeps single-point taps visible and stops a fully tapered end from stalling.
        return max(1f, diameter * params.spacing.coerceIn(0.01f, 4f) * spacingScale)
    }

    /**
     * Finishes exactly at the stroke end once, after the whole path — not once per segment, and not
     * at all when the final segment's spacing grid already stamped t = 1. A lone point is a tap.
     */
    private fun stampEnd(
        context: DabContext,
        points: List<StrokePoint>,
        walk: DabWalk,
    ) {
        if (points.size == 1) {
            drawDabAt(context, walk, points.first(), points.first(), 0f, 0f, 0f, 0f)
            return
        }
        val lastPrevious = points[points.size - 2]
        val last = points.last()
        val endDx = last.x - lastPrevious.x
        val endDy = last.y - lastPrevious.y
        val endDistance = sqrt(endDx * endDx + endDy * endDy)
        if (endDistance > 0.0001f && walk.lastDabDistance != walk.accumulated) {
            drawDabAt(context, walk, lastPrevious, last, 1f, endDistance, walk.accumulated, walk.velocity)
        }
    }

    private data class DabContext(
        val target: PixelBuffer,
        val stroke: Stroke,
        val params: BrushParams,
        val totalLength: Float,
        val alphaLock: Boolean,
        val mask: SelectionMask?,
        val random: Random,
        val tip: Stamping.TipShape,
        /** The layer being painted, sampled by wet mix; null when there is nothing to pick up. */
        val canvas: PixelBuffer?,
        /** Optional sparse physical paint plane for watercolor wetness & 3D impasto ridges. */
        val physicalPlane: PhysicalPaintPlane? = null,
        /** Visible stamp coverage; kept separate from scratch-buffer stamping restrictions. */
        val physicalCoverage: ((Int, Int) -> Float)? = null,
        /** False advances only dab geometry and random state before resolving physical grain. */
        val rasterize: Boolean = true,
    )

    private fun drawDabAt(
        context: DabContext,
        walk: DabWalk,
        previous: StrokePoint,
        current: StrokePoint,
        t: Float,
        distance: Float,
        accumulatedDistance: Float,
        velocity: Float,
    ) {
        val target = context.target
        val stroke = context.stroke
        val params = context.params
        val totalLength = context.totalLength
        val alphaLock = context.alphaLock
        val mask = context.mask
        val random = context.random
        val x = previous.x + (current.x - previous.x) * t
        val y = previous.y + (current.y - previous.y) * t
        val pressure = previous.pressure + (current.pressure - previous.pressure) * t

        // Stylus tilt: 0 upright, 1 lying flat. Shading with the side of the pen widens and softens the mark.
        // Size and opacity have separate amounts; opacity follows the size amount unless it was set apart.
        val tilt = ((previous.tiltX + (current.tiltX - previous.tiltX) * t) / HALF_PI).coerceIn(0f, 1f)
        val sizeTilt = params.tiltInfluence.coerceIn(0f, 1f) * tilt
        val opacityTilt = (params.tiltOpacityInfluence ?: params.tiltInfluence).coerceIn(0f, 1f) * tilt
        val size = params.calculateEffectiveSize(pressure, velocity, random) * (1f + TILT_SIZE_GAIN * sizeTilt)
        val baseOpacity = params.calculateEffectiveOpacity(pressure, velocity, random) * params.flowAt(pressure)
        val opacity = baseOpacity * (1f - TILT_OPACITY_LOSS * opacityTilt)

        // Tapering thins the stroke over the first and last portion of its length.
        val taper = taperFactor(params, accumulatedDistance, totalLength)
        val radius = max(0.35f, size * taper / 2f)
        // Taper opacity fades the tapered ends as well, and fall off fades the stroke along its path.
        val plainOpacity =
            opacity * (1f - params.taperOpacity.coerceIn(0f, 1f) * (1f - taper)) * falloffFactor(params, accumulatedDistance) *
                (1f - DILUTION_THINNING * params.dilution.coerceIn(0f, 1f))
        val rendering = params.rendering
        val dabOpacity = renderingOpacity(plainOpacity, params)

        // Pull keeps some of the paint the brush already carries instead of reloading the fresh colour.
        // Per-stroke jitter draws from a sequence that restarts at every dab, so each dab gets the same colour.
        val colorRandom = if (params.colorJitterPerStroke) Random(strokeColorSeed(stroke.id)) else random
        val fresh = params.applyColorJitter(stroke.color, pressure, velocity, colorRandom, stroke.secondaryColor, tilt)
        val loaded = walk.carried?.let { carried -> carriedColor(fresh, carried, params) } ?: fresh
        val color =
            if (params.picksUpCanvas || params.dilution > 0f) {
                val wetSample =
                    WetBrushPickup.Sample(
                        x,
                        y,
                        if (distance > 0f) (current.x - previous.x) / distance else 0f,
                        if (distance > 0f) (current.y - previous.y) / distance else 0f,
                        accumulatedDistance,
                        pressure,
                    )
                WetBrushPickup.color(params, context.canvas, loaded, wetSample, random)
            } else {
                loaded
            }
        walk.carried = color

        // Scatter offsets each dab; count repeats it along a random perpendicular offset.
        val countCurve = params.optionalChannelPressure(pressure, "dynamicsPressureStampCountCurve")
        val most = (params.count.coerceIn(1, 32) * countCurve).roundToInt().coerceIn(1, 32)
        val jitterCurve = params.optionalChannelPressure(pressure, "dynamicsPressureStampCountJitterCurve")
        val jitter = (params.countJitter.coerceIn(0f, 1f) * jitterCurve).coerceIn(0f, 1f)
        // Include both endpoints: a count of 10 at 50% jitter can stamp 5 through 10 times.
        val removable = (jitter * most).toInt().coerceAtMost(most - 1)
        val dabs = if (jitter > 0f && most > 1) most - (random.nextFloat() * (removable + 1)).toInt() else most
        val azimuth = if (params.tiltToRotation) interpolatedAzimuth(previous.tiltY, current.tiltY, t) else 0f
        val dirX = if (distance > 0f) (current.x - previous.x) / distance else 0f
        val dirY = if (distance > 0f) (current.y - previous.y) / distance else 0f
        val perpX = -dirY
        val perpY = dirX
        val plotJitterCurve = params.optionalChannelPressure(pressure, "dynamicsPressurePlotJitterCurve")
        val longJitterCurve = params.optionalChannelPressure(pressure, "dynamicsPressurePlotJitterLongitudinalCurve")
        val hasLongitudinalCurve = "dynamicsPressurePlotJitterLongitudinalCurve" in params.importedAuxiliaryCurves
        repeat(dabs) { index ->
            val scatterCurve = params.optionalChannelPressure(pressure, "dynamicsPressureShapeScatterCurve")
            val scatter = params.scatter.coerceIn(0f, 4f) * params.size * scatterCurve * plotJitterCurve
            val jitterX = if (scatter > 0f) ((random.nextFloat() * 2f - 1f) * scatter / 2f) else 0f
            val jitterY = if (scatter > 0f) ((random.nextFloat() * 2f - 1f) * scatter / 2f) else 0f
            val longJitter =
                if (hasLongitudinalCurve && params.scatter > 0f) {
                    (random.nextFloat() * 2f - 1f) * params.scatter.coerceIn(0f, 4f) * params.size * longJitterCurve * 0.5f
                } else {
                    0f
                }
            val offsetScale = if (dabs == 1) 0f else (index / (dabs - 1f) - 0.5f) * params.size * 0.5f
            val offsetX = jitterX + offsetScale * perpX + longJitter * dirX
            val offsetY = jitterY + offsetScale * perpY + longJitter * dirY
            val tip = tipFor(context.tip, params, pressure, azimuth, random)
            if (!context.rasterize) return@repeat

            Stamping.dab(
                target = target,
                x = x + offsetX,
                y = y + offsetY,
                radius = radius,
                color =
                    Channels.withAlpha(
                        color,
                        (Channels.alpha(color) * dabOpacity * rendering.strength).roundToInt().coerceIn(0, 255),
                    ),
                strength = 1f,
                // Flow controls deposited coverage. Glazing caps it within a stroke; blending builds it up.
                hardness = hardnessFor(params),
                mode = if (rendering.blending) Stamping.Mode.SOURCE_OVER else Stamping.Mode.MAX_COVERAGE,
                alphaLock = alphaLock,
                mask = mask,
                tip = tip,
            )
            context.physicalPlane?.let { plane ->
                if (params.waterAmount > 0f) {
                    PhysicalPaintEngine.depositWetness(
                        plane = plane,
                        centerX = x + offsetX,
                        centerY = y + offsetY,
                        radius = radius,
                        waterAmount = params.waterAmount,
                        // The coverage callback already includes the stamp's flow and alpha.
                        pigmentLoad = 1f,
                        coverage = context.physicalCoverage,
                    )
                }
                if (params.impastoThickness > 0f) {
                    PhysicalPaintEngine.depositImpasto(
                        plane = plane,
                        centerX = x + offsetX,
                        centerY = y + offsetY,
                        radius = radius,
                        thickness = params.impastoThickness * pressure.coerceIn(0.2f, 1f),
                        dirX = dirX,
                        dirY = dirY,
                        coverage = context.physicalCoverage,
                    )
                }
            }
            dabCount.incrementAndGet()
        }
    }

    /** With tilt-to-rotation, flat and image tips turn with the pen's direction (reported in radians). */
    private fun tipFor(
        tip: Stamping.TipShape,
        params: BrushParams,
        pressure: Float,
        azimuth: Float,
        random: Random,
    ): Stamping.TipShape {
        var effectiveRoundness = tip.roundness
        if ("dynamicsPressureShapeRoundnessCurve" in params.importedAuxiliaryCurves) {
            val roundCurve = params.optionalChannelPressure(pressure, "dynamicsPressureShapeRoundnessCurve")
            effectiveRoundness = (effectiveRoundness * roundCurve).coerceIn(0.05f, 1f)
        }
        val hasH = "dynamicsPressureShapeRoundessHorizontalJitterCurve" in params.importedAuxiliaryCurves
        val hasV = "dynamicsPressureShapeRoundessVerticalJitterCurve" in params.importedAuxiliaryCurves
        if (hasH || hasV) {
            val hScale = if (hasH) params.optionalChannelPressure(pressure, "dynamicsPressureShapeRoundessHorizontalJitterCurve") else 0f
            val vScale = if (hasV) params.optionalChannelPressure(pressure, "dynamicsPressureShapeRoundessVerticalJitterCurve") else 0f
            val delta = ((random.nextFloat() * 2f - 1f) * max(hScale, vScale) * 0.45f)
            effectiveRoundness = (effectiveRoundness + delta).coerceIn(0.05f, 1f)
        }
        val shaped = if (effectiveRoundness != tip.roundness) tip.copy(roundness = effectiveRoundness) else tip
        val turned =
            if (params.tiltToRotation) {
                shaped.copy(angleDegrees = shaped.angleDegrees + Math.toDegrees(azimuth.toDouble()).toFloat())
            } else {
                shaped
            }
        val flipX = params.tipFlipXJitter && random.nextBoolean()
        val flipY = params.tipFlipYJitter && random.nextBoolean()
        val image = turned.image
        if (image == null || (!flipX && !flipY)) return turned
        return turned.copy(image = { u, v -> image(if (flipX) 1f - u else u, if (flipY) 1f - v else v) })
    }

    /** Follow the shortest turn between input samples, including a pen crossing the angle boundary. */
    private fun interpolatedAzimuth(
        start: Float,
        end: Float,
        fraction: Float,
    ): Float {
        var turn = (end - start) % FULL_TURN_RADIANS
        if (turn > Math.PI.toFloat()) turn -= FULL_TURN_RADIANS
        if (turn < -Math.PI.toFloat()) turn += FULL_TURN_RADIANS
        return start + turn * fraction
    }

    /** The fresh colour mixed toward the paint still on the brush; alpha stays the fresh colour's. */
    private fun carriedColor(
        fresh: Int,
        carried: Int,
        params: BrushParams,
    ): Int {
        // Pull is what the brush still carries after travelling one brush width; dabs come every spacing.
        val pull = params.pull.coerceIn(0f, 1f)
        if (pull <= 0f) return fresh
        val amount = pull.toDouble().pow(params.spacing.coerceIn(MIN_PULL_STEP, 1f).toDouble()).toFloat()
        val mixed =
            if (params.spectralMixing) {
                com.artflow.studio.core.color.SpectralMixer
                    .mixArgb(fresh or 0xFF000000.toInt(), carried or 0xFF000000.toInt(), amount)
            } else {
                ImageFilters.lerpArgb(fresh or 0xFF000000.toInt(), carried or 0xFF000000.toInt(), amount)
            }
        return (fresh and 0xFF000000.toInt()) or (mixed and 0x00FFFFFF)
    }

    /**
     * What the grain leaves of the coverage in [source]'s alpha, as a factor on that alpha, so
     * Multiply is exactly the grain value and other grain blends reshape the coverage first.
     */
    private fun grainFactor(
        source: Int,
        grain: Float,
        params: BrushParams,
    ): Float {
        // Without grain (none, or depth 0) there is nothing to blend, whatever the mode.
        if (params.grainBlend == GrainBlend.MULTIPLY || grain >= 1f) return grain
        val coverage = (source ushr 24) / 255f
        return if (coverage <= 0f) 0f else params.grainBlend.combine(coverage, grain) / coverage
    }

    private fun canUseCapsule(
        params: BrushParams,
        points: List<StrokePoint>,
    ): Boolean =
        params.spacing <= 0f &&
            !params.rendering.blending &&
            params.roundness >= 1f &&
            !params.picksUpCanvas &&
            params.dilution <= 0f &&
            params.pull <= 0f &&
            params.tiltInfluence <= 0f &&
            (params.tiltOpacityInfluence ?: 0f) <= 0f &&
            BrushSourceLibrary.resolveShape(params.shapeId) == null &&
            "dynamicsPressureShapeRoundnessCurve" !in params.importedAuxiliaryCurves &&
            "dynamicsPressureShapeRoundessHorizontalJitterCurve" !in params.importedAuxiliaryCurves &&
            "dynamicsPressureShapeRoundessVerticalJitterCurve" !in params.importedAuxiliaryCurves &&
            points.size <= 2 &&
            params.count == 1 &&
            points.first().pressure == points.last().pressure &&
            listOf(
                params.scatter,
                params.falloff,
                params.taperStart,
                params.taperEnd,
                params.sizeJitter,
                params.opacityJitter,
                params.hueJitter,
                params.saturationJitter,
                params.brightnessJitter,
                params.effectiveDarknessJitter,
                params.velocityToSize,
                params.velocityToOpacity,
                params.velocityToHue,
                params.secondaryPressure,
                params.secondaryJitter,
                params.pressureToFlow,
                params.colorDynamics.tiltHue,
                params.colorDynamics.tiltSaturation,
                params.colorDynamics.tiltBrightness,
            ).all { it == 0f }

    private fun drawSegment(
        target: PixelBuffer,
        stroke: Stroke,
        params: BrushParams,
        start: StrokePoint,
        end: StrokePoint,
        alphaLock: Boolean,
        mask: SelectionMask?,
        random: Random,
    ) {
        val startSize = params.calculateEffectiveSize(start.pressure, random = random)
        val endSize = params.calculateEffectiveSize(end.pressure, random = random)
        val startAlpha = params.calculateEffectiveOpacity(start.pressure, random = random) * params.flowAt(start.pressure)
        val endAlpha = params.calculateEffectiveOpacity(end.pressure, random = random) * params.flowAt(end.pressure)
        val averageAlpha = (startAlpha + endAlpha) / 2f
        val strength = params.rendering.strength
        val color =
            params.applyColorJitter(
                stroke.color,
                (start.pressure + end.pressure) / 2f,
                random = random,
                secondary = stroke.secondaryColor,
            )

        if (start.x == end.x && start.y == end.y) {
            Stamping.dab(
                target = target,
                x = start.x,
                y = start.y,
                radius = max(0.35f, startSize / 2f),
                color =
                    Channels.withAlpha(
                        color,
                        (Channels.alpha(color) * renderingOpacity(startAlpha, params) * strength).roundToInt().coerceIn(0, 255),
                    ),
                hardness = hardnessFor(params),
                mode = Stamping.Mode.MAX_COVERAGE,
                alphaLock = alphaLock,
                mask = mask,
            )
            dabCount.incrementAndGet()
            return
        }

        Stamping.capsule(
            target = target,
            x0 = start.x,
            y0 = start.y,
            x1 = end.x,
            y1 = end.y,
            radiusStart = max(0.35f, startSize / 2f),
            radiusEnd = max(0.35f, endSize / 2f),
            color =
                Channels.withAlpha(
                    color,
                    (Channels.alpha(color) * renderingOpacity(averageAlpha, params) * strength).roundToInt().coerceIn(0, 255),
                ),
            hardness = hardnessFor(params),
            mode = Stamping.Mode.MAX_COVERAGE,
            alphaLock = alphaLock,
            mask = mask,
        )
        dabCount.incrementAndGet()
    }

    /** Intense modes lay down more paint; both a dab and a zero-spacing capsule use the same transfer. */
    private fun renderingOpacity(
        opacity: Float,
        params: BrushParams,
    ): Float = if (params.rendering.intense) 1f - (1f - opacity).let { it * it } else opacity

    /** Uniform edges stay hard at low flow; other modes soften as their paint flow decreases. */
    private fun hardnessFor(params: BrushParams): Float {
        if (params.rendering.uniformEdges) return 1f
        val flow = params.flow.coerceIn(0f, 1f)
        return (0.45f + 0.55f * flow).coerceIn(0.1f, 1f)
    }

    /**
     * Multiplier applied to the brush size [accumulatedDistance] pixels into the stroke to create
     * start/end tapers. The running distance is threaded through the dab loop, so the factor is
     * O(1) per dab instead of rescanning the point list (which was O(N) per dab, O(N²) per stroke).
     */
    private fun taperFactor(
        params: BrushParams,
        accumulatedDistance: Float,
        totalLength: Float,
    ): Float {
        if (params.taperStart <= 0f && params.taperEnd <= 0f) return 1f
        if (totalLength <= 1f) return 1f

        val startRamp = params.taperStart.coerceIn(0f, 1f) * totalLength
        val endRamp = params.taperEnd.coerceIn(0f, 1f) * totalLength

        val travelled = accumulatedDistance.coerceIn(0f, totalLength)
        val startFactor = if (startRamp <= 0f) 1f else (travelled / startRamp).coerceIn(0.05f, 1f)
        val remaining = totalLength - travelled
        val endFactor = if (endRamp <= 0f) 1f else (remaining / endRamp).coerceIn(0.05f, 1f)
        val factor = min(startFactor, endFactor)
        val sharpness = params.tipSharpness.coerceIn(0f, 1f)
        // A sharper tip bends the straight ramp into a curve that stays thin near the end.
        return if (sharpness > 0f) factor.pow(1f + SHARPNESS_CURVE * sharpness) else factor
    }

    /** Moving grain is anchored where the stroke starts; texturized grain stays fixed to the canvas. */
    private fun grainOrigin(
        params: BrushParams,
        points: List<StrokePoint>,
        canvasCoordinates: Boolean = false,
    ): Pair<Int, Int> {
        val movementCurve =
            if ("dynamicsPressureGrainMovementCurve" in params.importedAuxiliaryCurves) {
                params.optionalChannelPressure(grainPressure(params, points), "dynamicsPressureGrainMovementCurve")
            } else {
                1f
            }
        if (!params.grainMoving && movementCurve == 1f) return 0 to 0
        val offsetX = if (canvasCoordinates) 0 else originX
        val offsetY = if (canvasCoordinates) 0 else originY
        val baseX = points.first().x + offsetX
        val baseY = points.first().y + offsetY
        val factor = if (params.grainMoving) movementCurve else (1f - movementCurve)
        return floor(baseX * factor).toInt() to floor(baseY * factor).toInt()
    }

    /** Mean pressure of [points], evaluated only when grain pressure curves are present. */
    private fun grainPressure(
        params: BrushParams,
        points: List<StrokePoint>,
    ): Float {
        if (!BrushTexture.hasPressureCurves(params) || points.isEmpty()) return 1f
        var sum = 0f
        for (point in points) sum += point.pressure
        return (sum / points.size).coerceIn(0f, 1f)
    }

    /** Fall off: the stroke fades to nothing over one brush size at 1, and over 40 sizes near 0. */
    private fun falloffFactor(
        params: BrushParams,
        travelled: Float,
    ): Float {
        val falloff = params.falloff.coerceIn(0f, 1f)
        if (falloff <= 0f) return 1f
        val length = max(1f, params.size * (1f + FALLOFF_RANGE * (1f - falloff)))
        return (1f - travelled / length).coerceIn(0f, 1f)
    }

    /** The user-selected stroke opacity; pressure is evaluated per dab, never averaged. */
    fun strokeAlpha(stroke: Stroke): Float = stroke.brushParams.opacity.coerceIn(0f, 1f)

    /**
     * Clears only [area] of the shared scratch buffer. Pixels outside it may hold an earlier
     * stroke's coverage, but a stroke reads its scratch only inside its own reach, which it clears first.
     */
    private fun clear(
        buffer: PixelBuffer,
        area: IntBounds,
    ) {
        if (area.isEmpty) return
        for (y in area.top..area.bottom) {
            val start = y * buffer.width
            buffer.pixels.fill(0, start + area.left, start + area.right + 1)
        }
    }

    /** Draws a dual brush's second brush along the same path, with its own seed, size and grain. */
    private fun drawDual(
        target: PixelBuffer,
        stroke: Stroke,
        dual: DualBrush,
        reach: IntBounds,
    ): DualMarks {
        val params = dual.params.copy(opacity = 1f, dual = null, wetMix = 0f)
        val marks = scratchPool.obtain(target.width, target.height, clear = false)
        var completed = false
        try {
            // [reach] already covers the second brush; marks are read only inside it.
            clear(marks, reach)
            val random = Random(stroke.id + DUAL_SEED)
            drawStrokeInto(marks, stroke, params, stroke.points, alphaLock = false, mask = null, random = random)
            val result =
                DualMarks(
                    dual.mode,
                    marks,
                    BrushTexture.from(params, grainPressure(params, stroke.points), random),
                    dual.params.opacity,
                    grainOrigin(params, stroke.points),
                    params.grainBlend,
                )
            completed = true
            return result
        } finally {
            if (!completed) scratchPool.release(marks)
        }
    }

    /** The second brush's marks, combined with the main brush's coverage pixel by pixel. */
    private class DualMarks(
        val mode: DualBrush.Mode,
        val marks: PixelBuffer,
        val texture: BrushTexture?,
        val opacity: Float,
        val grain: Pair<Int, Int>,
        val grainBlend: GrainBlend,
    ) {
        fun combine(
            primary: Int,
            index: Int,
            x: Int,
            y: Int,
        ): Int {
            val second = marks.pixels[index]
            val own = (primary ushr 24) / 255f
            val coverage = (second ushr 24) / 255f
            val grainCoverage = texture?.coverage(x - grain.first, y - grain.second) ?: 1f
            val other =
                if (grainBlend == GrainBlend.MULTIPLY || grainCoverage >= 1f) {
                    // Preserve the historical floating-point order for the default blend.
                    coverage * opacity.coerceIn(0f, 1f) * grainCoverage
                } else {
                    // The grain changes shape coverage before the second brush's global opacity.
                    grainBlend.combine(coverage, grainCoverage) * opacity.coerceIn(0f, 1f)
                }
            val alpha =
                when (mode) {
                    DualBrush.Mode.MULTIPLY -> own * other
                    DualBrush.Mode.SUBTRACT -> own * (1f - other)
                    DualBrush.Mode.ADD -> own + other - own * other
                    DualBrush.Mode.COMBINE -> own * (1f - other) + other
                    DualBrush.Mode.INTENSE_BLEND -> if (other < 0.5f) 2f * own * other else 1f - 2f * (1f - own) * (1f - other)
                    DualBrush.Mode.DISPLACE -> (own * (0.5f + 0.5f * other)).coerceIn(0f, 1f)
                }
            val colour = if (own > 0f) primary else second
            return Channels.withAlpha(colour, (alpha * 255f).roundToInt().coerceIn(0, 255))
        }
    }

    /** Releases all pooled scratch buffers across all threads (called on dispose or memory trim). */
    fun release() {
        scratchPool.clear()
    }

    companion object {
        private const val SCRATCH_POOL_CAPACITY = 2
        private const val SCRATCH_POOL_MAX_BYTES = 16L * 1024L * 1024L

        /**
         * Whether [stroke] can be drawn live: dabs that never change once stamped. Taper depends on
         * the finished length, a second brush and the eraser keep their own buffers, and a zero
         * spacing straight line is drawn as one capsule.
         */
        fun canDrawLive(stroke: Stroke): Boolean {
            val params = stroke.brushParams
            return !stroke.isEraser && params.dual == null && params.taperStart <= 0f && params.taperEnd <= 0f && params.spacing > 0f
        }
    }

    /**
     * Convenience for tools and tests: rasterise a whole stroke list into a fresh buffer.
     */
    fun rasterize(
        strokes: List<Stroke>,
        width: Int,
        height: Int,
        alphaLock: Boolean = false,
        mask: SelectionMask? = null,
    ): PixelBuffer {
        val buffer = PixelBuffer(max(1, width), max(1, height))
        strokes.forEach { draw(buffer, it, alphaLock, mask) }
        return buffer
    }

    /** Blends [source] into [target] using the given opacity (kept for mask/overlay helpers). */
    fun blendWithOpacity(
        target: PixelBuffer,
        source: PixelBuffer,
        opacity: Float,
    ) {
        val clamped = opacity.coerceIn(0f, 1f)
        if (clamped <= 0f) return
        for (i in target.pixels.indices) {
            target.pixels[i] = ImageFilters.lerpArgb(target.pixels[i], source.pixels[i], clamped)
        }
    }
}

/** Offsets a dual brush's random seed so its jitter differs from the main brush's. */
private const val DUAL_SEED = 7919L

/** How dark a burnt rim gets at full Burnt Edges, as a share of the colour. */
private const val BURNT_DEPTH = 0.6f

/** Fully diluted paint keeps a quarter of its opacity. */
private const val DILUTION_THINNING = 0.75f

/** Spacing below this still counts as this step, so very dense brushes keep some pull. */
private const val MIN_PULL_STEP = 0.01f

private const val HALF_PI = (Math.PI / 2).toFloat()

/** A fully tilted pen paints up to this much wider ... */
private const val TILT_SIZE_GAIN = 2f

/** ... and this much lighter, like shading with the side of a pencil. */
private const val TILT_OPACITY_LOSS = 0.6f

/** Fall off at its gentlest spreads the fade over this many extra brush sizes. */
private const val FALLOFF_RANGE = 39f

/** Degrees in a full turn, for a randomized tip angle. */
private const val FULL_TURN = 360f

private const val FULL_TURN_RADIANS = (Math.PI * 2).toFloat()

/** Tip sharpness at full raises the taper ramp to this extra power. */
private const val SHARPNESS_CURVE = 3f

/**
 * A well-mixed seed for a stroke's own colour sequence (SplitMix64's finaliser). Stroke ids count
 * up by one, and java.util.Random gives nearly the same first value for neighbouring seeds.
 */
internal fun strokeColorSeed(id: Long): Long {
    var z = id + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}
