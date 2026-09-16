package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Gradient tool (Phase 21).
 *
 * Four gradient geometries, multi-stop colour ramps with per-stop alpha (so gradients can fade to
 * transparent, which is what artists actually use them for), ordered dithering to avoid banding on
 * 8-bit output, and a preset library.
 */
object GradientTool {

    enum class GradientType(val displayName: String) {
        LINEAR("Linear"),
        RADIAL("Radial"),
        ANGULAR("Angular"),
        DIAMOND("Diamond")
    }

    /**
     * A colour stop. [position] is `0..1` along the gradient; [color] carries its own alpha so a
     * stop can be fully transparent.
     */
    data class Stop(val position: Float, val color: Int)

    data class Gradient(
        val name: String,
        val type: GradientType,
        val stops: List<Stop>
    ) {
        init {
            require(stops.size >= 2) { "A gradient needs at least two stops" }
        }

        /** The stops sorted by position, which is what the sampler assumes. */
        val sortedStops: List<Stop> get() = stops.sortedBy { it.position }

        /** Colour at [t] (`0..1`) along the ramp. */
        fun colorAt(t: Float): Int {
            val sorted = sortedStops
            val clamped = t.coerceIn(0f, 1f)
            if (clamped <= sorted.first().position) return sorted.first().color
            if (clamped >= sorted.last().position) return sorted.last().color

            for (i in 0 until sorted.size - 1) {
                val a = sorted[i]
                val b = sorted[i + 1]
                if (clamped in a.position..b.position) {
                    val span = (b.position - a.position)
                    val localT = if (span <= 1e-6f) 0f else (clamped - a.position) / span
                    return lerpColor(a.color, b.color, localT)
                }
            }
            return sorted.last().color
        }
    }

    data class Settings(
        val gradient: Gradient = Presets.BLACK_TO_WHITE,
        /** Reverse the ramp without editing the stops. */
        val reverse: Boolean = false,
        /** Restrict the gradient to a selection / layer mask. */
        val mask: SelectionMask? = null,
        /** Restrict to existing pixels (alpha-locked layer). */
        val alphaLock: Boolean = false,
        /** Ordered dithering removes visible banding in smooth ramps. */
        val dither: Boolean = true,
        /** Overall opacity of the gradient layer, `0..1`. */
        val opacity: Float = 1f
    )

    data class Result(val bounds: IntBounds?, val changed: Boolean)

    /**
     * Draw a gradient defined by the drag from ([startX], [startY]) to ([endX], [endY]).
     *
     * For [GradientType.LINEAR] the drag defines the axis. For radial/angular/diamond the start
     * point is the centre and the drag length is the radius / sweep origin.
     */
    fun draw(
        target: PixelBuffer,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        settings: Settings = Settings()
    ): Result {
        val dx = endX - startX
        val dy = endY - startY
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.5f) return Result(null, changed = false)

        val opacity = settings.opacity.coerceIn(0f, 1f)
        if (opacity <= 0f) return Result(null, changed = false)

        var minX = target.width
        var minY = target.height
        var maxX = -1
        var maxY = -1
        var changed = false

        // A 4x4 Bayer matrix for ordered dithering.
        val bayer = BAYER_4X4
        val tStep = if (settings.dither) 1f / 512f else 0f

        for (y in 0 until target.height) {
            for (x in 0 until target.width) {
                val index = y * target.width + x
                var coverage = settings.mask?.alphaAt(index) ?: 1f
                if (coverage <= 0f) continue

                val existing = target.pixels[index]
                if (settings.alphaLock) {
                    val destinationAlpha = (existing ushr 24) and 0xFF
                    if (destinationAlpha == 0) continue
                    coverage *= destinationAlpha / 255f
                }

                val positionX = x + 0.5f
                val positionY = y + 0.5f

                var t = when (settings.gradient.type) {
                    GradientType.LINEAR -> {
                        // Projection of the pixel onto the drag axis.
                        (((positionX - startX) * dx + (positionY - startY) * dy) / (length * length))
                            .coerceIn(0f, 1f)
                    }
                    GradientType.RADIAL -> {
                        val distance = sqrt(
                            (positionX - startX) * (positionX - startX) +
                                (positionY - startY) * (positionY - startY)
                        )
                        (distance / length).coerceIn(0f, 1f)
                    }
                    GradientType.ANGULAR -> {
                        val angle = atan2((positionY - startY).toDouble(), (positionX - startX).toDouble())
                        val sweepStart = atan2(dy.toDouble(), dx.toDouble())
                        var degrees = Math.toDegrees(angle - sweepStart).toFloat()
                        if (degrees < 0f) degrees += 360f
                        (degrees / 360f).coerceIn(0f, 1f)
                    }
                    GradientType.DIAMOND -> {
                        val normalizedX = abs(positionX - startX) / length
                        val normalizedY = abs(positionY - startY) / length
                        (normalizedX + normalizedY).coerceIn(0f, 1f)
                    }
                }

                if (settings.dither) {
                    val matrix = bayer[(y and 3) * 4 + (x and 3)] / 16f - 0.5f
                    t = (t + matrix * tStep * 2f).coerceIn(0f, 1f)
                }
                if (settings.reverse) t = 1f - t

                val stopColor = settings.gradient.colorAt(t)
                val blended = resolve(existing, stopColor, coverage)

                if (blended != existing) {
                    target.pixels[index] = blended
                    changed = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val bounds = if (maxX >= minX && maxY >= minY) IntBounds(minX, minY, maxX, maxY) else null
        return Result(bounds, changed)
    }

    private fun resolve(backdrop: Int, gradientColor: Int, coverage: Float): Int {
        val withOpacity = Channels.scaleAlpha(gradientColor, coverage)
        return BlendModes.sourceOver(backdrop, withOpacity)
    }

    /** Preview ramp for the gradient picker: [steps] swatches from the gradient. */
    fun previewSwatches(gradient: Gradient, steps: Int = 32): List<Int> =
        (0 until steps).map { gradient.colorAt(it / (steps - 1f)) }

    /** Samples the composited colour under a gradient for the live preview tile. */
    fun sampleAt(gradient: Gradient, t: Float): Int = gradient.colorAt(t)

    /** Linear interpolation in premultiplied space; keeps alpha crossfades clean. */
    fun lerpColor(from: Int, to: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val fromA = Channels.alpha(from) / 255f
        val toA = Channels.alpha(to) / 255f
        val outA = fromA + (toA - fromA) * clamped

        if (outA <= 0f) return 0

        // Premultiply so a fade to transparent fades cleanly instead of shifting hue.
        val r = (Channels.red(from) * fromA + (Channels.red(to) * toA - Channels.red(from) * fromA) * clamped) / outA
        val g = (Channels.green(from) * fromA + (Channels.green(to) * toA - Channels.green(from) * fromA) * clamped) / outA
        val b = (Channels.blue(from) * fromA + (Channels.blue(to) * toA - Channels.blue(from) * fromA) * clamped) / outA
        return Channels.fromFloats(outA * 255f, r, g, b)
    }

    /** Built-in gradients. */
    object Presets {

        /** Every preset, in the order the picker shows them. */
        val ALL: List<Gradient> by lazy {
            listOf(
                BLACK_TO_WHITE, WHITE_TO_TRANSPARENT, BLACK_TO_TRANSPARENT,
                SUNSET, OCEAN, SPECTRUM, ALPHA_FADE
            )
        }

        val BLACK_TO_WHITE = Gradient(
            name = "Black to White",
            type = GradientType.LINEAR,
            stops = listOf(
                Stop(0f, 0xFF000000.toInt()),
                Stop(1f, 0xFFFFFFFF.toInt())
            )
        )

        val WHITE_TO_TRANSPARENT = Gradient(
            name = "White to Transparent",
            type = GradientType.LINEAR,
            stops = listOf(
                Stop(0f, 0xFFFFFFFF.toInt()),
                Stop(1f, 0x00FFFFFF)
            )
        )

        val BLACK_TO_TRANSPARENT = Gradient(
            name = "Black to Transparent",
            type = GradientType.LINEAR,
            stops = listOf(
                Stop(0f, 0xFF000000.toInt()),
                Stop(1f, 0x00000000)
            )
        )

        val SUNSET = Gradient(
            name = "Sunset",
            type = GradientType.LINEAR,
            stops = listOf(
                Stop(0f, 0xFFFF6B5C.toInt()),
                Stop(0.5f, 0xFFFFB25C.toInt()),
                Stop(1f, 0xFF5C7CFA.toInt())
            )
        )

        val OCEAN = Gradient(
            name = "Ocean",
            type = GradientType.LINEAR,
            stops = listOf(
                Stop(0f, 0xFF0B3954.toInt()),
                Stop(0.5f, 0xFF087E8B.toInt()),
                Stop(1f, 0xFFBFD7EA.toInt())
            )
        )

        val SPECTRUM = Gradient(
            name = "Spectrum",
            type = GradientType.LINEAR,
            stops = listOf(
                Stop(0f, 0xFFFF0000.toInt()),
                Stop(0.17f, 0xFFFFFF00.toInt()),
                Stop(0.33f, 0xFF00FF00.toInt()),
                Stop(0.5f, 0xFF00FFFF.toInt()),
                Stop(0.67f, 0xFF0000FF.toInt()),
                Stop(0.83f, 0xFFFF00FF.toInt()),
                Stop(1f, 0xFFFF0000.toInt())
            )
        )

        val ALPHA_FADE = Gradient(
            name = "Alpha Fade",
            type = GradientType.LINEAR,
            stops = listOf(
                Stop(0f, 0x00FFFFFF),
                Stop(0.5f, 0xFFFFFFFF.toInt()),
                Stop(1f, 0x00FFFFFF)
            )
        )

        /** Every preset, in the order shown in the picker. */
        fun all(): List<Gradient> = listOf(
            BLACK_TO_WHITE,
            WHITE_TO_TRANSPARENT,
            BLACK_TO_TRANSPARENT,
            SUNSET,
            OCEAN,
            SPECTRUM,
            ALPHA_FADE
        )

        fun byName(name: String): Gradient = all().firstOrNull { it.name == name } ?: BLACK_TO_WHITE
    }

    /** 4x4 ordered dither matrix, values 0..15. */
    private val BAYER_4X4 = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    )

    /** Clamp helper used by the editor when the drag leaves the canvas. */
    fun clampToCanvas(value: Float, max: Int): Float = value.coerceIn(0f, max.toFloat())

    /** Normalised direction name shown in the UI while dragging. */
    fun describeDirection(startX: Float, startY: Float, endX: Float, endY: Float): String {
        val dx = endX - startX
        val dy = endY - startY
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val normalized = ((angle % 360f) + 360f) % 360f
        return when {
            normalized < 22.5f || normalized >= 337.5f -> "→"
            normalized < 67.5f -> "↘"
            normalized < 112.5f -> "↓"
            normalized < 157.5f -> "↙"
            normalized < 202.5f -> "←"
            normalized < 247.5f -> "↖"
            normalized < 292.5f -> "↑"
            else -> "↗"
        }
    }

    /** Number of stops a gradient may hold (guards the editor UI). */
    const val MAX_STOPS = 16

    /** Inserts a stop at [position], interpolating its colour from the ramp. */
    fun insertStop(gradient: Gradient, position: Float): Gradient {
        if (gradient.stops.size >= MAX_STOPS) return gradient
        val clamped = position.coerceIn(0f, 1f)
        val color = gradient.colorAt(clamped)
        val stops = (gradient.stops + Stop(clamped, color)).sortedBy { it.position }
        return gradient.copy(stops = stops)
    }

    /** Removes the stop closest to [position], keeping at least two stops. */
    fun removeStop(gradient: Gradient, position: Float): Gradient {
        if (gradient.stops.size <= 2) return gradient
        val target = gradient.sortedStops.minByOrNull { abs(it.position - position) } ?: return gradient
        return gradient.copy(stops = gradient.stops.filterNot { it === target })
    }

    /** Moves an existing stop, keeping it inside its neighbours (stops must stay ordered). */
    fun moveStop(gradient: Gradient, index: Int, newPosition: Float): Gradient {
        val sorted = gradient.sortedStops
        if (index !in sorted.indices) return gradient
        val lowerBound = if (index == 0) 0f else sorted[index - 1].position
        val upperBound = if (index == sorted.size - 1) 1f else sorted[index + 1].position
        val clamped = newPosition.coerceIn(min(lowerBound, upperBound), max(lowerBound, upperBound))
        val stops = sorted.toMutableList()
        stops[index] = stops[index].copy(position = clamped)
        return gradient.copy(stops = stops)
    }

    /**
     * Turns a gradient into a selection/mask coverage using its alpha channel as a horizontal
     * ramp. This is the "gradient mask" feature: `White -> Transparent` fades the left edge of a
     * layer out, `Alpha Fade` softens both sides.
     */
    fun horizontalRampMask(gradient: Gradient, width: Int, height: Int): SelectionMask {
        require(width > 0 && height > 0) { "Mask must be at least 1x1" }
        val mask = SelectionMask(width, height)
        for (x in 0 until width) {
            val t = if (width <= 1) 0f else x / (width - 1f)
            val alpha = (gradient.colorAt(t) ushr 24) and 0xFF
            val column = alpha.toByte()
            for (y in 0 until height) {
                mask.coverage[y * width + x] = column
            }
        }
        return mask
    }

    /** Percentage readout for the UI while the user drags a gradient. */
    fun coveragePercent(result: Result, target: PixelBuffer): Int {
        if (!result.changed) return 0
        val total = target.width * target.height
        val affected = result.bounds?.let { it.width * it.height } ?: 0
        return ((affected.toFloat() / total) * 100f).roundToInt().coerceIn(0, 100)
    }
}
