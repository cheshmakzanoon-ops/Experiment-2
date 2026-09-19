package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Versioned, bounded storage. Unrecognized or damaged data fails closed instead of becoming empty. */
object BrushLibraryCodec {
    const val MAX_BRUSHES = 128
    const val MAX_NAME = 80
    private const val MAX_CHARACTERS = 524_288
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class Archive(
        val version: Int,
        val brushes: List<SavedBrush>,
    )

    fun validName(name: String): Boolean = name.isNotBlank() && name.length <= MAX_NAME && name.none { it.isISOControl() }

    fun encode(brushes: List<SavedBrush>): String {
        validate(brushes)
        return json.encodeToString(Archive.serializer(), Archive(1, brushes)).also { require(it.length <= MAX_CHARACTERS) }
    }

    fun decode(encoded: String): List<SavedBrush> {
        require(encoded.length <= MAX_CHARACTERS) { "Saved brush data exceeds the supported size" }
        val archive = json.decodeFromString(Archive.serializer(), encoded)
        require(archive.version == 1) { "This saved brush library requires a different app version" }
        validate(archive.brushes)
        return archive.brushes.toList()
    }

    private fun validate(brushes: List<SavedBrush>) {
        require(brushes.size <= MAX_BRUSHES) { "The saved library holds up to $MAX_BRUSHES brushes" }
        require(brushes.map { it.id }.toSet().size == brushes.size) { "Saved brush identities must be unique" }
        brushes.forEach { brush ->
            require(brush.id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "Invalid saved brush identity" }
            require(validName(brush.name)) { "Use a brush name with 1 to $MAX_NAME visible characters" }
            validateParameters(brush.parameters)
        }
    }

    private fun validateParameters(params: BrushParams) {
        require(params.size in 1f..512f && params.spacing in 0.01f..1f) { "Unsupported brush size or spacing" }
        require(params.count in 1..5 && params.scatter in 0f..2f) { "Unsupported brush scatter or dab count" }
        require(params.rotation in 0f..360f && params.textureRotation in 0f..360f) { "Unsupported brush rotation" }
        require(params.textureScale in 0.25f..8f) { "Unsupported grain scale" }
        require(params.textureId in setOf(null, "paper", "charcoal", "canvas")) { "Unsupported grain source" }
        val unitValues =
            listOf(
                params.opacity,
                params.taperStart,
                params.taperEnd,
                params.pressureToSize,
                params.pressureToOpacity,
                params.hueJitter,
                params.saturationJitter,
                params.brightnessJitter,
                params.sizeJitter,
                params.opacityJitter,
                params.smoothing,
                params.wetMix,
                params.flow,
                params.tiltInfluence,
                params.velocityToSize,
                params.velocityToOpacity,
                params.velocityToHue,
            )
        require(unitValues.all { it in 0f..1f }) { "Brush dynamics must be finite values between zero and one" }
        // PressureResponse validates finite, monotone control points during deserialization.
    }
}
