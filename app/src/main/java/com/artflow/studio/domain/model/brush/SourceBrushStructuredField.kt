package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable

/**
 * A bounded NSKeyedArchiver settings object retained as uninterpreted source data.
 * Does not retain binary payloads, artwork, runtime code, or arbitrary object graphs.
 */
@Serializable
data class SourceBrushStructuredField(
    val className: String,
    val kind: Kind,
    val fields: Map<String, SourceBrushScalar> = emptyMap(),
    val items: List<SourceBrushScalar>? = null,
) {
    @Serializable
    enum class Kind {
        FIELDS,
        ARRAY,
    }

    fun isValid(): Boolean {
        if (!className.matches(CLASS_NAME)) return false
        return when (kind) {
            Kind.FIELDS ->
                items == null && fields.size in 1..MAX_FIELDS &&
                    fields.all { (name, value) ->
                        name.matches(FIELD_NAME) && value.isValid()
                    }
            Kind.ARRAY ->
                items != null && fields.isEmpty() && items.size <= MAX_ITEMS &&
                    items.all { scalar ->
                        scalar.isValid() &&
                            (scalar.type == SourceBrushScalar.Type.REAL || scalar.type == SourceBrushScalar.Type.INTEGER)
                    }
        }
    }

    /** The key and object kind must agree; a malformed archive cannot disguise a saved-size array as a roll object. */
    fun isValidFor(name: String): Boolean {
        if (!isValid()) return false
        return when (name) {
            in ROLL_NAMES -> kind == Kind.FIELDS && className == "ValkyrieBrushRollParameters"
            in ARRAY_NAMES -> kind == Kind.ARRAY && className == "NSArray"
            "creationDate" -> kind == Kind.FIELDS && className == "NSDate"
            else -> false
        }
    }

    companion object {
        val ROLL_NAMES: Set<String> =
            setOf(
                "attackRollParameters",
                "dynamicsRollBleedParameters",
                "dynamicsRollBrightnessParameters",
                "dynamicsRollHueParameters",
                "dynamicsRollOpacityParameters",
                "dynamicsRollSaturationParameters",
                "dynamicsRollSecondaryColorParameters",
                "dynamicsRollSizeParameters",
                "plotJitterLongitudinalRollParameters",
                "plotJitterRollParameters",
                "shapeCountRollParameters",
                "shapeScatterRollParameters",
            )
        val ARRAY_NAMES: Set<String> =
            setOf(
                "savedEraseOpacities",
                "savedEraseSizes",
                "savedPaintOpacities",
                "savedPaintSizes",
                "savedSmudgeOpacities",
                "savedSmudgeSizes",
            )
        val ALLOWED_NAMES: Set<String> = ROLL_NAMES + ARRAY_NAMES + "creationDate"
        const val MAX_OBJECTS = 19
        const val MAX_FIELDS = 8
        const val MAX_ITEMS = 16
        private val CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,95}")
        private val FIELD_NAME = Regex("[A-Za-z][A-Za-z0-9.]{0,95}")
    }
}
