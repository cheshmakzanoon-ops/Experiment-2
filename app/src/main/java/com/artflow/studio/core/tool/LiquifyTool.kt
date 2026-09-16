package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.Stamping
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Liquify (Phase 18).
 *
 * Five distortion modes plus a reconstruct mode. Each gesture accumulates a *displacement map*
 * which is applied in a single bilinear resample pass at commit time — the same design desktop
 * tools use, and the reason a full-canvas warp stays interactive instead of re-warping the image
 * once per pointer sample.
 *
 * A freeze mask protects areas from further distortion and can be thawed again.
 */
object LiquifyTool {
    enum class Mode(
        val displayName: String,
    ) {
        PUSH("Push"),
        TWIRL_CLOCKWISE("Twirl Right"),
        TWIRL_COUNTER_CLOCKWISE("Twirl Left"),
        PINCH("Pinch"),
        BLOAT("Bloat"),
        RECONSTRUCT("Reconstruct"),
    }

    /**
     * Dense displacement field. Stored as parallel float arrays so there is no per-pixel object
     * allocation, and `touched` marks which pixels actually moved (the resample pass is skipped for
     * everything else).
     */
    class DisplacementMap(
        val width: Int,
        val height: Int,
    ) {
        val offsetX = FloatArray(width * height)
        val offsetY = FloatArray(width * height)
        private val touched = BooleanArray(width * height)

        /** Union of every pixel this map has modified, so callers can invalidate precisely. */
        var dirtyBounds: IntBounds? = null
            private set

        fun add(
            x: Int,
            y: Int,
            dx: Float,
            dy: Float,
        ) {
            if (x < 0 || y < 0 || x >= width || y >= height) return
            val index = y * width + x
            offsetX[index] += dx
            offsetY[index] += dy
            touched[index] = true
            val current = dirtyBounds
            dirtyBounds = if (current == null) IntBounds(x, y, x, y) else current.union(IntBounds(x, y, x, y))
        }

        fun hasDisplacement(index: Int): Boolean = touched[index]

        fun isEmpty(): Boolean = touched.none { it }

        fun clear() {
            offsetX.fill(0f)
            offsetY.fill(0f)
            touched.fill(false)
            dirtyBounds = null
        }

        /** Bilinear resample of [source] through this field. */
        fun apply(source: PixelBuffer): PixelBuffer {
            val out = PixelBuffer(source.width, source.height)
            for (y in 0 until source.height) {
                for (x in 0 until source.width) {
                    val index = y * source.width + x
                    if (!touched[index]) {
                        out.pixels[index] = source.pixels[index]
                        continue
                    }
                    out.pixels[index] =
                        source.sampleBilinear(
                            x + 0.5f + offsetX[index],
                            y + 0.5f + offsetY[index],
                        )
                }
            }
            return out
        }
    }

    data class Settings(
        val mode: Mode = Mode.PUSH,
        val size: Float = 80f,
        /** `0..1` distortion amount per sample. */
        val strength: Float = 0.5f,
        /** Multiplies the strength, normally fed from stylus pressure. */
        val pressure: Float = 1f,
        /**
         * Brush density: low values sample sparsely (smooth, slow distortion), high values sample
         * every movement increment (aggressive distortion).
         */
        val density: Float = 0.5f,
        /** Optional freeze mask: `true` entries are protected from distortion. */
        val freezeMask: BooleanArray? = null,
        /** Optional selection restricting the distortion. */
        val mask: SelectionMask? = null,
    ) {
        val radius: Float get() = (size / 2f).coerceAtLeast(1f)
    }

    /**
     * One liquify gesture. The displacement map is kept for the whole drag so distortion
     * accumulates smoothly rather than being re-applied (and lost) per sample.
     */
    class Session(
        private val startX: Float,
        private val startY: Float,
        val settings: Settings,
        val map: DisplacementMap,
    ) {
        private var lastX = startX
        private var lastY = startY
        var totalDistance: Float = 0f
            private set

        /**
         * Extends the gesture to ([x], [y]).
         * @return the canvas region affected by this sample, or null when the pointer did not move.
         */
        fun dragTo(
            x: Float,
            y: Float,
        ): IntBounds? {
            val dx = x - lastX
            val dy = y - lastY
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < 0.01f) return null

            when (settings.mode) {
                Mode.PUSH -> applyPush(dx, dy, distance)
                Mode.TWIRL_CLOCKWISE -> applyTwirl(distance, clockwise = true)
                Mode.TWIRL_COUNTER_CLOCKWISE -> applyTwirl(distance, clockwise = false)
                Mode.PINCH -> applyRadialScale(distance, pinch = true)
                Mode.BLOAT -> applyRadialScale(distance, pinch = false)
                Mode.RECONSTRUCT -> Unit
            }

            totalDistance += distance
            lastX = x
            lastY = y

            return affectedBounds(lastX, lastY, settings.radius, map.width, map.height)
        }

        private fun applyPush(
            dxTotal: Float,
            dyTotal: Float,
            distance: Float,
        ) {
            val radius = settings.radius
            // Spacing between samples in pixels; smaller spacing = denser, stronger distortion.
            val spacing = max(1f, radius * (0.5f - settings.density.coerceIn(0f, 1f) * 0.4f))
            val samples = ceil(distance / spacing).toInt().coerceAtLeast(1)
            for (i in 1..samples) {
                val t = i.toFloat() / samples
                val px = lastX + dxTotal * t
                val py = lastY + dyTotal * t
                val directionX = dxTotal / distance
                val directionY = dyTotal / distance
                forEachPixelInBrush(px, py, radius) { pixelX, pixelY, falloff ->
                    val amount = smoothstep(falloff) * effectiveStrength() * radius * 0.35f
                    map.add(pixelX, pixelY, directionX * amount, directionY * amount)
                }
            }
        }

        private fun applyTwirl(
            distance: Float,
            clockwise: Boolean,
        ) {
            val radius = settings.radius
            val anglePerSample = (distance / radius).coerceAtMost(0.5f) * 45f
            val direction = if (clockwise) 1f else -1f
            forEachPixelInBrush(lastX, lastY, radius) { pixelX, pixelY, falloff ->
                val offsetX = pixelX + 0.5f - lastX
                val offsetY = pixelY + 0.5f - lastY
                val pixelDistance = sqrt(offsetX * offsetX + offsetY * offsetY)
                if (pixelDistance < 0.5f) {
                    map.add(pixelX, pixelY, 0f, 0f)
                } else {
                    val angle =
                        Math.toRadians(
                            (anglePerSample * direction * smoothstep(falloff) * effectiveStrength()).toDouble(),
                        )
                    val cosA = kotlin.math.cos(angle).toFloat()
                    val sinA = kotlin.math.sin(angle).toFloat()
                    val rotatedX = offsetX * cosA - offsetY * sinA
                    val rotatedY = offsetX * sinA + offsetY * cosA
                    map.add(pixelX, pixelY, rotatedX - offsetX, rotatedY - offsetY)
                }
            }
        }

        private fun applyRadialScale(
            distance: Float,
            pinch: Boolean,
        ) {
            val radius = settings.radius
            val base = (distance / radius).coerceAtMost(1f) * if (pinch) -0.5f else 0.5f
            forEachPixelInBrush(lastX, lastY, radius) { pixelX, pixelY, falloff ->
                val offsetX = pixelX + 0.5f - lastX
                val offsetY = pixelY + 0.5f - lastY
                val scale = base * smoothstep(falloff) * effectiveStrength()
                map.add(pixelX, pixelY, offsetX * scale, offsetY * scale)
            }
        }

        /** Iterates the brush footprint, skipping frozen and unselected pixels. */
        private inline fun forEachPixelInBrush(
            centerX: Float,
            centerY: Float,
            radius: Float,
            action: (pixelX: Int, pixelY: Int, falloff: Float) -> Unit,
        ) {
            val x0 = max(0, floor(centerX - radius).toInt())
            val x1 = min(map.width - 1, ceil(centerX + radius).toInt())
            val y0 = max(0, floor(centerY - radius).toInt())
            val y1 = min(map.height - 1, ceil(centerY + radius).toInt())
            for (py in y0..y1) {
                for (px in x0..x1) {
                    val index = py * map.width + px
                    val freezeMask = settings.freezeMask
                    if (freezeMask != null && index < freezeMask.size && freezeMask[index]) continue
                    val selection = settings.mask
                    if (selection != null && selection.alphaAt(index) <= 0f) continue
                    val dx = px + 0.5f - centerX
                    val dy = py + 0.5f - centerY
                    val distanceSquared = dx * dx + dy * dy
                    if (distanceSquared > radius * radius) continue
                    action(px, py, 1f - sqrt(distanceSquared) / radius)
                }
            }
        }

        private fun effectiveStrength(): Float = settings.strength.coerceIn(0f, 1f) * settings.pressure.coerceIn(0f, 1f)

        private fun smoothstep(falloff: Float): Float {
            val t = falloff.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }

    fun beginSession(
        x: Float,
        y: Float,
        settings: Settings,
        width: Int,
        height: Int,
    ): Session = Session(x, y, settings, DisplacementMap(width, height))

    /** Applies the accumulated displacement to the layer in place. */
    fun commit(
        target: PixelBuffer,
        session: Session,
    ): IntBounds? {
        if (session.map.isEmpty()) return null
        val warped = session.map.apply(target)
        System.arraycopy(warped.pixels, 0, target.pixels, 0, target.pixels.size)
        return session.map.dirtyBounds
    }

    /**
     * Reconstruct: blends the warped pixels back towards [original]. This is what the Reconstruct
     * brush does in desktop tools — it undoes a liquify gradually without a full undo step.
     */
    fun reconstruct(
        target: PixelBuffer,
        original: PixelBuffer,
        amount: Float,
        radius: Float,
        centerX: Float,
        centerY: Float,
        mask: SelectionMask? = null,
    ) {
        val strength = amount.coerceIn(0f, 1f)
        if (strength <= 0f) return
        if (original.width != target.width || original.height != target.height) return

        val x0 = max(0, floor(centerX - radius).toInt())
        val x1 = min(target.width - 1, ceil(centerX + radius).toInt())
        val y0 = max(0, floor(centerY - radius).toInt())
        val y1 = min(target.height - 1, ceil(centerY + radius).toInt())
        for (py in y0..y1) {
            for (px in x0..x1) {
                val dx = px + 0.5f - centerX
                val dy = py + 0.5f - centerY
                val distance = sqrt(dx * dx + dy * dy)
                if (distance > radius) continue
                val falloff = 1f - distance / radius
                val index = py * target.width + px
                val coverage = mask?.alphaAt(index) ?: 1f
                val effective = falloff * falloff * strength * coverage
                if (effective <= 0f) continue
                target.pixels[index] =
                    ImageFilters.lerpArgb(
                        target.pixels[index],
                        original.pixels[index],
                        effective,
                    )
            }
        }
    }

    /** Freezes (or thaws) a circular area so later strokes leave it alone. */
    fun paintFreezeMask(
        freezeMask: BooleanArray,
        width: Int,
        height: Int,
        centerX: Float,
        centerY: Float,
        radius: Float,
        frozen: Boolean,
    ) {
        require(freezeMask.size == width * height) { "Freeze mask size must match the canvas" }
        val x0 = max(0, floor(centerX - radius).toInt())
        val x1 = min(width - 1, ceil(centerX + radius).toInt())
        val y0 = max(0, floor(centerY - radius).toInt())
        val y1 = min(height - 1, ceil(centerY + radius).toInt())
        for (py in y0..y1) {
            for (px in x0..x1) {
                val dx = px + 0.5f - centerX
                val dy = py + 0.5f - centerY
                if (dx * dx + dy * dy > radius * radius) continue
                freezeMask[py * width + px] = frozen
            }
        }
    }

    /** Inverts the freeze mask (the "Thaw All" / "Freeze All" toggle). */
    fun invertFreezeMask(freezeMask: BooleanArray) {
        for (i in freezeMask.indices) freezeMask[i] = !freezeMask[i]
    }

    /** Fraction of the canvas currently frozen, for the UI readout. */
    fun frozenPercent(freezeMask: BooleanArray): Int {
        if (freezeMask.isEmpty()) return 0
        return ((freezeMask.count { it }.toFloat() / freezeMask.size) * 100f).toInt().coerceIn(0, 100)
    }

    /**
     * One-shot push, used by the quick menu's "nudge" action and by tests.
     * @return the affected region, or null when nothing changed.
     */
    fun pushOnce(
        target: PixelBuffer,
        x: Float,
        y: Float,
        deltaX: Float,
        deltaY: Float,
        size: Float,
        strength: Float,
    ): IntBounds? {
        Stamping.displace(
            target = target,
            centerX = x,
            centerY = y,
            radius = (size / 2f).coerceAtLeast(1f),
            deltaX = deltaX,
            deltaY = deltaY,
            strength = strength.coerceIn(0f, 1f),
        )
        return affectedBounds(x, y, size / 2f, target.width, target.height)
    }

    /** Bounds helper so callers can size their invalidations without duplicating clamp logic. */
    fun affectedBounds(
        centerX: Float,
        centerY: Float,
        radius: Float,
        width: Int,
        height: Int,
    ): IntBounds? {
        val bounds =
            IntBounds
                .aroundRectangle(
                    centerX - radius,
                    centerY - radius,
                    centerX + radius,
                    centerY + radius,
                    padding = 2,
                ).clamped(width, height)
        return if (bounds.isEmpty) null else bounds
    }
}
