package com.artflow.studio.domain.model.layer

/**
 * Enumeration of available adjustment types for adjustment layers.
 *
 * Adjustment layers are stored as ordinary [Layer]s whose `adjustmentType` is set; the compositor
 * applies them non-destructively below the layer.
 */
enum class AdjustmentType(
    val displayName: String,
    val defaultParameters: Map<String, Float>,
    val parameterRanges: Map<String, ClosedFloatingPointRange<Float>>,
) {
    BRIGHTNESS_CONTRAST(
        displayName = "Brightness/Contrast",
        defaultParameters = mapOf("brightness" to 0f, "contrast" to 0f),
        parameterRanges =
            mapOf(
                "brightness" to -100f..100f,
                "contrast" to -100f..100f,
            ),
    ),
    HUE_SATURATION(
        displayName = "Hue/Saturation",
        defaultParameters = mapOf("hue" to 0f, "saturation" to 0f, "lightness" to 0f),
        parameterRanges =
            mapOf(
                "hue" to -180f..180f,
                "saturation" to -100f..100f,
                "lightness" to -100f..100f,
            ),
    ),
    COLOR_BALANCE(
        displayName = "Color Balance",
        defaultParameters =
            mapOf(
                "cyan_red" to 0f,
                "magenta_green" to 0f,
                "yellow_blue" to 0f,
            ),
        parameterRanges =
            mapOf(
                "cyan_red" to -100f..100f,
                "magenta_green" to -100f..100f,
                "yellow_blue" to -100f..100f,
            ),
    ),
    CURVES(
        displayName = "Curves",
        defaultParameters =
            mapOf(
                "point_0_x" to 0f,
                "point_0_y" to 0f,
                "point_1_x" to 64f,
                "point_1_y" to 64f,
                "point_2_x" to 128f,
                "point_2_y" to 128f,
                "point_3_x" to 192f,
                "point_3_y" to 192f,
                "point_4_x" to 255f,
                "point_4_y" to 255f,
            ),
        parameterRanges =
            mapOf(
                "point_0_x" to 0f..255f,
                "point_0_y" to 0f..255f,
                "point_1_x" to 0f..255f,
                "point_1_y" to 0f..255f,
                "point_2_x" to 0f..255f,
                "point_2_y" to 0f..255f,
                "point_3_x" to 0f..255f,
                "point_3_y" to 0f..255f,
                "point_4_x" to 0f..255f,
                "point_4_y" to 0f..255f,
            ),
    ),
    LEVELS(
        displayName = "Levels",
        defaultParameters =
            mapOf(
                "input_black" to 0f,
                "input_white" to 255f,
                "gamma" to 1.0f,
                "output_black" to 0f,
                "output_white" to 255f,
            ),
        parameterRanges =
            mapOf(
                "input_black" to 0f..255f,
                "input_white" to 0f..255f,
                "gamma" to 0.1f..10f,
                "output_black" to 0f..255f,
                "output_white" to 0f..255f,
            ),
    ),
    INVERT(
        displayName = "Invert",
        defaultParameters = emptyMap(),
        parameterRanges = emptyMap(),
    ),
    POSTERIZE(
        displayName = "Posterize",
        defaultParameters = mapOf("levels" to 4f),
        parameterRanges = mapOf("levels" to 2f..256f),
    ),
    SELECTIVE_COLOR(
        displayName = "Selective Color",
        defaultParameters =
            mapOf(
                "reds_cyan" to 0f,
                "reds_magenta" to 0f,
                "reds_yellow" to 0f,
                "reds_black" to 0f,
                "yellows_cyan" to 0f,
                "yellows_magenta" to 0f,
                "yellows_yellow" to 0f,
                "yellows_black" to 0f,
                "greens_cyan" to 0f,
                "greens_magenta" to 0f,
                "greens_yellow" to 0f,
                "greens_black" to 0f,
                "cyans_cyan" to 0f,
                "cyans_magenta" to 0f,
                "cyans_yellow" to 0f,
                "cyans_black" to 0f,
                "blues_cyan" to 0f,
                "blues_magenta" to 0f,
                "blues_yellow" to 0f,
                "blues_black" to 0f,
                "magentas_cyan" to 0f,
                "magentas_magenta" to 0f,
                "magentas_yellow" to 0f,
                "magentas_black" to 0f,
                "whites_cyan" to 0f,
                "whites_magenta" to 0f,
                "whites_yellow" to 0f,
                "whites_black" to 0f,
                "neutrals_cyan" to 0f,
                "neutrals_magenta" to 0f,
                "neutrals_yellow" to 0f,
                "neutrals_black" to 0f,
                "blacks_cyan" to 0f,
                "blacks_magenta" to 0f,
                "blacks_yellow" to 0f,
                "blacks_black" to 0f,
            ),
        parameterRanges = (
            buildMap {
                listOf("reds", "yellows", "greens", "cyans", "blues", "magentas", "whites", "neutrals", "blacks").forEach { color ->
                    listOf("cyan", "magenta", "yellow", "black").forEach { component ->
                        put("${color}_$component", -100f..100f)
                    }
                }
            }
        ),
    ),
    GRADIENT_MAP(
        displayName = "Gradient Map",
        defaultParameters = mapOf("gradient_start_hue" to 0f, "gradient_end_hue" to 360f),
        parameterRanges =
            mapOf(
                "gradient_start_hue" to 0f..360f,
                "gradient_end_hue" to 0f..360f,
            ),
    ),
    ;

    /**
     * Validate a parameter value is within range
     */
    fun validateParameter(
        key: String,
        value: Float,
    ): Float {
        val range = parameterRanges[key] ?: return value
        return value.coerceIn(range)
    }

    /**
     * Check if this adjustment type has parameters
     */
    fun hasParameters(): Boolean = defaultParameters.isNotEmpty()
}
