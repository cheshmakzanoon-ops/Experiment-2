package com.artflow.studio.core.color

/**
 * Gradient Map colour ramps: each stop is a position from shadows (0) to highlights (1) and an RGB
 * colour, and a pixel takes the ramp's colour at its luminance. Stops travel inside an adjustment's
 * parameter map as `stop_count`, `stop_N_pos` and `stop_N_rgb` (RGB fits a float exactly).
 */
object GradientMaps {
    data class Stop(
        val position: Float,
        val rgb: Int,
    )

    data class Ramp(
        val name: String,
        val stops: List<Stop>,
    )

    /** Ramps to start from, darkest stop first. */
    val PRESETS: List<Ramp> =
        listOf(
            ramp("Noir", 0x0A0A0F, 0x5A5A66, 0xF2F0EA),
            ramp("Heat", 0x14002A, 0x9B1B30, 0xF07F13, 0xFFF3B0),
            ramp("Ocean", 0x041627, 0x0F5E7A, 0x4FC3C8, 0xE8FBF6),
            ramp("Sepia", 0x1E120A, 0x7A5230, 0xE9D6B4),
            ramp("Dusk", 0x1B1035, 0x6B2D6E, 0xE0785F, 0xFCE3A5),
            ramp("Forest", 0x07150C, 0x2F5D2E, 0xA6C46A, 0xF1F5DC),
            ramp("Neon", 0x0B0221, 0x7A04EB, 0xFF2A6D, 0x05D9E8),
            ramp("Duotone", 0x2B2D6E, 0xF7C59F),
        )

    private fun ramp(
        name: String,
        vararg colors: Int,
    ): Ramp = Ramp(name, colors.mapIndexed { i, rgb -> Stop(i / (colors.size - 1f), rgb) })

    fun toParameters(ramp: Ramp): Map<String, Float> =
        buildMap {
            put(COUNT, ramp.stops.size.toFloat())
            ramp.stops.forEachIndexed { i, stop ->
                put("stop_${i}_pos", stop.position)
                put("stop_${i}_rgb", (stop.rgb and RGB).toFloat())
            }
        }

    /** The ramp stored in [parameters], sorted by position, or null when there is none. */
    fun fromParameters(parameters: Map<String, Float>): List<Stop>? {
        val count = parameters[COUNT]?.toInt()?.takeIf { it in 2..MAX_STOPS } ?: return null
        val stops =
            (0 until count).map { i ->
                val position = parameters["stop_${i}_pos"] ?: return null
                val rgb = parameters["stop_${i}_rgb"] ?: return null
                if (!position.isFinite() || !rgb.isFinite()) return null
                Stop(position.coerceIn(0f, 1f), rgb.toInt() and RGB)
            }
        return stops.sortedBy { it.position }
    }

    /** The ramp's RGB at [t] (0..1), blending linearly between neighbouring stops. */
    fun colorAt(
        stops: List<Stop>,
        t: Float,
    ): Int {
        val x = t.coerceIn(0f, 1f)
        if (x <= stops.first().position) return stops.first().rgb
        if (x >= stops.last().position) return stops.last().rgb
        val upper = stops.indexOfFirst { it.position >= x }
        val a = stops[upper - 1]
        val b = stops[upper]
        val span = b.position - a.position
        val f = if (span <= 0f) 0f else (x - a.position) / span

        fun channel(shift: Int): Int {
            val from = (a.rgb shr shift) and 0xFF
            val to = (b.rgb shr shift) and 0xFF
            return (from + (to - from) * f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    const val COUNT = "stop_count"
    private const val MAX_STOPS = 16
    private const val RGB = 0xFFFFFF
}
