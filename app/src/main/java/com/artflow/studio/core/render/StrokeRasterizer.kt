package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.Stamping
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
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
     * Scratch buffer reused across strokes (transparent; allocated on demand).
     *
     * The rasteriser runs on the GL thread while the repository composites on worker dispatchers,
     * so the scratch must be per-thread: a single shared field once handed one thread a buffer
     * another thread had already cleared or sized for a different canvas.
     */
    private val scratch: ThreadLocal<PixelBuffer?> = ThreadLocal.withInitial { null }

    /** Second scratch for a dual brush's other brush, also per thread. */
    private val dualScratch: ThreadLocal<PixelBuffer?> = ThreadLocal.withInitial { null }

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

        val buffer = scratchFor(scratch, target.width, target.height)
        val reach = StrokeReach.bounds(points, params, target.width, target.height)
        clear(buffer, reach)
        drawStrokeInto(buffer, stroke, params, points, alphaLock = false, mask = null, random = random, canvas = target)
        val dual = params.dual?.let { drawDual(target, stroke, it, reach) }
        depositCoverage(target, buffer, reach, Deposit(params, strokeAlpha, mask, alphaLock, grainOrigin(params, points)), dual)
        lastDabCount = dabCount.get()
    }

    /** How a stroke's coverage becomes paint on its layer. */
    private class Deposit(
        val params: BrushParams,
        val strokeAlpha: Float,
        val mask: SelectionMask?,
        val alphaLock: Boolean,
        val grain: Pair<Int, Int>,
    ) {
        val texture = BrushTexture.from(params)
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
    ) {
        val grainX = how.grain.first
        val grainY = how.grain.second
        for (y in reach.top..reach.bottom) {
            for (i in y * target.width + reach.left..y * target.width + reach.right) {
                val source = dual?.combine(buffer.pixels[i], i, originX + i % target.width, originY + i / target.width) ?: buffer.pixels[i]
                if ((source ushr 24) == 0) continue
                val coverage = selectionCoverage(how.mask, target, i)
                val grain = how.texture?.coverage(originX + i % target.width - grainX, originY + i / target.width - grainY) ?: 1f
                val effective = how.strokeAlpha * coverage * grain * wetEdgeFactor(source, how.wetEdges)
                if (effective <= 0f) continue
                val paint = Channels.scaleAlpha(burnt(source, how.burntEdges), effective)
                target.pixels[i] = deposit(target.pixels[i], paint, how.params, how.alphaLock)
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
        // with a copy of the random, and the pixels it covered are put back afterwards.
        val end = StrokeReach.bounds(listOf(points.last()), params, coverage.width, coverage.height)
        // A closing dab far off the canvas reaches no pixels, and then there is nothing to keep.
        val kept = if (end.isEmpty) null else coverage.crop(end)
        val carried = live.walk.carried
        stampEnd(DabContext(coverage, stroke, params, 0f, false, null, live.random.copy(), tip, canvas), points.takeLast(2), live.walk)
        live.walk.carried = carried
        val reach = IntBounds(left - originX, top - originY, right - originX, bottom - originY)
        val buffer = scratchFor(scratch, target.width, target.height)
        for (y in top..bottom) {
            System.arraycopy(
                coverage.pixels,
                y * coverage.width + left,
                buffer.pixels,
                (y - originY) * target.width + reach.left,
                reach.width,
            )
        }
        if (kept != null) {
            for (y in end.top..end.bottom) {
                System.arraycopy(kept.pixels, (y - end.top) * kept.width, coverage.pixels, y * coverage.width + end.left, kept.width)
            }
        }
        depositCoverage(target, buffer, reach, Deposit(params, strokeAlpha, mask, alphaLock, grainOrigin(params, points)))
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
        val buffer = scratchFor(scratch, target.width, target.height)
        val reach = StrokeReach.bounds(points, params, target.width, target.height)
        clear(buffer, reach)
        // Erasing depends on brush coverage, never on the selected ink color's alpha.
        val eraseShape = stroke.copy(color = 0xFFFFFFFF.toInt())
        drawStrokeInto(buffer, eraseShape, params, points, alphaLock = false, mask = null, random = random)
        val texture = BrushTexture.from(params)
        val (grainX, grainY) = grainOrigin(params, points)
        for (y in reach.top..reach.bottom) {
            for (i in y * target.width + reach.left..y * target.width + reach.right) {
                val source = buffer.pixels[i]
                val sourceCoverage = ((source ushr 24) and 0xFF) / 255f
                if (sourceCoverage <= 0f) continue
                val selectionCoverage = selectionCoverage(mask, target, i)
                if (selectionCoverage <= 0f) continue
                val grain = texture?.coverage(originX + i % target.width - grainX, originY + i / target.width - grainY) ?: 1f
                val erase = (strokeAlpha * sourceCoverage * selectionCoverage * grain).coerceIn(0f, 1f)
                val destination = target.pixels[i]
                val destinationAlpha = (destination ushr 24) and 0xFF
                if (destinationAlpha == 0) continue
                val outAlpha = (destinationAlpha * (1f - erase)).roundToInt().coerceIn(0, 255)
                target.pixels[i] =
                    Channels.argb(
                        outAlpha,
                        (destination shr 16) and 0xFF,
                        (destination shr 8) and 0xFF,
                        destination and 0xFF,
                    )
            }
        }
        lastDabCount = dabCount.get()
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
    ) {
        if (canUseCapsule(params, points)) {
            drawSegment(target, stroke, params, points.first(), points.last(), alphaLock, mask, random)
            return
        }

        // Total path length, needed by the taper maths; zero cost for untapered brushes.
        val totalLength =
            if (params.taperStart > 0f || params.taperEnd > 0f) stroke.calculateLength() else 0f

        val context = DabContext(target, stroke, params, totalLength, alphaLock, mask, random, tipFor(params, random), canvas)
        val walk = DabWalk()
        walkSegments(context, points, 1, walk)
        stampEnd(context, points, walk)
    }

    /** The dab outline; a randomized tip draws its angle first, as every stroke starts. */
    private fun tipFor(
        params: BrushParams,
        random: Random,
    ): Stamping.TipShape {
        val shape = CustomGrains.get(params.shapeId)
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

    /** Where the spacing grid stands after the segments stamped so far. */
    internal class DabWalk {
        var carry = 0f
        var accumulated = 0f

        // Travelled value of the most recently stamped dab, used to avoid stamping the path end
        // twice when the spacing grid already lands exactly on it.
        var lastDabTravelled = Float.NaN

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
        // Spacing is expressed as a fraction of the brush size; a minimum of one dab per segment
        // keeps single-point taps visible.
        val spacingPx = max(1f, context.params.size * context.params.spacing.coerceIn(0.01f, 4f))
        for (i in from until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            val dx = current.x - previous.x
            val dy = current.y - previous.y
            val distance = sqrt(dx * dx + dy * dy)

            if (distance <= 0.0001f) {
                // Duplicate sample: a single dab keeps a tap visible without double-darkening.
                drawDabAt(context, walk, previous, current, 0f, distance, walk.accumulated)
                continue
            }

            var travelled = walk.carry
            while (travelled <= distance) {
                drawDabAt(context, walk, previous, current, travelled / distance, distance, walk.accumulated + travelled)
                walk.lastDabTravelled = travelled
                travelled += spacingPx
            }
            walk.carry = travelled - distance
            walk.accumulated += distance
        }
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
            drawDabAt(context, walk, points.first(), points.first(), 0f, 0f, 0f)
            return
        }
        val lastPrevious = points[points.size - 2]
        val last = points.last()
        val endDx = last.x - lastPrevious.x
        val endDy = last.y - lastPrevious.y
        val endDistance = sqrt(endDx * endDx + endDy * endDy)
        if (endDistance > 0.0001f && walk.lastDabTravelled != endDistance) {
            drawDabAt(context, walk, lastPrevious, last, 1f, endDistance, walk.accumulated)
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
    )

    private fun drawDabAt(
        context: DabContext,
        walk: DabWalk,
        previous: StrokePoint,
        current: StrokePoint,
        t: Float,
        distance: Float,
        accumulatedDistance: Float,
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

        // Velocity is derived from the sample spacing and timestamps so the dynamics are stable
        // regardless of how fast the digitiser reports.
        val elapsedMs = (current.timestamp - previous.timestamp).coerceAtLeast(1L).toFloat()
        val velocity = if (distance <= 0f) 0f else distance / elapsedMs

        // Stylus tilt: 0 upright, 1 lying flat. Shading with the side of the pen widens and softens the mark.
        val tilt = ((previous.tiltX + (current.tiltX - previous.tiltX) * t) / HALF_PI).coerceIn(0f, 1f)
        val tiltEffect = params.tiltInfluence.coerceIn(0f, 1f) * tilt
        val size = params.calculateEffectiveSize(pressure, velocity, random) * (1f + TILT_SIZE_GAIN * tiltEffect)
        val baseOpacity = params.calculateEffectiveOpacity(pressure, velocity, random) * params.flow.coerceIn(0f, 1f)
        val opacity = baseOpacity * (1f - TILT_OPACITY_LOSS * tiltEffect)

        // Tapering thins the stroke over the first and last portion of its length.
        val taper = taperFactor(params, accumulatedDistance, totalLength)
        val radius = max(0.35f, size * taper / 2f)
        // Taper opacity fades the tapered ends as well, and fall off fades the stroke along its path.
        val dabOpacity =
            opacity * (1f - params.taperOpacity.coerceIn(0f, 1f) * (1f - taper)) * falloffFactor(params, accumulatedDistance) *
                (1f - DILUTION_THINNING * params.dilution.coerceIn(0f, 1f))

        // Pull keeps some of the paint the brush already carries instead of reloading the fresh colour.
        val fresh = params.applyColorJitter(stroke.color, pressure, velocity, random, stroke.secondaryColor)
        val loaded = walk.carried?.let { carried -> carriedColor(fresh, carried, params) } ?: fresh
        val color = wetColor(loaded, context.canvas, x, y, wetPickup(params))
        walk.carried = color

        // Scatter offsets each dab; count repeats it along a random perpendicular offset.
        val dabs = params.count.coerceIn(1, 32)
        repeat(dabs) { index ->
            val scatter = params.scatter.coerceIn(0f, 4f) * params.size
            val jitterX = if (scatter > 0f) ((random.nextFloat() * 2f - 1f) * scatter / 2f) else 0f
            val jitterY = if (scatter > 0f) ((random.nextFloat() * 2f - 1f) * scatter / 2f) else 0f
            val offsetScale = if (dabs == 1) 0f else (index / (dabs - 1f) - 0.5f) * params.size * 0.5f
            val offsetX = jitterX + offsetScale * (if (distance > 0f) -(current.y - previous.y) / distance else 0f)
            val offsetY = jitterY + offsetScale * (if (distance > 0f) (current.x - previous.x) / distance else 0f)

            Stamping.dab(
                target = target,
                x = x + offsetX,
                y = y + offsetY,
                radius = radius,
                color = Channels.withAlpha(color, (Channels.alpha(color) * dabOpacity).roundToInt().coerceIn(0, 255)),
                strength = 1f,
                // Flow controls deposited coverage. Glazing caps it within a stroke; blending builds it up.
                hardness = hardnessForFlow(params),
                mode = if (params.buildUp) Stamping.Mode.SOURCE_OVER else Stamping.Mode.MAX_COVERAGE,
                alphaLock = alphaLock,
                mask = mask,
                tip = tipFor(context.tip, params, current),
            )
            dabCount.incrementAndGet()
        }
    }

    /** With tilt-to-rotation, flat and image tips turn with the pen's direction (reported in radians). */
    private fun tipFor(
        tip: Stamping.TipShape,
        params: BrushParams,
        point: StrokePoint,
    ): Stamping.TipShape {
        if (!params.tiltToRotation) return tip
        return tip.copy(angleDegrees = tip.angleDegrees + Math.toDegrees(point.tiltY.toDouble()).toFloat())
    }

    /** Wet mix, raised by dilution: watery paint picks up more of the layer. */
    private fun wetPickup(params: BrushParams): Float {
        val wetMix = params.wetMix.coerceIn(0f, 1f)
        return wetMix + (1f - wetMix) * DILUTION_PICKUP * params.dilution.coerceIn(0f, 1f)
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
        val mixed = ImageFilters.lerpArgb(fresh or 0xFF000000.toInt(), carried or 0xFF000000.toInt(), amount)
        return (fresh and 0xFF000000.toInt()) or (mixed and 0x00FFFFFF)
    }

    /** Wet mix: the dab picks up some of the paint already on the layer under it. */
    private fun wetColor(
        color: Int,
        canvas: PixelBuffer?,
        x: Float,
        y: Float,
        wetMix: Float,
    ): Int {
        if (canvas == null || wetMix <= 0f) return color
        val under = canvas.getSafe(floor(x).toInt(), floor(y).toInt())
        val pickup = wetMix.coerceIn(0f, 1f) * WET_PICKUP * ((under ushr 24) / 255f)
        if (pickup <= 0f) return color
        val mixed = ImageFilters.lerpArgb(color or 0xFF000000.toInt(), under or 0xFF000000.toInt(), pickup)
        return (color and 0xFF000000.toInt()) or (mixed and 0x00FFFFFF)
    }

    private fun canUseCapsule(
        params: BrushParams,
        points: List<StrokePoint>,
    ): Boolean =
        params.spacing <= 0f &&
            params.roundness >= 1f &&
            params.wetMix <= 0f &&
            params.dilution <= 0f &&
            params.pull <= 0f &&
            params.tiltInfluence <= 0f &&
            CustomGrains.get(params.shapeId) == null &&
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
                params.velocityToSize,
                params.velocityToOpacity,
                params.velocityToHue,
                params.secondaryPressure,
                params.secondaryJitter,
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
        val startAlpha = params.calculateEffectiveOpacity(start.pressure, random = random) * params.flow.coerceIn(0f, 1f)
        val endAlpha = params.calculateEffectiveOpacity(end.pressure, random = random) * params.flow.coerceIn(0f, 1f)
        val averageAlpha = (startAlpha + endAlpha) / 2f
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
                color = Channels.withAlpha(color, (Channels.alpha(color) * startAlpha).roundToInt().coerceIn(0, 255)),
                hardness = hardnessForFlow(params),
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
            color = Channels.withAlpha(color, (Channels.alpha(color) * averageAlpha).roundToInt().coerceIn(0, 255)),
            hardness = hardnessForFlow(params),
            mode = Stamping.Mode.MAX_COVERAGE,
            alphaLock = alphaLock,
            mask = mask,
        )
        dabCount.incrementAndGet()
    }

    /** Low flow spreads the paint more softly, mirroring how an airbrush behaves. */
    private fun hardnessForFlow(params: BrushParams): Float {
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
        return min(startFactor, endFactor)
    }

    /** Moving grain is anchored where the stroke starts; texturized grain stays fixed to the canvas. */
    private fun grainOrigin(
        params: BrushParams,
        points: List<StrokePoint>,
    ): Pair<Int, Int> = if (params.grainMoving) points.first().x.toInt() to points.first().y.toInt() else 0 to 0

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

    private fun selectionCoverage(
        mask: SelectionMask?,
        target: PixelBuffer,
        index: Int,
    ): Float =
        if (mask == null) {
            1f
        } else if (mask.width == target.width && mask.height == target.height) {
            mask.alphaAt(index)
        } else {
            mask.coverageAt(index % target.width, index / target.width) / 255f
        }

    private fun scratchFor(
        slot: ThreadLocal<PixelBuffer?>,
        width: Int,
        height: Int,
    ): PixelBuffer {
        val existing = slot.get()
        if (existing != null && existing.width == width && existing.height == height) return existing
        val created = PixelBuffer(width, height)
        slot.set(created)
        return created
    }

    /** Draws a dual brush's second brush along the same path, with its own seed, size and grain. */
    private fun drawDual(
        target: PixelBuffer,
        stroke: Stroke,
        dual: DualBrush,
        reach: IntBounds,
    ): DualMarks {
        val params = dual.params.copy(opacity = 1f, dual = null, wetMix = 0f)
        val marks = scratchFor(dualScratch, target.width, target.height)
        // [reach] already covers the second brush; marks are read only inside it.
        clear(marks, reach)
        drawStrokeInto(marks, stroke, params, stroke.points, alphaLock = false, mask = null, random = Random(stroke.id + DUAL_SEED))
        return DualMarks(dual.mode, marks, BrushTexture.from(params))
    }

    /** The second brush's marks, combined with the main brush's coverage pixel by pixel. */
    private class DualMarks(
        val mode: DualBrush.Mode,
        val marks: PixelBuffer,
        val texture: BrushTexture?,
    ) {
        fun combine(
            primary: Int,
            index: Int,
            x: Int,
            y: Int,
        ): Int {
            val second = marks.pixels[index]
            val own = (primary ushr 24) / 255f
            val other = (second ushr 24) / 255f * (texture?.coverage(x, y) ?: 1f)
            val alpha =
                when (mode) {
                    DualBrush.Mode.MULTIPLY -> own * other
                    DualBrush.Mode.SUBTRACT -> own * (1f - other)
                    DualBrush.Mode.ADD -> own + other - own * other
                }
            val colour = if (own > 0f) primary else second
            return Channels.withAlpha(colour, (alpha * 255f).roundToInt().coerceIn(0, 255))
        }
    }

    /** Releases this thread's scratch buffers (called when the canvas is disposed). */
    fun release() {
        scratch.remove()
        dualScratch.remove()
    }

    companion object {
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

/** How much of the paint under a dab a fully wet brush picks up. */
private const val WET_PICKUP = 0.6f

/** Fully diluted paint keeps a quarter of its opacity. */
private const val DILUTION_THINNING = 0.75f

/** Fully diluted paint with no wet mix picks up as much as half wet mix does. */
private const val DILUTION_PICKUP = 0.5f

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
