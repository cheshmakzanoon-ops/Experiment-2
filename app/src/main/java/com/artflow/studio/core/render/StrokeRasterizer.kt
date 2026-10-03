package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.ImageFilters
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
        buffer.clear()
        drawStrokeInto(buffer, stroke, params, points, alphaLock = false, mask = null, random = random, canvas = target)
        val dual = params.dual?.let { drawDual(target, stroke, it) }
        val texture = BrushTexture.from(params)
        val wetEdges = params.wetEdges.coerceIn(0f, 1f)
        for (i in target.pixels.indices) {
            val source = dual?.combine(buffer.pixels[i], i, originX + i % target.width, originY + i / target.width) ?: buffer.pixels[i]
            if ((source ushr 24) == 0) continue
            val coverage = selectionCoverage(mask, target, i)
            val grain = texture?.coverage(originX + i % target.width, originY + i / target.width) ?: 1f
            val effective = strokeAlpha * coverage * grain * wetEdgeFactor(source, wetEdges)
            if (effective <= 0f) continue
            val paint = Channels.scaleAlpha(source, effective)
            target.pixels[i] = deposit(target.pixels[i], paint, params, alphaLock)
        }
        lastDabCount = dabCount.get()
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
        buffer.clear()
        // Erasing depends on brush coverage, never on the selected ink color's alpha.
        val eraseShape = stroke.copy(color = 0xFFFFFFFF.toInt())
        drawStrokeInto(buffer, eraseShape, params, points, alphaLock = false, mask = null, random = random)
        val texture = BrushTexture.from(params)
        for (i in target.pixels.indices) {
            val source = buffer.pixels[i]
            val sourceCoverage = ((source ushr 24) and 0xFF) / 255f
            if (sourceCoverage <= 0f) continue
            val selectionCoverage = selectionCoverage(mask, target, i)
            if (selectionCoverage <= 0f) continue
            val grain = texture?.coverage(originX + i % target.width, originY + i / target.width) ?: 1f
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

        // Spacing is expressed as a fraction of the brush size; a minimum of one dab per segment
        // keeps single-point taps visible.
        val shape = CustomGrains.get(params.shapeId)
        val tip = Stamping.TipShape(params.roundness, params.rotation, shape?.let { tile -> { u, v -> tile.sample(u, v) } })
        val context = DabContext(target, stroke, params, totalLength, alphaLock, mask, random, tip, canvas)
        val spacingPx = max(1f, params.size * params.spacing.coerceIn(0.01f, 4f))
        var carry = 0f
        var accumulatedDistance = 0f
        // Travelled value of the most recently stamped dab, used to avoid stamping the path end
        // twice when the spacing grid already lands exactly on it.
        var lastDabTravelled = Float.NaN

        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            val dx = current.x - previous.x
            val dy = current.y - previous.y
            val distance = sqrt(dx * dx + dy * dy)

            if (distance <= 0.0001f) {
                // Duplicate sample: a single dab keeps a tap visible without double-darkening.
                drawDabAt(
                    context,
                    previous,
                    current,
                    0f,
                    distance,
                    accumulatedDistance,
                )
                continue
            }

            var travelled = carry
            while (travelled <= distance) {
                drawDabAt(
                    context,
                    previous,
                    current,
                    travelled / distance,
                    distance,
                    accumulatedDistance + travelled,
                )
                lastDabTravelled = travelled
                travelled += spacingPx
            }
            carry = travelled - distance
            accumulatedDistance += distance
        }

        if (points.size == 1) {
            drawDabAt(context, points.first(), points.first(), 0f, 0f, 0f)
            return
        }
        // Finish exactly at the stroke end once, after the whole path — not once per segment, and
        // not at all when the final segment's spacing grid already stamped t = 1.
        val lastPrevious = points[points.size - 2]
        val last = points.last()
        val endDx = last.x - lastPrevious.x
        val endDy = last.y - lastPrevious.y
        val endDistance = sqrt(endDx * endDx + endDy * endDy)
        if (endDistance > 0.0001f && lastDabTravelled != endDistance) {
            drawDabAt(
                context,
                lastPrevious,
                last,
                1f,
                endDistance,
                accumulatedDistance,
            )
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
        val dabOpacity = opacity * (1f - params.taperOpacity.coerceIn(0f, 1f) * (1f - taper)) * falloffFactor(params, accumulatedDistance)

        val color = wetColor(params.applyColorJitter(stroke.color, pressure, velocity, random), context.canvas, x, y, params.wetMix)

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
                // Flow controls deposited coverage; repeated strokes can build it up.
                hardness = hardnessForFlow(params),
                mode = Stamping.Mode.MAX_COVERAGE,
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
        val color = params.applyColorJitter(stroke.color, (start.pressure + end.pressure) / 2f, random = random)

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
    ): DualMarks {
        val params = dual.params.copy(opacity = 1f, dual = null, wetMix = 0f)
        val marks = scratchFor(dualScratch, target.width, target.height)
        marks.clear()
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

/** How much of the paint under a dab a fully wet brush picks up. */
private const val WET_PICKUP = 0.6f

private const val HALF_PI = (Math.PI / 2).toFloat()

/** A fully tilted pen paints up to this much wider ... */
private const val TILT_SIZE_GAIN = 2f

/** ... and this much lighter, like shading with the side of a pencil. */
private const val TILT_OPACITY_LOSS = 0.6f

/** Fall off at its gentlest spreads the fade over this many extra brush sizes. */
private const val FALLOFF_RANGE = 39f
