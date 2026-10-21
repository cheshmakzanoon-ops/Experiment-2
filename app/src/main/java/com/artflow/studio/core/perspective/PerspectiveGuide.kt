package com.artflow.studio.core.perspective

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Perspective guides (Phase 34).
 *
 * Implements 1/2/3-point perspective plus an isometric grid, editable vanishing points, and
 * snapping that projects the pointer onto the nearest guide ray. All pure geometry, so it is
 * testable without a device.
 */
object PerspectiveGuide {
    enum class GuideType(
        val displayName: String,
    ) {
        NONE("None"),
        ONE_POINT("1-Point"),
        TWO_POINT("2-Point"),
        THREE_POINT("3-Point"),
        ISOMETRIC("Isometric"),
        GRID("2D Grid"),
    }

    /**
     * A vanishing point in normalised canvas coordinates (so it survives canvas resizes). Values
     * outside `0..1` are allowed and useful: a far-off vanishing point gives a nearly parallel
     * perspective, which is exactly what architectural drawing needs.
     */
    data class VanishingPoint(
        val x: Float,
        val y: Float,
        val enabled: Boolean = true,
    )

    data class Settings(
        val type: GuideType = GuideType.NONE,
        val horizonY: Float = 0.45f,
        /** 1, 2 or 3 points depending on [type]; extra entries are ignored. */
        val vanishingPoints: List<VanishingPoint> = DEFAULT_POINTS,
        /** Number of rays drawn per vanishing point. */
        val density: Int = 18,
        val snapEnabled: Boolean = true,
        /** Snap radius in pixels: a pointer this close to a ray is pulled onto it. */
        val snapRadius: Float = 24f,
        /** Snap strength `0..1`; 1 means "exactly on the ray". */
        val snapStrength: Float = 1f,
        /** Isometric grid: grid cell size in pixels. */
        val gridSpacing: Int = 64,
        /** 2D grid: rotation in degrees about the canvas centre. */
        val gridRotation: Float = 0f,
        val showHorizon: Boolean = true,
        /** Procreate's guide appearance: the lines' colour (RGB), opacity (0 to 1) and thickness (times a hairline). */
        val lineColor: Int = DEFAULT_LINE_COLOR,
        val lineOpacity: Float = 0.6f,
        val lineThickness: Float = 1f,
        /** UI-only guide style shared with symmetry, preserving older per-field settings. */
        val lineStyle: GuideLineStyle = GuideLineStyle(),
    ) {
        fun isActive(): Boolean = type != GuideType.NONE

        /** Number of vanishing points the current guide type uses. */
        fun activePointCount(): Int =
            when (type) {
                GuideType.ONE_POINT -> 1
                GuideType.TWO_POINT -> 2
                GuideType.THREE_POINT -> 3
                else -> 0
            }
    }

    /** A line in canvas pixel space. */
    data class GuideLine(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        /** Vanishing point index this ray belongs to, or -1 for the horizon / isometric grid. */
        val pointIndex: Int = -1,
    )

    /** The guide's default line colour, a soft green. */
    const val DEFAULT_LINE_COLOR = 0xFF88CC66.toInt()

    /** Thickest guide line, in hairlines. */
    const val MAX_LINE_THICKNESS = 4f

    val DEFAULT_POINTS: List<VanishingPoint> =
        listOf(
            VanishingPoint(0.30f, 0.45f),
            VanishingPoint(0.70f, 0.45f),
            VanishingPoint(0.50f, 0.95f),
        )

    /** Directions of the isometric families: vertical, and 30 degrees either side of horizontal. */
    private val ISOMETRIC_AXES: List<Float> = listOf(90f, 30f, -30f)

    /** Resolution-independent position of vanishing point [index]. */
    fun pointPosition(
        settings: Settings,
        index: Int,
        width: Int,
        height: Int,
    ): Pair<Float, Float> {
        val point = settings.vanishingPoints.getOrNull(index) ?: DEFAULT_POINTS[index.coerceIn(0, 2)]
        val y =
            if (index == settings.activePointCount() - 1 && settings.type == GuideType.THREE_POINT) {
                // The third point is a *vertical* vanishing point; keep it independent of the horizon.
                point.y
            } else {
                // Points 0 and 1 always sit on the horizon so the guide stays coherent when the
                // horizon is dragged.
                settings.horizonY
            }
        return (point.x * width) to (y * height)
    }

    /**
     * Rays for the current guide, clipped to the canvas rectangle.
     *
     * @param extended when true the rays are extended past the canvas edge by 20% so the overlay
     *   looks continuous while panning.
     */
    fun guideLines(
        settings: Settings,
        width: Int,
        height: Int,
        extended: Boolean = true,
    ): List<GuideLine> {
        if (!settings.isActive()) return emptyList()
        val lines = mutableListOf<GuideLine>()

        if (settings.showHorizon && settings.type in setOf(GuideType.ONE_POINT, GuideType.TWO_POINT, GuideType.THREE_POINT)) {
            val horizon = settings.horizonY * height
            lines += GuideLine(-width * 0.2f, horizon, width * 1.2f, horizon, pointIndex = -1)
        }

        if (settings.type == GuideType.ISOMETRIC) {
            return isometricLines(settings, width, height) + lines
        }
        if (settings.type == GuideType.GRID) return squareGridLines(settings, width, height)

        val count = settings.activePointCount()
        val density = settings.density.coerceIn(4, 72)
        val diagonal = sqrt((width * width + height * height).toFloat())

        for (index in 0 until count) {
            val (px, py) = pointPosition(settings, index, width, height)
            // Rays are distributed over a full turn; the ones that miss the canvas are cheap.
            for (i in 0 until density) {
                val angle = (2.0 * Math.PI * i) / density
                val dx = cos(angle).toFloat()
                val dy = sin(angle).toFloat()
                val length = diagonal * if (extended) 1.2f else 1f
                lines +=
                    GuideLine(
                        startX = px,
                        startY = py,
                        endX = px + dx * length,
                        endY = py + dy * length,
                        pointIndex = index,
                    )
            }
        }
        return lines
    }

    private fun isometricLines(
        settings: Settings,
        width: Int,
        height: Int,
    ): List<GuideLine> {
        val spacing = settings.gridSpacing.coerceIn(8, 512)
        val lines = mutableListOf<GuideLine>()
        val diagonal = sqrt((width * width + height * height).toFloat())

        for (angleDegrees in ISOMETRIC_AXES) {
            val radians = Math.toRadians(angleDegrees.toDouble())
            val dx = cos(radians).toFloat()
            val dy = sin(radians).toFloat()
            // Perpendicular offset step so the lines tile the canvas evenly.
            val perpX = -dy
            val perpY = dx
            val steps = (diagonal / spacing).toInt() + 2
            val centerX = width / 2f
            val centerY = height / 2f
            for (i in -steps..steps) {
                val offset = i * spacing
                val originX = centerX + perpX * offset
                val originY = centerY + perpY * offset
                lines +=
                    GuideLine(
                        startX = originX - dx * diagonal / 2f,
                        startY = originY - dy * diagonal / 2f,
                        endX = originX + dx * diagonal / 2f,
                        endY = originY + dy * diagonal / 2f,
                        pointIndex = -1,
                    )
            }
        }
        return lines
    }

    /**
     * Snaps a point onto the nearest perspective ray.
     *
     * @return the snapped point, or the original when nothing is within [Settings.snapRadius].
     */
    fun snap(
        x: Float,
        y: Float,
        settings: Settings,
        width: Int,
        height: Int,
    ): Pair<Float, Float> {
        if (!settings.isActive() || !settings.snapEnabled) return x to y
        if (settings.type == GuideType.ISOMETRIC) return snapToIsometricGrid(x, y, settings, width, height)
        if (settings.type == GuideType.GRID) return snapToSquareGrid(x, y, settings, width / 2f, height / 2f)

        var bestX = x
        var bestY = y
        var bestDistance = settings.snapRadius

        for (index in 0 until settings.activePointCount()) {
            val (px, py) = pointPosition(settings, index, width, height)
            // Project (x, y) onto the line through (px, py) in the pointer direction.
            val dx = x - px
            val dy = y - py
            val length = sqrt(dx * dx + dy * dy)
            if (length < 1f) continue

            // The nearest ray is the one whose angle is closest to the pointer's angle; the
            // perpendicular distance to that ray is 0, so we snap by rotating onto the ray grid.
            val density = settings.density.coerceIn(4, 72)
            val step = (2.0 * Math.PI) / density
            val angle = atan2(dy.toDouble(), dx.toDouble())
            val snappedAngle = (Math.round(angle / step) * step)
            val projectedX = px + (cos(snappedAngle) * length).toFloat()
            val projectedY = py + (sin(snappedAngle) * length).toFloat()

            // Perpendicular distance between the pointer and the snapped ray.
            val angleDelta = abs(angle - snappedAngle)
            val distance = (length * kotlin.math.sin(angleDelta)).toFloat()
            if (distance < bestDistance) {
                bestDistance = distance
                val strength = settings.snapStrength.coerceIn(0f, 1f)
                bestX = x + (projectedX - x) * strength
                bestY = y + (projectedY - y) * strength
            }
        }
        return bestX to bestY
    }

    /**
     * Square drawing grid centred on the canvas, [Settings.gridSpacing] pixels per cell, turned by
     * [Settings.gridRotation] about the centre.
     */
    private fun squareGridLines(
        settings: Settings,
        width: Int,
        height: Int,
    ): List<GuideLine> {
        val spacing = settings.gridSpacing.coerceIn(8, 512)
        if (settings.gridRotation % 360f != 0f) return rotatedGridLines(settings, width, height)
        val lines = mutableListOf<GuideLine>()
        // The grid is centred on the canvas, matching the square-grid snapping origin.
        val phaseX = (width / 2f) % spacing
        val phaseY = (height / 2f) % spacing
        var x = phaseX
        while (x <= width) {
            lines += GuideLine(x, 0f, x, height.toFloat(), pointIndex = -1)
            x += spacing
        }
        var y = phaseY
        while (y <= height) {
            lines += GuideLine(0f, y, width.toFloat(), y, pointIndex = -1)
            y += spacing
        }
        return lines
    }

    /**
     * Rotated 2D grid about the canvas centre: both orthogonal families retain the
     * same spacing and rotation as the user's guide settings.
     */
    private fun rotatedGridLines(settings: Settings, width: Int, height: Int): List<GuideLine> {
        val spacing = settings.gridSpacing.coerceIn(8, 512).toFloat()
        val angle = Math.toRadians(settings.gridRotation.toDouble())
        val centreX = width / 2f
        val centreY = height / 2f
        val diagonal = hypot(width.toFloat(), height.toFloat())
        val steps = ceil(diagonal / spacing).toInt() + 1
        return buildList {
            for (axis in 0..1) {
                val theta = angle + axis * Math.PI / 2.0
                val dx = cos(theta).toFloat()
                val dy = sin(theta).toFloat()
                val nx = -dy
                val ny = dx
                for (step in -steps..steps) {
                    val ox = centreX + nx * step * spacing
                    val oy = centreY + ny * step * spacing
                    val ray =
                        GuideLine(
                            ox - dx * diagonal, oy - dy * diagonal,
                            ox + dx * diagonal, oy + dy * diagonal,
                            pointIndex = -1,
                        )
                    clipToCanvas(ray, width, height)?.let(::add)
                }
            }
        }
    }

    /**
     * Pulls the pointer onto the nearest grid line (horizontal or vertical) within the snap radius.
     * The lines pass through ([originX], [originY]), the canvas centre that [squareGridLines] draws from.
     */
    fun snapToSquareGrid(
        x: Float,
        y: Float,
        settings: Settings,
        originX: Float,
        originY: Float,
    ): Pair<Float, Float> {
        val cell = settings.gridSpacing.coerceIn(8, 512)
        val spacing = cell.toFloat()
        val strength = settings.snapStrength.coerceIn(0f, 1f)
        val lineX = originX + Math.round((x - originX) / spacing) * spacing
        val lineY = originY + Math.round((y - originY) / spacing) * spacing
        val distanceX = abs(x - lineX)
        val distanceY = abs(y - lineY)
        return when {
            distanceX <= distanceY && distanceX <= settings.snapRadius -> (x + (lineX - x) * strength) to y
            distanceY <= settings.snapRadius -> x to (y + (lineY - y) * strength)
            else -> x to y
        }
    }

    /**
     * Snaps onto the nearest intersection of two isometric guide lines. The lines are the families
     * [isometricLines] draws, each spaced [Settings.gridSpacing] apart from the canvas centre.
     */
    fun snapToIsometricGrid(
        x: Float,
        y: Float,
        settings: Settings,
        width: Int,
        height: Int,
    ): Pair<Float, Float> {
        val spacing = settings.gridSpacing.coerceIn(8, 512).toDouble()
        val centreX = width / 2.0
        val centreY = height / 2.0
        val crossing = nearestIsometricCrossing(x - centreX, y - centreY, spacing)
        if (crossing.distance > settings.snapRadius) return x to y
        val snapX = (centreX + crossing.x).toFloat()
        val snapY = (centreY + crossing.y).toFloat()
        val strength = settings.snapStrength.coerceIn(0f, 1f)
        return (x + (snapX - x) * strength) to (y + (snapY - y) * strength)
    }

    /** A point where two isometric families cross, relative to the canvas centre. */
    private class Crossing(
        val x: Double,
        val y: Double,
        val distance: Double,
    )

    /** The crossing of any two isometric families nearest to the offset ([qx], [qy]) from the centre. */
    private fun nearestIsometricCrossing(
        qx: Double,
        qy: Double,
        spacing: Double,
    ): Crossing {
        var best = Crossing(0.0, 0.0, Double.MAX_VALUE)
        for (a in ISOMETRIC_AXES.indices) {
            for (b in a + 1 until ISOMETRIC_AXES.size) {
                val candidate = crossingNear(ISOMETRIC_AXES[a], ISOMETRIC_AXES[b], qx, qy, spacing)
                if (candidate.distance < best.distance) best = candidate
            }
        }
        return best
    }

    /** Two families meet where both offsets are whole multiples of the spacing; test the cells around the point. */
    private fun crossingNear(
        degreesA: Float,
        degreesB: Float,
        qx: Double,
        qy: Double,
        spacing: Double,
    ): Crossing {
        val (ax, ay) = isometricNormal(degreesA)
        val (bx, by) = isometricNormal(degreesB)
        val determinant = ax * by - ay * bx
        val ua = floor((ax * qx + ay * qy) / spacing).toInt()
        val ub = floor((bx * qx + by * qy) / spacing).toInt()
        var best = Crossing(0.0, 0.0, Double.MAX_VALUE)
        for (i in ua - 1..ua + 2) {
            for (j in ub - 1..ub + 2) {
                val offsetA = i * spacing
                val offsetB = j * spacing
                val px = (offsetA * by - ay * offsetB) / determinant
                val py = (ax * offsetB - bx * offsetA) / determinant
                val distance = hypot(px - qx, py - qy)
                if (distance < best.distance) best = Crossing(px, py, distance)
            }
        }
        return best
    }

    /** Unit normal of the isometric family whose lines run at [degrees]: offsets are measured along it. */
    private fun isometricNormal(degrees: Float): Pair<Double, Double> {
        val radians = Math.toRadians(degrees.toDouble())
        return -sin(radians) to cos(radians)
    }

    /** Moves the horizon and keeps the horizontal vanishing points on it. */
    fun withHorizon(
        settings: Settings,
        horizonY: Float,
    ): Settings = settings.copy(horizonY = horizonY.coerceIn(-1f, 2f))

    /**
     * Drags vanishing point [index] to canvas pixel ([x], [y]). Points on the horizon carry the
     * horizon with them; the third point of a three-point guide moves freely.
     */
    fun dragPoint(
        settings: Settings,
        index: Int,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
    ): Settings {
        val fx = x / width.coerceAtLeast(1)
        val fy = y / height.coerceAtLeast(1)
        val onHorizon = !(settings.type == GuideType.THREE_POINT && index == settings.activePointCount() - 1)
        val moved = movePoint(settings, index, fx, fy)
        return if (onHorizon) withHorizon(moved, fy) else moved
    }

    /** Moves vanishing point [index] to a normalised position. */
    fun movePoint(
        settings: Settings,
        index: Int,
        x: Float,
        y: Float,
    ): Settings {
        val points = settings.vanishingPoints.toMutableList()
        while (points.size <= index) {
            points += DEFAULT_POINTS[points.size % DEFAULT_POINTS.size]
        }
        points[index] = VanishingPoint(x.coerceIn(-2f, 3f), y.coerceIn(-2f, 3f), enabled = true)
        return settings.copy(vanishingPoints = points)
    }

    /** Adds or pads points so a 3-point guide always has three usable vanishing points. */
    fun ensurePoints(
        settings: Settings,
        count: Int,
    ): Settings {
        val points = settings.vanishingPoints.toMutableList()
        while (points.size < count) {
            points += DEFAULT_POINTS[points.size % DEFAULT_POINTS.size]
        }
        return settings.copy(vanishingPoints = points)
    }

    /** Line direction angle (degrees) of the nearest ray, for snapping brush direction. */
    fun nearestRayAngle(
        x: Float,
        y: Float,
        settings: Settings,
        width: Int,
        height: Int,
    ): Float? {
        if (!settings.isActive() || settings.type == GuideType.ISOMETRIC || settings.type == GuideType.GRID) return null
        var bestAngle: Float? = null
        var bestDelta = Float.MAX_VALUE
        for (index in 0 until settings.activePointCount()) {
            val (px, py) = pointPosition(settings, index, width, height)
            val angle = Math.toDegrees(atan2((y - py).toDouble(), (x - px).toDouble())).toFloat()
            val density = settings.density.coerceIn(4, 72)
            val step = 360f / density
            val snapped = Math.round(angle / step) * step
            val delta = abs(angle - snapped)
            if (delta < bestDelta) {
                bestDelta = delta
                bestAngle = snapped
            }
        }
        return bestAngle
    }

    /** Presets for the guide picker. */
    data class Preset(
        val name: String,
        val settings: Settings,
    )

    val PRESETS: List<Preset> =
        listOf(
            Preset("1-Point", Settings(type = GuideType.ONE_POINT, vanishingPoints = listOf(VanishingPoint(0.5f, 0.45f)))),
            Preset("2-Point", Settings(type = GuideType.TWO_POINT)),
            Preset("3-Point", Settings(type = GuideType.THREE_POINT)),
            Preset(
                "Wide 2-Point",
                Settings(type = GuideType.TWO_POINT, vanishingPoints = listOf(VanishingPoint(-0.4f, 0.5f), VanishingPoint(1.4f, 0.5f))),
            ),
            Preset("Isometric", Settings(type = GuideType.ISOMETRIC)),
            Preset("Isometric Fine", Settings(type = GuideType.ISOMETRIC, gridSpacing = 32)),
            Preset("2D Grid", Settings(type = GuideType.GRID, gridSpacing = 100)),
            Preset("2D Grid Fine", Settings(type = GuideType.GRID, gridSpacing = 40)),
        )

    /** Default snap radius range exposed to the UI. */
    val SNAP_RADIUS_RANGE = 4f..96f

    /** Density range exposed to the UI slider. */
    val DENSITY_RANGE = 4..72

    /** Clamp helper used by every setter. */
    fun sanitize(settings: Settings): Settings =
        settings.copy(
            horizonY = settings.horizonY.coerceIn(-1f, 2f),
            density = settings.density.coerceIn(DENSITY_RANGE.first, DENSITY_RANGE.last),
            snapRadius = settings.snapRadius.coerceIn(SNAP_RADIUS_RANGE),
            snapStrength = settings.snapStrength.coerceIn(0f, 1f),
            gridSpacing = settings.gridSpacing.coerceIn(8, 512),
            gridRotation = if (settings.gridRotation.isFinite()) settings.gridRotation.mod(360f) else 0f,
            lineStyle = settings.lineStyle.sanitize(),
        )

    /** Human-readable description for the guide overlay's info pill. */
    fun describe(settings: Settings): String =
        when (settings.type) {
            GuideType.NONE -> "Perspective off"
            GuideType.ONE_POINT -> "1-point perspective"
            GuideType.TWO_POINT -> "2-point perspective"
            GuideType.THREE_POINT -> "3-point perspective"
            GuideType.ISOMETRIC -> "Isometric grid (${settings.gridSpacing}px)"
            GuideType.GRID -> "2D grid (${settings.gridSpacing}px)"
        }

    /** Clips a ray to the canvas rectangle; returns null when it completely misses. */
    fun clipToCanvas(
        line: GuideLine,
        width: Int,
        height: Int,
    ): GuideLine? {
        val dx = line.endX - line.startX
        val dy = line.endY - line.startY
        var tMin = 0f
        var tMax = 1f
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(line.startX, width - line.startX, line.startY, height - line.startY)

        for (i in 0 until 4) {
            if (abs(p[i]) < 1e-6f) {
                if (q[i] < 0f) return null
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0f) {
                    tMin = max(tMin, t)
                } else {
                    tMax = min(tMax, t)
                }
                if (tMin > tMax) return null
            }
        }
        return GuideLine(
            startX = line.startX + tMin * dx,
            startY = line.startY + tMin * dy,
            endX = line.startX + tMax * dx,
            endY = line.startY + tMax * dy,
            pointIndex = line.pointIndex,
        )
    }
}
