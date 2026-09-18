package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.Stamping
import com.artflow.studio.core.pixels.checkedPixelCount
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
        private val count = checkedPixelCount(width, height)

        // Reconstruct/no-op sessions do not need a pair of full-canvas displacement arrays.
        val offsetX: FloatArray by lazy { FloatArray(count) }
        val offsetY: FloatArray by lazy { FloatArray(count) }
        private val touched: BooleanArray by lazy { BooleanArray(count) }

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
            if (!dx.isFinite() || !dy.isFinite()) return
            if (dx == 0f && dy == 0f) return
            val index = y * width + x
            offsetX[index] += dx
            offsetY[index] += dy
            touched[index] = true
            val current = dirtyBounds
            dirtyBounds = if (current == null) IntBounds(x, y, x, y) else current.union(IntBounds(x, y, x, y))
        }

        fun hasDisplacement(index: Int): Boolean = touched[index]

        fun isEmpty(): Boolean = dirtyBounds == null

        fun clear() {
            if (isEmpty()) return
            offsetX.fill(0f)
            offsetY.fill(0f)
            touched.fill(false)
            dirtyBounds = null
        }

        /** Bilinear resample of [source] through this field. */
        fun apply(source: PixelBuffer): PixelBuffer {
            require(source.width == width && source.height == height) { "Displacement dimensions must match the layer" }
            if (isEmpty()) return source.copy()
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
        val alphaLock: Boolean = false,
    ) {
        val radius: Float get() = if (size.isFinite()) (size / 2f).coerceIn(1f, 4096f) else 1f
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
        private var samplePressure = unit(settings.pressure)
        private var reconstruction: FloatArray? = null
        private var reconstructionBounds: IntBounds? = null
        val dirtyBounds: IntBounds? get() = map.dirtyBounds ?: reconstructionBounds
        var totalDistance: Float = 0f
            private set

        /**
         * Extends the gesture to ([x], [y]).
         * @return the canvas region affected by this sample, or null when the pointer did not move.
         */
        fun dragTo(
            x: Float,
            y: Float,
            pressure: Float = settings.pressure,
        ): IntBounds? {
            if (!x.isFinite() || !y.isFinite()) return null
            samplePressure = unit(pressure)
            val dx = x - lastX
            val dy = y - lastY
            val distance = sqrt(dx * dx + dy * dy)
            if (!distance.isFinite() || distance < 0.01f) return null

            val spacing = (settings.radius * (0.12f - unit(settings.density) * 0.08f)).coerceIn(0.5f, 4f)
            val samples = ceil(distance / spacing).toInt().coerceIn(1, 65536)
            for (sample in 1..samples) {
                val t = (sample - 0.5f) / samples
                val centerX = lastX + dx * t
                val centerY = lastY + dy * t
                val stepDistance = distance / samples
                when (settings.mode) {
                    Mode.PUSH -> applyPush(centerX, centerY, dx / distance, dy / distance, stepDistance)
                    Mode.TWIRL_CLOCKWISE -> applyTwirl(centerX, centerY, stepDistance, clockwise = true)
                    Mode.TWIRL_COUNTER_CLOCKWISE -> applyTwirl(centerX, centerY, stepDistance, clockwise = false)
                    Mode.PINCH -> applyRadialScale(centerX, centerY, stepDistance, pinch = true)
                    Mode.BLOAT -> applyRadialScale(centerX, centerY, stepDistance, pinch = false)
                    Mode.RECONSTRUCT -> applyReconstruction(centerX, centerY, stepDistance)
                }
            }

            totalDistance += distance
            lastX = x
            lastY = y

            return affectedBounds(lastX, lastY, settings.radius, map.width, map.height)
        }

        private fun applyPush(
            centerX: Float,
            centerY: Float,
            directionX: Float,
            directionY: Float,
            distance: Float,
        ) {
            forEachPixelInBrush(centerX, centerY, settings.radius) { px, py, falloff ->
                // Inverse lookup: a rightward push reads pixels on its LEFT.
                val amount = smoothstep(falloff) * effectiveStrength() * coverage(px, py) * distance * 0.7f
                map.add(px, py, -directionX * amount, -directionY * amount)
            }
        }

        private fun applyTwirl(
            centerX: Float,
            centerY: Float,
            distance: Float,
            clockwise: Boolean,
        ) {
            val radius = settings.radius
            val anglePerSample = (distance / radius).coerceAtMost(0.5f) * 45f
            val direction = if (clockwise) -1f else 1f
            forEachPixelInBrush(centerX, centerY, radius) { pixelX, pixelY, falloff ->
                val offsetX = pixelX + 0.5f - centerX
                val offsetY = pixelY + 0.5f - centerY
                val pixelDistance = sqrt(offsetX * offsetX + offsetY * offsetY)
                if (pixelDistance < 0.5f) {
                    map.add(pixelX, pixelY, 0f, 0f)
                } else {
                    val angle =
                        Math.toRadians(
                            (anglePerSample * direction * smoothstep(falloff) * effectiveStrength() * coverage(pixelX, pixelY)).toDouble(),
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
            centerX: Float,
            centerY: Float,
            distance: Float,
            pinch: Boolean,
        ) {
            val radius = settings.radius
            val base = (distance / radius).coerceAtMost(1f) * if (pinch) 0.5f else -0.5f
            forEachPixelInBrush(centerX, centerY, radius) { pixelX, pixelY, falloff ->
                val offsetX = pixelX + 0.5f - centerX
                val offsetY = pixelY + 0.5f - centerY
                val scale = base * smoothstep(falloff) * effectiveStrength() * coverage(pixelX, pixelY)
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

        private fun effectiveStrength(): Float = unit(settings.strength) * samplePressure

        private fun coverage(
            x: Int,
            y: Int,
        ): Float = settings.mask?.alphaAt(y * map.width + x) ?: 1f

        private fun applyReconstruction(
            centerX: Float,
            centerY: Float,
            distance: Float,
        ) {
            if (effectiveStrength() <= 0f) return
            val restored = reconstruction ?: FloatArray(map.width * map.height).also { reconstruction = it }
            forEachPixelInBrush(centerX, centerY, settings.radius) { px, py, falloff ->
                val index = py * map.width + px
                val amount = (smoothstep(falloff) * effectiveStrength() * coverage(px, py) * distance / settings.radius).coerceIn(0f, 1f)
                if (amount > 0f) {
                    restored[index] += (1f - restored[index]) * amount
                    val pixel = IntBounds(px, py, px, py)
                    reconstructionBounds = reconstructionBounds?.union(pixel) ?: pixel
                }
            }
        }

        /** Reconstruct always reads a fixed pre-liquify reference, never its own previous preview. */
        fun render(
            source: PixelBuffer,
            original: PixelBuffer? = null,
        ): PixelBuffer {
            if (settings.mode != Mode.RECONSTRUCT) return retainCoverage(map.apply(source), source)
            require(source.width == map.width && source.height == map.height) { "Reconstruct dimensions must match the layer" }
            val out = source.copy()
            val reference = original ?: return out
            require(reference.width == source.width && reference.height == source.height) { "Reconstruct reference dimensions must match" }
            val restored = reconstruction ?: return out
            for (index in out.pixels.indices) {
                if (restored[index] >
                    0f
                ) {
                    out.pixels[index] = ImageFilters.lerpArgb(source.pixels[index], reference.pixels[index], restored[index])
                }
            }
            return retainCoverage(out, source)
        }

        /** Preserve the layer silhouette without inventing colour from zero-alpha samples. */
        private fun retainCoverage(
            result: PixelBuffer,
            source: PixelBuffer,
        ): PixelBuffer {
            if (!settings.alphaLock) return result
            for (index in result.pixels.indices) {
                val original = source.pixels[index]
                val candidate = result.pixels[index]
                val alpha = original ushr 24
                result.pixels[index] =
                    if (alpha == 0 || (candidate ushr 24) == 0) {
                        original
                    } else {
                        (candidate and 0x00FFFFFF) or (alpha shl 24)
                    }
            }
            return result
        }

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
    ): Session {
        require(x.isFinite() && y.isFinite()) { "Liquify coordinates must be finite" }
        val count = checkedPixelCount(width, height)
        require(settings.freezeMask == null || settings.freezeMask.size == count) { "Freeze mask dimensions must match" }
        require(
            settings.mask == null || (settings.mask.width == width && settings.mask.height == height),
        ) { "Selection dimensions must match" }
        val owned = settings.copy(freezeMask = settings.freezeMask?.copyOf(), mask = settings.mask?.copy())
        return Session(x, y, owned, DisplacementMap(width, height))
    }

    private fun unit(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    /** Applies the accumulated displacement to the layer in place. */
    fun commit(
        target: PixelBuffer,
        session: Session,
        original: PixelBuffer? = null,
    ): IntBounds? {
        val bounds = session.dirtyBounds ?: return null
        val warped = session.render(target, original)
        if (warped.pixels.contentEquals(target.pixels)) return null
        System.arraycopy(warped.pixels, 0, target.pixels, 0, target.pixels.size)
        return bounds
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
