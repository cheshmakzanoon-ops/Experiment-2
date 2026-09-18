package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.Stamping
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The three "sample and re-apply pixels" tools: smudge (P17), clone stamp (P19) and healing
 * brush (P20).
 *
 * They share the same gesture model — a pointer travels across the canvas stamping a soft patch —
 * but differ in where the patch comes from and how it is corrected before it lands.
 */
object PixelBrushes {
    /** Shared brush geometry used by all three tools. */
    data class BrushShape(
        val size: Float = 60f,
        /** `0..1`: 1 is a crisp anti-aliased edge, 0 is a fully soft airbrush. */
        val hardness: Float = 0.85f,
        /** Per-stamp strength, `0..1`. */
        val strength: Float = 0.6f,
        val opacity: Float = 1f,
        val mask: SelectionMask? = null,
        val alphaLock: Boolean = false,
    ) {
        val radius: Float get() = (size / 2f).coerceAtLeast(0.5f)
    }

    // ---------------------------------------------------------------------------------------
    // Smudge (Phase 17)
    // ---------------------------------------------------------------------------------------

    /**
     * Smudge session state for one drag. Real smudge tools keep reading their *own* last output
     * (that is what produces the characteristic trailing streak), so the patch source is the
     * layer itself and the sample point lags the pointer by a fraction of the brush size.
     */
    class SmudgeSession(
        private val startX: Float,
        private val startY: Float,
        var settings: SmudgeSettings = SmudgeSettings(),
    ) {
        var lastX: Float = startX
            private set
        var lastY: Float = startY
            private set
        private var accumulatedDistance = 0f

        /** Total distance travelled, exposed for the timelapse and for the "rate" readout. */
        val distance: Float get() = accumulatedDistance

        fun dragTo(
            x: Float,
            y: Float,
            target: PixelBuffer,
        ): IntBounds? {
            val dx = x - lastX
            val dy = y - lastY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < 0.01f) return null

            // Read the colour from *behind* the pointer and write it at the pointer. That offset is
            // what turns a drag into a streak instead of a re-stamp of the pixel under the cursor.
            val lag = min(distance, settings.radius * settings.sampleLag)
            val sampleX = lastX - (dx / distance) * lag
            val sampleY = lastY - (dy / distance) * lag

            val strength = (settings.strength * if (settings.fingerMode) 0.6f else 1f).coerceIn(0f, 1f)
            Stamping.smudgeSegment(
                target = target,
                fromX = sampleX,
                fromY = sampleY,
                toX = x,
                toY = y,
                radius = settings.radius,
                strength = strength,
                hardness = settings.hardness,
                mask = settings.mask,
                alphaLock = settings.alphaLock,
            )

            accumulatedDistance += distance
            val bounds =
                IntBounds.aroundRectangle(
                    min(lastX, x),
                    min(lastY, y),
                    max(lastX, x),
                    max(lastY, y),
                    padding = ceil(settings.radius).toInt() + 1,
                )

            lastX = x
            lastY = y
            return bounds
        }
    }

    data class SmudgeSettings(
        val size: Float = 60f,
        val hardness: Float = 0.6f,
        /** How much colour each stamp carries, `0..1`. */
        val strength: Float = 0.5f,
        /**
         * How far behind the pointer the colour is sampled, as a fraction of the brush radius.
         * Larger values produce longer, more painterly streaks.
         */
        val sampleLag: Float = 0.5f,
        /** Finger smudge mode pulls less colour per stamp for a gentler blend. */
        val fingerMode: Boolean = false,
        val mask: SelectionMask? = null,
        val alphaLock: Boolean = false,
    ) {
        val radius: Float get() = (size / 2f).coerceAtLeast(0.5f)
    }

    fun beginSmudge(
        x: Float,
        y: Float,
        settings: SmudgeSettings,
    ): SmudgeSession = SmudgeSession(x, y, settings)

    // ---------------------------------------------------------------------------------------
    // Clone stamp (Phase 19)
    // ---------------------------------------------------------------------------------------

    /** A named clone origin, so the artist can keep several reference points around. */
    data class CloneSource(
        val id: Long,
        val x: Float,
        val y: Float,
        val label: String,
    )

    data class CloneSettings(
        val size: Float = 60f,
        val hardness: Float = 0.85f,
        val opacity: Float = 1f,
        /**
         * Aligned: the source keeps its offset relative to the brush for the whole session
         * (the default in every desktop tool). Non-aligned: each stroke restarts from the origin.
         */
        val aligned: Boolean = true,
        /** Sample the flattened composite instead of just the active layer. */
        val sampleAllLayers: Boolean = false,
        /** Rotate the sampled patch around the source point. */
        val sourceRotation: Float = 0f,
        /** Scale the sampled patch around the source point. */
        val sourceScale: Float = 1f,
        val mask: SelectionMask? = null,
        val alphaLock: Boolean = false,
    ) {
        val radius: Float get() = (size / 2f).coerceAtLeast(0.5f)
    }

    /**
     * One clone-stamp drag. Create with [beginCloneStamp]; the offset is captured on the first
     * stamp and reused for the rest of the session when [CloneSettings.aligned] is set.
     */
    class CloneSession(
        private val targetStartX: Float,
        private val targetStartY: Float,
        private val sourceStartX: Float,
        private val sourceStartY: Float,
        var settings: CloneSettings,
    ) {
        private var lastX = targetStartX
        private var lastY = targetStartY
        private var stamped = false

        /** Offset between source and target, fixed for aligned sessions. */
        var offsetX: Float = targetStartX - sourceStartX
            private set
        var offsetY: Float = targetStartY - sourceStartY
            private set

        fun dragTo(
            x: Float,
            y: Float,
            target: PixelBuffer,
            source: PixelBuffer,
        ): IntBounds? {
            val dx = x - lastX
            val dy = y - lastY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < 0.01f) return null

            // Walk in brush-size steps so a fast drag still leaves continuous coverage.
            val step = max(1f, settings.radius * 0.25f)
            val steps = ceil(distance / step).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val px = lastX + dx * t
                val py = lastY + dy * t
                stamp(px, py, target, source)
            }

            lastX = x
            lastY = y
            stamped = true

            return IntBounds.aroundRectangle(
                min(targetStartX, x),
                min(targetStartY, y),
                max(targetStartX, x),
                max(targetStartY, y),
                padding = ceil(settings.radius).toInt() + 1,
            )
        }

        private fun stamp(
            px: Float,
            py: Float,
            target: PixelBuffer,
            source: PixelBuffer,
        ) {
            // Aligned mode keeps one fixed offset for the whole session; unaligned mode restarts
            // from the origin on every new stroke, which is why the offset is captured up front.
            val sourceX = px - offsetX
            val sourceY = py - offsetY

            val patch = readTransformedPatch(source, sourceX, sourceY, settings)

            // Source-out-of-bounds produces a transparent patch; stamping it would erase pixels.
            if (isFullyTransparent(patch.pixels)) return

            Stamping.stampPatch(
                target = target,
                patch = patch.pixels,
                centerX = px,
                centerY = py,
                radius = settings.radius,
                strength = settings.opacity,
                hardness = settings.hardness,
                mask = settings.mask,
                alphaLock = settings.alphaLock,
            )
            previewSourceX = sourceX
            previewSourceY = sourceY
        }

        /** Last sampled source position, drawn as the clone-source crosshair. */
        var previewSourceX: Float = sourceStartX
            private set
        var previewSourceY: Float = sourceStartY
            private set

        /** Resets the offset, used when the user alt-taps a new source mid-session. */
        fun resetAlignment(
            newSourceX: Float,
            newSourceY: Float,
        ) {
            offsetX = lastX - newSourceX
            offsetY = lastY - newSourceY
            stamped = false
        }
    }

    fun beginCloneStamp(
        targetX: Float,
        targetY: Float,
        sourceX: Float,
        sourceY: Float,
        settings: CloneSettings,
    ): CloneSession = CloneSession(targetX, targetY, sourceX, sourceY, settings)

    /** Reads the clone patch, applying the configured rotation and scale. */
    private fun readTransformedPatch(
        source: PixelBuffer,
        centerX: Float,
        centerY: Float,
        settings: CloneSettings,
    ): PixelBuffer {
        val radius = ceil(settings.radius).toInt().coerceAtLeast(1)
        val size = radius * 2 + 1
        val patch = PixelBuffer(size, size)
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                patch.pixels[(dy + radius) * size + (dx + radius)] =
                    source.sampleBilinear(centerX + dx + 0.5f, centerY + dy + 0.5f)
            }
        }
        if (settings.sourceRotation == 0f && settings.sourceScale == 1f) return patch

        return patch.transformed(
            targetWidth = size,
            targetHeight = size,
            translateX = 0f,
            translateY = 0f,
            scaleX = settings.sourceScale.coerceAtLeast(0.05f),
            scaleY = settings.sourceScale.coerceAtLeast(0.05f),
            rotationDegrees = settings.sourceRotation,
            pivotX = size / 2f,
            pivotY = size / 2f,
        )
    }

    private fun isFullyTransparent(pixels: IntArray): Boolean = pixels.all { (it ushr 24) == 0 }

    // ---------------------------------------------------------------------------------------
    // Healing brush (Phase 20)
    // ---------------------------------------------------------------------------------------

    data class HealingSettings(
        val size: Float = 60f,
        val hardness: Float = 0.7f,
        val strength: Float = 1f,
        /**
         * Spot healing: no source point needed, the patch is taken automatically from a ring of
         * nearby pixels that best matches the destination's surroundings.
         */
        val spotMode: Boolean = false,
        /** How far outside the brush the automatic source ring is sampled from (spot mode). */
        val sampleDistance: Float = 2.5f,
        val mask: SelectionMask? = null,
        val alphaLock: Boolean = false,
    ) {
        val radius: Float get() = (size / 2f).coerceAtLeast(0.5f)
    }

    /** One healing drag: samples a patch, colour-matches it, then blends it in with edge feathering. */
    class HealingSession(
        private val startX: Float,
        private val startY: Float,
        private val sourceStartX: Float,
        private val sourceStartY: Float,
        var settings: HealingSettings,
    ) {
        private var lastX = startX
        private var lastY = startY
        var offsetX: Float = startX - sourceStartX
            private set
        var offsetY: Float = startY - sourceStartY
            private set

        fun dragTo(
            x: Float,
            y: Float,
            target: PixelBuffer,
            source: PixelBuffer,
        ): IntBounds? {
            val dx = x - lastX
            val dy = y - lastY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < 0.01f) return null

            val step = max(1f, settings.radius * 0.3f)
            val steps = ceil(distance / step).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                heal(lastX + dx * t, lastY + dy * t, target, source)
            }

            lastX = x
            lastY = y
            return IntBounds.aroundRectangle(
                min(startX, x),
                min(startY, y),
                max(startX, x),
                max(startY, y),
                padding = ceil(settings.radius).toInt() + 1,
            )
        }

        /** Applies a single healing dab at ([px], [py]). */
        fun heal(
            px: Float,
            py: Float,
            target: PixelBuffer,
            source: PixelBuffer,
        ) {
            val radius = settings.radius
            val (sampleX, sampleY) =
                if (settings.spotMode) {
                    findSpotSource(target, px, py, settings)
                } else {
                    (px - offsetX) to (py - offsetY)
                }

            val patch = Stamping.readPatch(source, sampleX, sampleY, radius)
            val patchSize = sqrt(patch.size.toFloat()).toInt()
            if (patchSize % 2 == 0) return

            // Colour matching: shift the patch so its mean matches the destination region's mean.
            val patchMean = meanColor(patch)
            val destinationMean = meanRegion(target, px, py, radius)
            val shiftR = Channels.red(destinationMean) - Channels.red(patchMean)
            val shiftG = Channels.green(destinationMean) - Channels.green(patchMean)
            val shiftB = Channels.blue(destinationMean) - Channels.blue(patchMean)

            val corrected =
                IntArray(patch.size) { index ->
                    val pixel = patch[index]
                    val alpha = Channels.alpha(pixel).toInt()
                    if (alpha == 0) {
                        pixel
                    } else {
                        Channels.argb(
                            alpha,
                            (Channels.red(pixel) + shiftR).roundToInt().coerceIn(0, 255),
                            (Channels.green(pixel) + shiftG).roundToInt().coerceIn(0, 255),
                            (Channels.blue(pixel) + shiftB).roundToInt().coerceIn(0, 255),
                        )
                    }
                }

            Stamping.stampPatch(
                target = target,
                patch = corrected,
                centerX = px,
                centerY = py,
                radius = radius,
                strength = settings.strength,
                hardness = settings.hardness,
                mask = settings.mask,
                alphaLock = settings.alphaLock,
            )
        }
    }

    fun beginHealing(
        x: Float,
        y: Float,
        sourceX: Float,
        sourceY: Float,
        settings: HealingSettings,
    ): HealingSession = HealingSession(x, y, sourceX, sourceY, settings)

    /**
     * Picks the best automatic source for spot healing: samples 16 points around the brush at
     * [HealingSettings.sampleDistance] and keeps the one whose local mean is closest to the ring
     * around the blemish. This is what makes spot healing "just work" on skin and sky.
     */
    fun findSpotSource(
        target: PixelBuffer,
        x: Float,
        y: Float,
        settings: HealingSettings,
    ): Pair<Float, Float> {
        val radius = settings.radius
        val distance = radius * settings.sampleDistance.coerceAtLeast(1.2f)
        val samples = 16
        val surrounding = meanRegion(target, x, y, radius * 1.8f)

        var bestX = x + distance
        var bestY = y
        var bestScore = Float.MAX_VALUE

        for (i in 0 until samples) {
            val angle = (i / samples.toFloat()) * 2f * Math.PI.toFloat()
            val candidateX = x + kotlin.math.cos(angle) * distance
            val candidateY = y + kotlin.math.sin(angle) * distance
            if (candidateX < 0 || candidateY < 0 || candidateX >= target.width || candidateY >= target.height) {
                continue
            }
            val candidate = meanRegion(target, candidateX, candidateY, radius)
            val score = colorDistance(candidate, surrounding)
            if (score < bestScore) {
                bestScore = score
                bestX = candidateX
                bestY = candidateY
            }
        }
        return bestX to bestY
    }

    /** Mean ARGB over a circular region. Transparent pixels are excluded from the average. */
    fun meanRegion(
        buffer: PixelBuffer,
        centerX: Float,
        centerY: Float,
        radius: Float,
    ): Int {
        var a = 0f
        var r = 0f
        var g = 0f
        var b = 0f
        var count = 0
        val radiusSquared = radius * radius
        val x0 = max(0, floor(centerX - radius).toInt())
        val x1 = min(buffer.width - 1, ceil(centerX + radius).toInt())
        val y0 = max(0, floor(centerY - radius).toInt())
        val y1 = min(buffer.height - 1, ceil(centerY + radius).toInt())
        for (py in y0..y1) {
            for (px in x0..x1) {
                val dx = px + 0.5f - centerX
                val dy = py + 0.5f - centerY
                if (dx * dx + dy * dy > radiusSquared) continue
                val pixel = buffer.getUnchecked(px, py)
                if ((pixel ushr 24) == 0) continue
                a += Channels.alpha(pixel)
                r += Channels.red(pixel)
                g += Channels.green(pixel)
                b += Channels.blue(pixel)
                count++
            }
        }
        if (count == 0) return 0
        return Channels.fromFloats(a / count, r / count, g / count, b / count)
    }

    private fun meanColor(patch: IntArray): Int {
        var a = 0f
        var r = 0f
        var g = 0f
        var b = 0f
        var count = 0
        for (pixel in patch) {
            if ((pixel ushr 24) == 0) continue
            a += Channels.alpha(pixel)
            r += Channels.red(pixel)
            g += Channels.green(pixel)
            b += Channels.blue(pixel)
            count++
        }
        if (count == 0) return 0
        return Channels.fromFloats(a / count, r / count, g / count, b / count)
    }

    fun colorDistance(
        a: Int,
        b: Int,
    ): Float {
        val da = Channels.alpha(a) - Channels.alpha(b)
        val dr = Channels.red(a) - Channels.red(b)
        val dg = Channels.green(a) - Channels.green(b)
        val db = Channels.blue(a) - Channels.blue(b)
        return sqrt(da * da + dr * dr + dg * dg + db * db)
    }

    /** Applies a symmetric blur only inside the healed area, which hides the patch seam. */
    fun blendEdges(
        target: PixelBuffer,
        original: PixelBuffer,
        centerX: Float,
        centerY: Float,
        radius: Float,
        feather: Float,
    ) {
        val blurred = ImageFilters.gaussianBlur(original, feather.coerceAtLeast(0.5f))
        for (y in 0 until target.height) {
            for (x in 0 until target.width) {
                val dx = x + 0.5f - centerX
                val dy = y + 0.5f - centerY
                val distance = sqrt(dx * dx + dy * dy)
                if (distance > radius) continue
                val weight = Stamping.edgeWeight(radius - distance, feather)
                if (weight <= 0f) continue
                val index = y * target.width + x
                target.pixels[index] = ImageFilters.lerpArgb(target.pixels[index], blurred.pixels[index], weight * 0.35f)
            }
        }
    }

    /** Readout for the UI: how much of the brush footprint the automatic source found usable data. */
    fun sourceCoverage(patch: IntArray): Int {
        if (patch.isEmpty()) return 0
        val opaque = patch.count { (it ushr 24) != 0 }
        return ((opaque.toFloat() / patch.size) * 100f).roundToInt().coerceIn(0, 100)
    }
}
