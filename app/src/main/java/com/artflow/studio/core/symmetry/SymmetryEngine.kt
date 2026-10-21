package com.artflow.studio.core.symmetry

import com.artflow.studio.core.perspective.GuideLineStyle
import com.artflow.studio.domain.model.brush.Stroke
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Symmetry tools (Phase 33).
 *
 * Symmetry in a raster painting app is implemented by *replicating the brush motion*, not by
 * mirroring pixels: every pointer sample is transformed and the brush is stamped once per
 * reflection. That keeps variable width, texture and colour dynamics consistent across all
 * mirrored strokes.
 *
 * The maths is pure geometry, so it is fully unit-testable.
 */
object SymmetryEngine {
    enum class SymmetryType(
        val displayName: String,
    ) {
        NONE("None"),

        /** Mirror across the vertical axis (left <-> right). */
        VERTICAL("Vertical"),

        /** Mirror across the horizontal axis (top <-> bottom). */
        HORIZONTAL("Horizontal"),

        /** Both axes, producing four copies. */
        QUADRANT("Quadrant"),

        /** Rotational symmetry around the canvas centre. */
        RADIAL("Radial"),
    }

    /**
     * Symmetry configuration.
     *
     * @param centreX / [centreY] axis position as a fraction of the canvas (`0.5` = centred).
     * @param offsetX / [offsetY] extra offset in *fractions of the canvas*, matching Procreate's
     *   "symmetry guide offset".
     * @param radialCount number of copies for [SymmetryType.RADIAL] (2 = like vertical).
     * @param radialAngleDegrees rotation of the whole radial pattern.
     * @param secondaryAxis adds a second mirrored axis (like a mandala with two guide pairs).
     */
    data class Settings(
        val type: SymmetryType = SymmetryType.NONE,
        val centreX: Float = 0.5f,
        val centreY: Float = 0.5f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val radialCount: Int = 6,
        val radialAngleDegrees: Float = 0f,
        val secondaryAxis: Boolean = false,
        /** When true the mirrored copies use their own independent colour jitter. */
        val independentColour: Boolean = false,
        /** Procreate's Rotational Symmetry: vertical, horizontal and quadrant copies turn about the axis point instead of mirroring. */
        val rotational: Boolean = false,
        /** Radial copies come in mirrored pairs, like a kaleidoscope or mandala, instead of only turning. */
        val mirroredSegments: Boolean = false,
        /** How symmetry's overlays are drawn. An independent style does not change paint pixels. */
        val lineStyle: GuideLineStyle = GuideLineStyle.SYMMETRY_DEFAULT,
    ) {
        fun isActive(): Boolean = type != SymmetryType.NONE

        /** How many brush stamps each pointer sample produces. */
        fun instanceCount(): Int =
            when (type) {
                SymmetryType.NONE -> 1
                SymmetryType.VERTICAL, SymmetryType.HORIZONTAL -> if (secondaryAxis) 4 else 2
                SymmetryType.QUADRANT -> 4
                SymmetryType.RADIAL -> radialCount.coerceIn(2, 32) * if (mirroredSegments) 2 else 1
            }
    }

    /** A mirrored pointer sample. */
    data class Instance(
        val x: Float,
        val y: Float,
        val rotationDegrees: Float,
    )

    /**
     * Transforms ([x], [y]) into every symmetric instance, including the original.
     *
     * @param width / [height] canvas size in pixels.
     * @param brushRotation the brush's own rotation, which is rotated along with each instance.
     */
    fun instances(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        settings: Settings,
        brushRotation: Float = 0f,
    ): List<Instance> {
        if (!settings.isActive()) return listOf(Instance(x, y, brushRotation))

        val axisX = (settings.centreX + settings.offsetX) * width
        val axisY = (settings.centreY + settings.offsetY) * height
        val centreX = width / 2f
        val centreY = height / 2f

        val results = mutableListOf<Instance>()

        fun mirrorVertical(
            px: Float,
            py: Float,
            rotation: Float,
        ): Instance = Instance(2f * axisX - px, py, -rotation)

        fun mirrorHorizontal(
            px: Float,
            py: Float,
            rotation: Float,
        ): Instance = Instance(px, 2f * axisY - py, -rotation)

        // Rotational symmetry: half turns about the axis point, or quarter turns for four copies.
        fun turned(quarters: Int): Instance {
            val dx = x - axisX
            val dy = y - axisY
            return when (quarters % 4) {
                1 -> Instance(axisX - dy, axisY + dx, brushRotation + QUARTER_TURN)
                2 -> Instance(axisX - dx, axisY - dy, brushRotation + 2 * QUARTER_TURN)
                3 -> Instance(axisX + dy, axisY - dx, brushRotation + 3 * QUARTER_TURN)
                else -> Instance(x, y, brushRotation)
            }
        }

        val rotationalCopies =
            when {
                !settings.rotational || settings.type == SymmetryType.RADIAL -> 0
                settings.type == SymmetryType.QUADRANT || settings.secondaryAxis -> 4
                else -> 2
            }
        if (rotationalCopies > 0) return List(rotationalCopies) { turned(it * 4 / rotationalCopies) }

        when (settings.type) {
            SymmetryType.NONE -> results += Instance(x, y, brushRotation)
            SymmetryType.VERTICAL -> {
                results += Instance(x, y, brushRotation)
                results += mirrorVertical(x, y, brushRotation)
                if (settings.secondaryAxis) {
                    results += mirrorHorizontal(x, y, brushRotation)
                    results += mirrorHorizontal(2f * axisX - x, y, -brushRotation)
                }
            }
            SymmetryType.HORIZONTAL -> {
                results += Instance(x, y, brushRotation)
                results += mirrorHorizontal(x, y, brushRotation)
                if (settings.secondaryAxis) {
                    results += mirrorVertical(x, y, brushRotation)
                    results += mirrorVertical(x, 2f * axisY - y, -brushRotation)
                }
            }
            SymmetryType.QUADRANT -> {
                results += Instance(x, y, brushRotation)
                results += mirrorVertical(x, y, brushRotation)
                results += mirrorHorizontal(x, y, brushRotation)
                results += Instance(2f * axisX - x, 2f * axisY - y, brushRotation)
            }
            SymmetryType.RADIAL -> {
                val count = settings.radialCount.coerceIn(2, 32)
                val baseAngle = Math.toRadians(settings.radialAngleDegrees.toDouble())
                val dx = x - centreX
                val dy = y - centreY
                // The mirror image across the pattern's first spoke, for kaleidoscope pairs.
                val doubled = 2.0 * baseAngle
                val mirrorX = (dx * cos(doubled) + dy * sin(doubled)).toFloat()
                val mirrorY = (dx * sin(doubled) - dy * cos(doubled)).toFloat()
                for (i in 0 until count) {
                    val angle = (2.0 * Math.PI * i) / count
                    val cosA = cos(angle).toFloat()
                    val sinA = sin(angle).toFloat()
                    results +=
                        Instance(
                            x = centreX + dx * cosA - dy * sinA,
                            y = centreY + dx * sinA + dy * cosA,
                            rotationDegrees = brushRotation + Math.toDegrees(angle).toFloat(),
                        )
                }
                if (settings.mirroredSegments) {
                    for (i in 0 until count) {
                        val angle = (2.0 * Math.PI * i) / count
                        val cosA = cos(angle).toFloat()
                        val sinA = sin(angle).toFloat()
                        results +=
                            Instance(
                                x = centreX + mirrorX * cosA - mirrorY * sinA,
                                y = centreY + mirrorX * sinA + mirrorY * cosA,
                                rotationDegrees = -brushRotation + Math.toDegrees(angle + doubled).toFloat(),
                            )
                    }
                }
            }
        }
        return results
    }

    /**
     * Mirrors a whole stroke. Used when symmetry is switched on after a stroke was drawn, and by
     * the tests that assert the replication matches stamp-for-stamp. Every point of a copy goes
     * through the map of its copy: a reflection for mirrored copies, a half turn for the diagonal
     * of quadrant symmetry and a rotation for radial copies. Points keep their pressure and timing.
     */
    fun mirrorStroke(
        stroke: Stroke,
        width: Int,
        height: Int,
        settings: Settings,
    ): List<Stroke> {
        if (!settings.isActive()) return listOf(stroke)
        val copies = copyMaps(width, height, settings)
        if (copies.size <= 1) return listOf(stroke)

        return copies.mapIndexed { index, map ->
            if (index == 0) {
                stroke
            } else {
                val transformed =
                    stroke.points.map { point ->
                        point.copy(x = map.x(point.x, point.y), y = map.y(point.x, point.y))
                    }
                stroke.copy(id = stroke.id + index, points = transformed)
            }
        }
    }

    /** One copy of the sample as an affine map: `x' = a·x + b·y + tx` and `y' = c·x + d·y + ty`. */
    private class CopyMap(
        private val a: Float,
        private val b: Float,
        private val c: Float,
        private val d: Float,
        private val tx: Float,
        private val ty: Float,
    ) {
        fun x(
            px: Float,
            py: Float,
        ): Float = a * px + b * py + tx

        fun y(
            px: Float,
            py: Float,
        ): Float = c * px + d * py + ty
    }

    /**
     * The copies [instances] produces, in the same order and count, expressed as affine maps so a
     * whole stroke can be carried through them. A mirrored copy is a reflection of the stroke, not
     * a translation of it.
     */
    private fun copyMaps(
        width: Int,
        height: Int,
        settings: Settings,
    ): List<CopyMap> {
        val axisX = (settings.centreX + settings.offsetX) * width
        val axisY = (settings.centreY + settings.offsetY) * height
        val identity = CopyMap(1f, 0f, 0f, 1f, 0f, 0f)
        val reflectX = CopyMap(-1f, 0f, 0f, 1f, 2f * axisX, 0f)
        val reflectY = CopyMap(1f, 0f, 0f, -1f, 0f, 2f * axisY)
        val halfTurn = CopyMap(-1f, 0f, 0f, -1f, 2f * axisX, 2f * axisY)
        return when (settings.type) {
            SymmetryType.NONE -> listOf(identity)
            SymmetryType.VERTICAL ->
                if (settings.secondaryAxis) listOf(identity, reflectX, reflectY, halfTurn) else listOf(identity, reflectX)
            SymmetryType.HORIZONTAL ->
                if (settings.secondaryAxis) listOf(identity, reflectY, reflectX, halfTurn) else listOf(identity, reflectY)
            SymmetryType.QUADRANT -> listOf(identity, reflectX, reflectY, halfTurn)
            SymmetryType.RADIAL -> {
                val count = settings.radialCount.coerceIn(2, 32)
                val baseAngle = Math.toRadians(settings.radialAngleDegrees.toDouble())
                val centreX = width / 2f
                val centreY = height / 2f
                List(count) { i ->
                    val angle = baseAngle + (2.0 * Math.PI * i) / count
                    val cosA = cos(angle).toFloat()
                    val sinA = sin(angle).toFloat()
                    // Rotation about the canvas centre: x' = R·x + (centre - R·centre).
                    CopyMap(
                        a = cosA,
                        b = -sinA,
                        c = sinA,
                        d = cosA,
                        tx = centreX - (cosA * centreX - sinA * centreY),
                        ty = centreY - (sinA * centreX + cosA * centreY),
                    )
                }
            }
        }
    }

    /** A guide line to draw as an overlay, in canvas pixel space. */
    data class GuideLine(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val isPrimary: Boolean = true,
    )

    /** Guide lines for the current symmetry, ready for the overlay to draw. */
    fun guideLines(
        width: Int,
        height: Int,
        settings: Settings,
    ): List<GuideLine> {
        if (!settings.isActive()) return emptyList()
        val axisX = (settings.centreX + settings.offsetX) * width
        val axisY = (settings.centreY + settings.offsetY) * height
        val centreX = width / 2f
        val centreY = height / 2f
        val lines = mutableListOf<GuideLine>()

        when (settings.type) {
            SymmetryType.NONE -> Unit
            SymmetryType.VERTICAL -> {
                lines += GuideLine(axisX, 0f, axisX, height.toFloat())
                if (settings.secondaryAxis) lines += GuideLine(0f, axisY, width.toFloat(), axisY, isPrimary = false)
            }
            SymmetryType.HORIZONTAL -> {
                lines += GuideLine(0f, axisY, width.toFloat(), axisY)
                if (settings.secondaryAxis) lines += GuideLine(axisX, 0f, axisX, height.toFloat(), isPrimary = false)
            }
            SymmetryType.QUADRANT -> {
                lines += GuideLine(axisX, 0f, axisX, height.toFloat())
                lines += GuideLine(0f, axisY, width.toFloat(), axisY)
            }
            SymmetryType.RADIAL -> {
                val count = settings.radialCount.coerceIn(2, 32)
                val radius = sqrt(centreX * centreX + centreY * centreY)
                for (i in 0 until count) {
                    val angle =
                        Math.toRadians(settings.radialAngleDegrees.toDouble()) +
                            (2.0 * Math.PI * i) / count
                    lines +=
                        GuideLine(
                            startX = centreX,
                            startY = centreY,
                            endX = centreX + radius * cos(angle).toFloat(),
                            endY = centreY + radius * sin(angle).toFloat(),
                            isPrimary = i % 2 == 0,
                        )
                }
            }
        }
        return lines
    }

    /** Snaps a point onto the nearest symmetry axis, used by the "snap to guide" option. */
    fun snapToAxis(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        settings: Settings,
        tolerance: Float,
    ): Pair<Float, Float> {
        if (!settings.isActive()) return x to y
        val axisX = (settings.centreX + settings.offsetX) * width
        val axisY = (settings.centreY + settings.offsetY) * height
        var resultX = x
        var resultY = y
        if (settings.type == SymmetryType.VERTICAL || settings.type == SymmetryType.QUADRANT) {
            if (kotlin.math.abs(x - axisX) <= tolerance) resultX = axisX
        }
        if (settings.type == SymmetryType.HORIZONTAL || settings.type == SymmetryType.QUADRANT) {
            if (kotlin.math.abs(y - axisY) <= tolerance) resultY = axisY
        }
        return resultX to resultY
    }

    /** Presets shown in the symmetry panel. */
    data class Preset(
        val name: String,
        val settings: Settings,
    )

    val PRESETS: List<Preset> =
        listOf(
            Preset("Vertical", Settings(type = SymmetryType.VERTICAL)),
            Preset("Horizontal", Settings(type = SymmetryType.HORIZONTAL)),
            Preset("Quadrant", Settings(type = SymmetryType.QUADRANT)),
            Preset("Mandala 6", Settings(type = SymmetryType.RADIAL, radialCount = 6)),
            Preset("Mandala 8", Settings(type = SymmetryType.RADIAL, radialCount = 8)),
            Preset("Mandala 12", Settings(type = SymmetryType.RADIAL, radialCount = 12)),
            Preset("Kaleidoscope 5", Settings(type = SymmetryType.RADIAL, radialCount = 5, radialAngleDegrees = 15f)),
            Preset("Double Axis", Settings(type = SymmetryType.VERTICAL, secondaryAxis = true)),
        )

    /** Radial symmetry count range exposed to the UI slider. */
    val RADIAL_RANGE = 2..32

    private const val QUARTER_TURN = 90f

    /** Clamps settings into their valid ranges (called by every setter in the ViewModel). */
    fun sanitize(settings: Settings): Settings =
        settings.copy(
            centreX = settings.centreX.coerceIn(-1f, 2f),
            centreY = settings.centreY.coerceIn(-1f, 2f),
            offsetX = settings.offsetX.coerceIn(-1f, 1f),
            offsetY = settings.offsetY.coerceIn(-1f, 1f),
            radialCount = settings.radialCount.coerceIn(RADIAL_RANGE.first, RADIAL_RANGE.last),
            radialAngleDegrees = ((settings.radialAngleDegrees % 360f) + 360f) % 360f,
            lineStyle = settings.lineStyle.sanitize(),
        )

    /** True when mirrored strokes would fall outside the canvas and are worth skipping. */
    fun isOutsideCanvas(
        instance: Instance,
        width: Int,
        height: Int,
        margin: Float = 64f,
    ): Boolean =
        instance.x < -margin ||
            instance.y < -margin ||
            instance.x > width + margin ||
            instance.y > height + margin

    /** Number of stamps per sample, for the performance readout. */
    fun stampsPerSample(settings: Settings): Int = max(1, min(settings.instanceCount(), 32))
}
