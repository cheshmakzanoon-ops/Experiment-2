package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.Stamping
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * - Colour jitter, spacing, scatter, count and velocity/tilt dynamics are honoured per dab.
 */
class StrokeRasterizer {
    /** Scratch buffer reused across strokes (transparent; allocated on demand). */
    private var scratch: PixelBuffer? = null

    /** Statistics from the last rasterisation, for the performance panel. */
    var lastDabCount: Int = 0
        private set

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

        val params = stroke.brushParams
        val strokeAlpha = strokeAlpha(stroke)
        if (strokeAlpha <= 0f) return

        lastDabCount = 0

        // Erasing is not painting: it removes coverage, so it always goes through the scratch
        // buffer to measure how much each pixel is covered by this one stroke.
        if (stroke.isEraser) {
            eraseStroke(target, stroke, params, points, strokeAlpha, mask)
            return
        }

        // Fully opaque strokes can draw straight into the target; anything else needs the scratch
        // pass so overlapping dabs do not compound the alpha.
        if (strokeAlpha >= 0.999f) {
            drawStrokeInto(target, stroke, params, points, 1f, alphaLock, mask)
        } else {
            val buffer = scratchFor(target.width, target.height)
            buffer.clear()
            drawStrokeInto(buffer, stroke, params, points, 1f, alphaLock = false, mask = null)
            for (i in target.pixels.indices) {
                val source = buffer.pixels[i]
                if ((source ushr 24) == 0) continue
                val coverage = mask?.alphaAt(i) ?: 1f
                val effective = strokeAlpha * coverage
                if (effective <= 0f) continue
                if (alphaLock) {
                    val destinationAlpha = (target.pixels[i] ushr 24) and 0xFF
                    if (destinationAlpha == 0) continue
                    target.pixels[i] =
                        BlendModes.sourceOver(
                            target.pixels[i],
                            Channels.scaleAlpha(source, effective * destinationAlpha / 255f),
                        )
                } else {
                    target.pixels[i] =
                        BlendModes.sourceOver(
                            target.pixels[i],
                            Channels.scaleAlpha(source, effective),
                        )
                }
            }
        }
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
    ) {
        val buffer = scratchFor(target.width, target.height)
        buffer.clear()
        drawStrokeInto(buffer, stroke, params, points, 1f, alphaLock = false, mask = null)
        for (i in target.pixels.indices) {
            val source = buffer.pixels[i]
            val sourceCoverage = ((source ushr 24) and 0xFF) / 255f
            if (sourceCoverage <= 0f) continue
            val selectionCoverage = mask?.alphaAt(i) ?: 1f
            if (selectionCoverage <= 0f) continue
            val erase = (strokeAlpha * sourceCoverage * selectionCoverage).coerceIn(0f, 1f)
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

    private fun drawStrokeInto(
        target: PixelBuffer,
        stroke: Stroke,
        params: BrushParams,
        points: List<StrokePoint>,
        alphaScale: Float,
        alphaLock: Boolean,
        mask: SelectionMask?,
    ) {
        if (params.spacing <= 0f && points.size <= 2) {
            drawSegment(target, stroke, params, points.first(), points.last(), alphaLock, mask)
            return
        }

        // Spacing is expressed as a fraction of the brush size; a minimum of one dab per segment
        // keeps single-point taps visible.
        val spacingPx = max(1f, params.size * params.spacing.coerceIn(0.01f, 4f))
        var carry = 0f

        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            val dx = current.x - previous.x
            val dy = current.y - previous.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)

            if (distance <= 0.0001f) {
                // Duplicate sample: a single dab keeps a tap visible without double-darkening.
                drawDabAt(target, stroke, params, previous, current, 0f, distance, alphaLock, mask)
                continue
            }

            val directionX = dx / distance
            val directionY = dy / distance
            var travelled = carry
            while (travelled <= distance) {
                val t = travelled / distance
                drawDabAt(target, stroke, params, previous, current, t, distance, alphaLock, mask)
                travelled += spacingPx
            }
            carry = travelled - distance
            // Always finish the segment so the stroke reaches the pointer position exactly.
            drawDabAt(target, stroke, params, previous, current, 1f, distance, alphaLock, mask)
        }

        if (points.size == 1) {
            drawDabAt(target, stroke, params, points.first(), points.first(), 0f, 0f, alphaLock, mask)
        }
    }

    private fun drawDabAt(
        target: PixelBuffer,
        stroke: Stroke,
        params: BrushParams,
        previous: StrokePoint,
        current: StrokePoint,
        t: Float,
        distance: Float,
        alphaLock: Boolean,
        mask: SelectionMask?,
    ) {
        val x = previous.x + (current.x - previous.x) * t
        val y = previous.y + (current.y - previous.y) * t
        val pressure = previous.pressure + (current.pressure - previous.pressure) * t

        // Velocity is derived from the sample spacing and timestamps so the dynamics are stable
        // regardless of how fast the digitiser reports.
        val elapsedMs = (current.timestamp - previous.timestamp).coerceAtLeast(1L).toFloat()
        val velocity = if (distance <= 0f) 0f else distance / elapsedMs * 1000f

        val size = params.calculateEffectiveSize(pressure, velocity)
        val opacity = params.calculateEffectiveOpacity(pressure, velocity)

        // Tapering thins the stroke over the first and last portion of its length.
        val taperFactor = taperFactor(params, stroke, current, t)
        val radius = max(0.35f, size * taperFactor / 2f)

        val color = params.applyColorJitter(stroke.color, pressure, velocity)

        // Scatter offsets each dab; count repeats it along a random perpendicular offset.
        val dabs = params.count.coerceIn(1, 32)
        repeat(dabs) { index ->
            val scatter = params.scatter.coerceIn(0f, 4f) * params.size
            val jitterX = if (scatter > 0f) ((Math.random().toFloat() * 2f - 1f) * scatter / 2f) else 0f
            val jitterY = if (scatter > 0f) ((Math.random().toFloat() * 2f - 1f) * scatter / 2f) else 0f
            val offsetScale = if (dabs == 1) 0f else (index / (dabs - 1f) - 0.5f) * params.size * 0.5f
            val offsetX = jitterX + offsetScale * (if (distance > 0f) -(current.y - previous.y) / distance else 0f)
            val offsetY = jitterY + offsetScale * (if (distance > 0f) (current.x - previous.x) / distance else 0f)

            Stamping.dab(
                target = target,
                x = x + offsetX,
                y = y + offsetY,
                radius = radius,
                color = Channels.withAlpha(color, (Channels.alpha(color) * opacity).roundToInt().coerceIn(0, 255)),
                strength = 1f,
                // Flow controls how hard each dab presses; low flow builds up gradually.
                hardness = hardnessForFlow(params),
                mode = Stamping.Mode.SOURCE_OVER,
                alphaLock = alphaLock,
                mask = mask,
            )
            lastDabCount++
        }
    }

    private fun drawSegment(
        target: PixelBuffer,
        stroke: Stroke,
        params: BrushParams,
        start: StrokePoint,
        end: StrokePoint,
        alphaLock: Boolean,
        mask: SelectionMask?,
    ) {
        val startSize = params.calculateEffectiveSize(start.pressure)
        val endSize = params.calculateEffectiveSize(end.pressure)
        val startAlpha = params.calculateEffectiveOpacity(start.pressure)
        val endAlpha = params.calculateEffectiveOpacity(end.pressure)
        val averageAlpha = (startAlpha + endAlpha) / 2f
        val color = params.applyColorJitter(stroke.color, (start.pressure + end.pressure) / 2f)

        if (start.x == end.x && start.y == end.y) {
            Stamping.dab(
                target = target,
                x = start.x,
                y = start.y,
                radius = max(0.35f, startSize / 2f),
                color = Channels.withAlpha(color, (Channels.alpha(color) * startAlpha).roundToInt().coerceIn(0, 255)),
                hardness = hardnessForFlow(params),
                alphaLock = alphaLock,
                mask = mask,
            )
            lastDabCount++
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
            alphaLock = alphaLock,
            mask = mask,
        )
        lastDabCount++
    }

    /** Low flow spreads the paint more softly, mirroring how an airbrush behaves. */
    private fun hardnessForFlow(params: BrushParams): Float {
        val flow = params.flow.coerceIn(0f, 1f)
        return (0.45f + 0.55f * flow).coerceIn(0.1f, 1f)
    }

    /** Multiplier applied to the brush size at position [current] to create start/end tapers. */
    private fun taperFactor(
        params: BrushParams,
        stroke: Stroke,
        current: StrokePoint,
        segmentT: Float,
    ): Float {
        if (params.taperStart <= 0f && params.taperEnd <= 0f) return 1f
        val totalLength = stroke.calculateLength()
        if (totalLength <= 1f) return 1f

        // Distance travelled up to this sample (approximate but monotonic, which is what matters).
        val index = stroke.points.indexOf(current)
        var travelled = 0f
        if (index > 0) {
            for (i in 1..index) travelled += stroke.points[i].distanceTo(stroke.points[i - 1])
        }
        travelled += segmentT * 0f

        val startRamp = params.taperStart.coerceIn(0f, 1f) * totalLength
        val endRamp = params.taperEnd.coerceIn(0f, 1f) * totalLength

        val startFactor = if (startRamp <= 0f) 1f else (travelled / startRamp).coerceIn(0.05f, 1f)
        val remaining = totalLength - travelled
        val endFactor = if (endRamp <= 0f) 1f else (remaining / endRamp).coerceIn(0.05f, 1f)
        return min(startFactor, endFactor)
    }

    /** Overall opacity of a stroke, derived from the dynamics applied to its points. */
    fun strokeAlpha(stroke: Stroke): Float {
        val params = stroke.brushParams
        val points = stroke.points
        if (points.isEmpty()) return params.opacity.coerceIn(0f, 1f)
        val average = points.sumOf { params.calculateEffectiveOpacity(it.pressure).toDouble() } / points.size
        return (average * params.opacity.coerceIn(0f, 1f).toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private fun scratchFor(
        width: Int,
        height: Int,
    ): PixelBuffer {
        val existing = scratch
        if (existing != null && existing.width == width && existing.height == height) return existing
        val created = PixelBuffer(width, height)
        scratch = created
        return created
    }

    /** Releases the scratch buffer (called when the canvas is disposed). */
    fun release() {
        scratch = null
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
