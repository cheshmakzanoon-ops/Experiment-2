package com.artflow.studio.core.pixels

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shared rasterising primitives used by the pixel tools (smudge, clone stamp, healing, paint
 * bucket edge soften, brush dab stamping).
 *
 * Everything here is deterministic and pure Kotlin so the tools can be verified off-device.
 */
object Stamping {
    /** How a stamped pixel is merged into the destination. */
    enum class Mode { SOURCE_OVER, REPLACE, ADD, SUBTRACT }

    /**
     * Stamp a single soft round dab.
     *
     * @param hardness `0..1`, where `1` is a hard anti-aliased edge and `0` is a fully soft falloff.
     * @param mode merge behaviour; [Mode.REPLACE] is used by the eraser and by clone/heal.
     * @param alphaLock when true, only pixels that are already opaque are modified.
     * @param mask optional selection coverage restricting the stamp.
     */
    fun dab(
        target: PixelBuffer,
        x: Float,
        y: Float,
        radius: Float,
        color: Int,
        strength: Float = 1f,
        hardness: Float = 0.8f,
        mode: Mode = Mode.SOURCE_OVER,
        alphaLock: Boolean = false,
        mask: SelectionMask? = null,
    ) {
        if (radius <= 0f || strength <= 0f) return
        val x0 = max(0, floor(x - radius).toInt())
        val x1 = min(target.width - 1, ceil(x + radius).toInt())
        val y0 = max(0, floor(y - radius).toInt())
        val y1 = min(target.height - 1, ceil(y + radius).toInt())
        if (x1 < x0 || y1 < y0) return

        val inner = radius * hardness.coerceIn(0f, 1f)
        val edge = max(radius - inner, 0.75f)

        for (py in y0..y1) {
            for (px in x0..x1) {
                val dx = px + 0.5f - x
                val dy = py + 0.5f - y
                val distance = sqrt(dx * dx + dy * dy)
                if (distance > radius) continue
                val falloff = if (distance <= inner) 1f else ((radius - distance) / edge).coerceIn(0f, 1f)
                val index = py * target.width + px
                applyPixel(target, index, color, falloff * strength, mode, alphaLock, mask)
            }
        }
    }

    /**
     * Stamp a capsule (thick line segment with round caps) with per-end radii. This is the
     * primitive behind pressure-varying strokes and the eraser; it produces exactly the same
     * geometry as the CPU stroke rasteriser used for export.
     */
    fun capsule(
        target: PixelBuffer,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        radiusStart: Float,
        radiusEnd: Float,
        color: Int,
        strength: Float = 1f,
        hardness: Float = 0.8f,
        mode: Mode = Mode.SOURCE_OVER,
        alphaLock: Boolean = false,
        mask: SelectionMask? = null,
    ) {
        val maxRadius = max(radiusStart, radiusEnd)
        if (maxRadius <= 0f || strength <= 0f) return

        val left = max(0, floor(min(x0, x1) - maxRadius).toInt())
        val right = min(target.width - 1, ceil(max(x0, x1) + maxRadius).toInt())
        val top = max(0, floor(min(y0, y1) - maxRadius).toInt())
        val bottom = min(target.height - 1, ceil(max(y0, y1) + maxRadius).toInt())
        if (right < left || bottom < top) return

        val segmentX = x1 - x0
        val segmentY = y1 - y0
        val segmentLengthSquared = segmentX * segmentX + segmentY * segmentY

        for (py in top..bottom) {
            for (px in left..right) {
                val pointX = px + 0.5f
                val pointY = py + 0.5f

                // Projection of the point onto the segment, clamped to [0, 1].
                val t =
                    if (segmentLengthSquared <= 1e-6f) {
                        0f
                    } else {
                        (((pointX - x0) * segmentX + (pointY - y0) * segmentY) / segmentLengthSquared)
                            .coerceIn(0f, 1f)
                    }

                val closestX = x0 + segmentX * t
                val closestY = y0 + segmentY * t
                val radius = radiusStart + (radiusEnd - radiusStart) * t

                val dx = pointX - closestX
                val dy = pointY - closestY
                val distance = sqrt(dx * dx + dy * dy)
                if (distance > radius) continue

                val inner = radius * hardness.coerceIn(0f, 1f)
                val edge = max(radius - inner, 0.75f)
                val falloff = if (distance <= inner) 1f else ((radius - distance) / edge).coerceIn(0f, 1f)
                val index = py * target.width + px
                applyPixel(target, index, color, falloff * strength, mode, alphaLock, mask)
            }
        }
    }

    /**
     * Read a source colour for clone/heal style tools, blending several samples for a softer,
     * less obviously copied result.
     */
    fun sampleAveraged(
        source: PixelBuffer,
        x: Float,
        y: Float,
        radius: Int,
        jitterX: Float = 0f,
        jitterY: Float = 0f,
    ): Int {
        var a = 0f
        var r = 0f
        var g = 0f
        var b = 0f
        var samples = 0
        val cx = x + jitterX
        val cy = y + jitterY
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val sample = source.getSafe(floor(cx).toInt() + dx, floor(cy).toInt() + dy)
                val sa = Channels.alpha(sample)
                a += sa
                r += Channels.red(sample)
                g += Channels.green(sample)
                b += Channels.blue(sample)
                samples++
            }
        }
        if (samples == 0) return 0
        return Channels.fromFloats(a / samples, r / samples, g / samples, b / samples)
    }

    /** Smudge: move colour along a segment by mixing source and destination. */
    fun smudgeSegment(
        target: PixelBuffer,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        radius: Float,
        strength: Float,
        hardness: Float,
        mask: SelectionMask? = null,
    ) {
        if (radius <= 0f || strength <= 0f) return
        // Read the source patch first: dragging while reading would smear the write back in.
        val patch = readPatch(target, fromX, fromY, radius)
        val alpha = strength.coerceIn(0f, 1f)
        stampPatch(target, patch, toX, toY, radius, alpha, hardness, mask)
    }

    /** Reads a circular patch into a (2r+1)^2 buffer so it can be written back displaced. */
    fun readPatch(
        source: PixelBuffer,
        centerX: Float,
        centerY: Float,
        radius: Float,
    ): IntArray {
        val r = ceil(radius).toInt()
        val size = r * 2 + 1
        val patch = IntArray(size * size)
        for (dy in -r..r) {
            for (dx in -r..r) {
                patch[(dy + r) * size + (dx + r)] =
                    source.sampleBilinear(
                        centerX + dx + 0.5f,
                        centerY + dy + 0.5f,
                    )
            }
        }
        return patch
    }

    /** Writes a patch read by [readPatch] back at a displaced position, with a soft edge. */
    fun stampPatch(
        target: PixelBuffer,
        patch: IntArray,
        centerX: Float,
        centerY: Float,
        radius: Float,
        strength: Float,
        hardness: Float,
        mask: SelectionMask? = null,
    ) {
        val r = (sqrt(patch.size.toFloat()).toInt() - 1) / 2
        if (r <= 0) return
        val inner = radius * hardness.coerceIn(0f, 1f)
        val edge = max(radius - inner, 0.75f)
        for (dy in -r..r) {
            for (dx in -r..r) {
                val px = floor(centerX).toInt() + dx
                val py = floor(centerY).toInt() + dy
                if (!target.contains(px, py)) continue
                val distance = sqrt((dx * dx + dy * dy).toFloat())
                if (distance > radius) continue
                val falloff = if (distance <= inner) 1f else ((radius - distance) / edge).coerceIn(0f, 1f)
                val index = py * target.width + px
                val coverage = if (mask != null) mask.alphaAt(index) else 1f
                val effective = falloff * strength * coverage
                if (effective <= 0f) continue
                val sample = patch[(dy + r) * (r * 2 + 1) + (dx + r)]
                target.pixels[index] = ImageFilters.lerpArgb(target.pixels[index], sample, effective)
            }
        }
    }

    /** Bilinear displacement used by the liquify tool. */
    fun displace(
        target: PixelBuffer,
        centerX: Float,
        centerY: Float,
        radius: Float,
        deltaX: Float,
        deltaY: Float,
        strength: Float,
        freeze: BooleanArray? = null,
    ) {
        if (radius <= 0f) return
        val snapshot = target.copy()
        val x0 = max(0, floor(centerX - radius).toInt())
        val x1 = min(target.width - 1, ceil(centerX + radius).toInt())
        val y0 = max(0, floor(centerY - radius).toInt())
        val y1 = min(target.height - 1, ceil(centerY + radius).toInt())
        val falloffRadius = max(radius, 0.001f)

        for (py in y0..y1) {
            for (px in x0..x1) {
                val index = py * target.width + px
                if (freeze != null && index < freeze.size && freeze[index]) continue
                val dx = px + 0.5f - centerX
                val dy = py + 0.5f - centerY
                val distance = sqrt(dx * dx + dy * dy)
                if (distance > radius) continue
                val falloff = (1f - distance / falloffRadius)
                val soft = falloff * falloff * strength.coerceIn(0f, 1f)
                val sourceX = px + 0.5f + deltaX * soft - 0.5f
                val sourceY = py + 0.5f + deltaY * soft - 0.5f
                target.pixels[index] = snapshot.sampleBilinear(sourceX + 0.5f, sourceY + 0.5f)
            }
        }
    }

    /** Rotate pixels around a centre inside a circular area (liquify twirl). */
    fun twirl(
        target: PixelBuffer,
        centerX: Float,
        centerY: Float,
        radius: Float,
        angleDegrees: Float,
        strength: Float,
        freeze: BooleanArray? = null,
    ) {
        if (radius <= 0f) return
        val snapshot = target.copy()
        val x0 = max(0, floor(centerX - radius).toInt())
        val x1 = min(target.width - 1, ceil(centerX + radius).toInt())
        val y0 = max(0, floor(centerY - radius).toInt())
        val y1 = min(target.height - 1, ceil(centerY + radius).toInt())

        for (py in y0..y1) {
            for (px in x0..x1) {
                val index = py * target.width + px
                if (freeze != null && index < freeze.size && freeze[index]) continue
                val dx = px + 0.5f - centerX
                val dy = py + 0.5f - centerY
                val distance = sqrt(dx * dx + dy * dy)
                if (distance > radius) continue
                val falloff = 1f - distance / radius
                val angle = Math.toRadians((angleDegrees * falloff * strength).toDouble())
                val cosA = kotlin.math.cos(angle).toFloat()
                val sinA = kotlin.math.sin(angle).toFloat()
                val rotatedX = dx * cosA - dy * sinA + centerX
                val rotatedY = dx * sinA + dy * cosA + centerY
                target.pixels[index] = snapshot.sampleBilinear(rotatedX, rotatedY)
            }
        }
    }

    private fun applyPixel(
        target: PixelBuffer,
        index: Int,
        color: Int,
        strength: Float,
        mode: Mode,
        alphaLock: Boolean,
        mask: SelectionMask?,
    ) {
        val coverage = if (mask != null) mask.alphaAt(index) else 1f
        var effective = (strength * coverage).coerceIn(0f, 1f)
        if (effective <= 0f) return

        val existing = target.pixels[index]
        if (alphaLock) {
            val dstAlpha = (existing ushr 24) and 0xFF
            if (dstAlpha == 0) return
            effective *= dstAlpha / 255f
            if (effective <= 0f) return
        }

        target.pixels[index] =
            when (mode) {
                Mode.SOURCE_OVER -> BlendModes.sourceOver(existing, Channels.scaleAlpha(color, effective))
                Mode.REPLACE -> ImageFilters.lerpArgb(existing, color, effective)
                Mode.ADD ->
                    Channels.fromFloats(
                        a = min(255f, Channels.alpha(existing) + 255f * effective * (Channels.alpha(color) / 255f)),
                        r = Channels.red(existing) + Channels.red(color) * effective,
                        g = Channels.green(existing) + Channels.green(color) * effective,
                        b = Channels.blue(existing) + Channels.blue(color) * effective,
                    )
                Mode.SUBTRACT ->
                    Channels.fromFloats(
                        a = Channels.alpha(existing) + (255f - Channels.alpha(existing)) * effective,
                        r = Channels.red(existing) - Channels.red(color) * effective,
                        g = Channels.green(existing) - Channels.green(color) * effective,
                        b = Channels.blue(existing) - Channels.blue(color) * effective,
                    )
            }
    }

    /** Signed distance helper used by the healing brush to weight its edge blend. */
    fun edgeWeight(
        distanceFromEdge: Float,
        feather: Float,
    ): Float {
        if (feather <= 0f) return 1f
        return ((distanceFromEdge / feather).coerceIn(0f, 1f)).let { it * it * (3f - 2f * it) }
    }

    /** Total distance along a polyline (used by path tools and timelapse metrics). */
    fun polylineLength(points: List<Pair<Float, Float>>): Float {
        if (points.size < 2) return 0f
        var total = 0f
        for (i in 1 until points.size) {
            val dx = points[i].first - points[i - 1].first
            val dy = points[i].second - points[i - 1].second
            total += sqrt(dx * dx + dy * dy)
        }
        return total
    }
}
