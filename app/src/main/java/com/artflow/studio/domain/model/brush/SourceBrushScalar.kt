package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable

/**
 * Type-tagged primitive values from a source Brush.archive settings dictionary.
 *
 * These are preserved for interoperability, not interpreted as renderer parameters.
 * Binary resources, NSKeyedArchiver class objects and nested structures deliberately
 * are not retained here; curves have their own validated representation.
 */
@Serializable
data class SourceBrushScalar(
    val type: Type,
    val value: String,
) {
    @Serializable
    enum class Type { BOOLEAN, INTEGER, REAL, TEXT }

    fun isValid(): Boolean {
        if (value.length > MAX_VALUE_LENGTH) return false
        return when (type) {
            Type.BOOLEAN -> value == "true" || value == "false"
            Type.INTEGER -> value.toLongOrNull() != null
            Type.REAL -> value.toDoubleOrNull()?.isFinite() == true
            Type.TEXT -> value.none { it == '\u0000' }
        }
    }

    companion object {
        const val MAX_FIELDS = 256
        const val MAX_VALUE_LENGTH = 256
        private val NAME = Regex("[A-Za-z][A-Za-z0-9]{0,95}")

        fun validName(name: String): Boolean = name.matches(NAME)

        fun capture(value: Any?): SourceBrushScalar? =
            when (value) {
                is Boolean -> SourceBrushScalar(Type.BOOLEAN, value.toString())
                is Byte, is Short, is Int, is Long -> SourceBrushScalar(Type.INTEGER, value.toString())
                is Float -> value.takeIf { it.isFinite() }?.let { SourceBrushScalar(Type.REAL, it.toString()) }
                is Double -> value.takeIf { it.isFinite() }?.let { SourceBrushScalar(Type.REAL, it.toString()) }
                is String -> value.takeIf { it.length <= MAX_VALUE_LENGTH && '\u0000' !in it }
                    ?.let { SourceBrushScalar(Type.TEXT, it) }
                else -> null
            }
    }
}
