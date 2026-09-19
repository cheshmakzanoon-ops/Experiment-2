package com.artflow.studio.presentation.ui.components.brush

/** Navigation only: attribute choices never mutate brush parameters or document state. */
enum class BrushAttribute(
    val label: String,
) {
    ALL("All settings"),
    PROPERTIES("Properties"),
    STROKE("Stroke"),
    TAPER("Taper"),
    PRESSURE("Pressure"),
    GRAIN("Grain"),
    SCATTER("Scatter"),
    COLOUR("Colour jitter"),
    ROTATION("Rotation"),
    WET("Wet paint"),
    DYNAMICS("Speed & colour"),
    ;

    companion object {
        fun visible(attribute: BrushAttribute): List<BrushAttribute> =
            if (attribute == ALL) entries.filter { it != ALL } else listOf(attribute)
    }
}
