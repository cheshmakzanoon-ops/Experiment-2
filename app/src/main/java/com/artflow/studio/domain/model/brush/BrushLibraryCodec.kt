package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/** Versioned, bounded storage. Unrecognized or damaged data fails closed instead of becoming empty. */
object BrushLibraryCodec {
    const val MAX_BRUSHES = 128
    const val MAX_NAME = 80

    // 128 source-preserving brushes can include ~200 metadata scalars each.
    // Still bounded: per-field and per-library validation prevents unbounded allocations.
    private const val MAX_CHARACTERS = 3_145_728

    private val GRAIN_IDS = setOf(null, "paper", "charcoal", "canvas", "fine", "blotch", "bristle", "halftone", "hatch", "speckle")

    /** Grains imported from photos; their tiles live beside the library. */
    private val IMPORTED_GRAIN = Regex("custom-[0-9a-f]{1,32}")
    private val AUXILIARY_CURVE_KEY = Regex("dynamicsPressure[A-Za-z0-9]{1,60}")

    // Native archives have 39 channels; four use ImportedPressure's dedicated fields.
    private const val MAX_AUXILIARY_CURVES = 35
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
        require(params.sourceImportVersion in 0..NATIVE_BRUSH_IMPORT_VERSION) { "Unsupported source brush import version" }
        // The renderer accepts sparse stamps up to four diameters apart and up to 32 dabs.
        // Built-in spray/stamp presets already use spacing and scatter beyond the slider's old range.
        require(params.size in 1f..512f && params.spacing in 0.01f..4f) { "Unsupported brush size or spacing" }
        require(params.count in 1..32 && params.scatter in 0f..4f) { "Unsupported brush scatter or dab count" }
        require(params.rotation in 0f..360f && params.textureRotation in 0f..360f) { "Unsupported brush rotation" }
        require(params.textureScale in 0.05f..8f) { "Unsupported grain scale" }
        require(params.textureId in GRAIN_IDS || params.textureId?.matches(IMPORTED_GRAIN) == true) { "Unsupported grain source" }
        require(params.roundness in 0.05f..1f) { "Unsupported tip roundness" }
        require(params.wetEdges in 0f..1f) { "Unsupported wet edges" }
        require(params.shapeId == null || params.shapeId.matches(IMPORTED_GRAIN)) { "Unsupported shape source" }
        params.dual?.let { dual ->
            require(dual.params.dual == null) { "A dual brush combines exactly two brushes" }
            validateParameters(dual.params)
        }
        val unitValues =
            listOf(
                params.opacity,
                params.taperStart,
                params.taperEnd,
                params.taperOpacity,
                params.falloff,
                params.hueJitter,
                params.saturationJitter,
                params.brightnessJitter,
                params.sizeJitter,
                params.opacityJitter,
                params.smoothing,
                params.wetMix,
                params.dilution,
                params.pull,
                params.countJitter,
                params.minOpacity,
                params.tipSharpness,
                params.touchTaperStart ?: 0f,
                params.touchTaperEnd ?: 0f,
                params.maxOpacity,
                params.wetBlur,
                params.wetAttack,
                params.wetCharge,
                params.wetDilution,
                params.wetPull,
                params.wetBlurJitter,
                params.bleed,
                params.flow,
                params.tiltInfluence,
                params.tiltOpacityInfluence ?: 0f,
                params.velocityToHue,
                params.secondaryPressure,
                params.secondaryJitter,
                params.burntEdges,
                params.grainDepth,
            )
        require(unitValues.all { it in 0f..1f }) { "Brush dynamics must be finite values between zero and one" }
        require(params.importedSourceScalars.size <= SourceBrushScalar.MAX_FIELDS) {
            "Too many source brush scalar settings"
        }
        require(
            params.importedSourceScalars.all { (name, field) ->
                SourceBrushScalar.validName(name) && field.isValid()
            },
        ) { "Invalid imported source brush scalar" }
        params.importedSourceColorData?.let { encoded ->
            require(encoded.length == 24) { "Invalid source brush colour data" }
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            require(bytes?.size == 16 && Base64.getEncoder().encodeToString(bytes) == encoded) {
                "Invalid source brush colour payload"
            }
        }
        require(params.importedSourceStructures.size <= SourceBrushStructuredField.MAX_OBJECTS) {
            "Too many source brush structured fields"
        }
        require(
            params.importedSourceStructures.all { (name, entry) ->
                entry.isValidFor(name)
            },
        ) { "Invalid source brush structured field" }
        require(params.importedAuxiliaryCurves.size <= MAX_AUXILIARY_CURVES) {
            "Too many source pressure curve components"
        }
        require(params.importedAuxiliaryCurves.keys.all { it.matches(AUXILIARY_CURVE_KEY) }) {
            "Invalid imported pressure curve field name"
        }
        val signedValues =
            listOf(
                params.pressureToSize,
                params.pressureToOpacity,
                params.pressureToFlow,
                params.velocityToSize,
                params.velocityToOpacity,
                params.grainBrightness,
                params.grainContrast,
            )
        require(signedValues.all { it in -1f..1f }) { "Signed brush dynamics must be finite values between minus one and one" }
        // Both pressure response types validate their control points during deserialization.
    }
}
