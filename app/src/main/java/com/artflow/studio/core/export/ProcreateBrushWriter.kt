package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.SourceBrushScalar
import com.artflow.studio.domain.model.brush.SourceBrushStructuredField
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes Procreate brushes, the inverse of [ProcreateBrush]: a `.brush` is a zip holding the brush's
 * settings as a keyed archive ("Brush.archive") with its shape and grain images as PNG ("Shape.png",
 * "Grain.png"), and a `.brushset` is a zip of such folders with "brushset.plist" listing them in order.
 *
 * Each setting is written on the scale [ProcreateBrush.read] maps it back from, so a brush read back has
 * the same value within float rounding wherever the value is in range. Compound brushes retain a
 * second settings archive and its own images in Sub01, matching the observed source structure.
 * Original dual blend identifiers survive unchanged. New or edited local combine modes use the
 * existing approximate document blend identifiers; their equivalence to native dual math is unverified.
 * Renderer texture rotation has no verified source setting and is not reconstructed.
 * Known pressure curve graphs are serialized as ValkyrieMagnitudinalCurve archives; source interpolation
 * and pixel parity are not verified. Signed pressure/tilt scalar values survive in the archive.
 * Fixed tip flips are baked into an available shape image; the imported flags normalize to false,
 * while per-dab mirror jitter stays independent. A missing shape image cannot be reconstructed.
 */
object ProcreateBrushWriter {
    /** One brush to write: its name, settings, and its shape and grain tiles (null when it has none). */
    class Brush(
        val name: String,
        val parameters: BrushParams,
        val shape: CustomGrains.Tile? = null,
        val grain: CustomGrains.Tile? = null,
        /** Explicit assets for the second component; otherwise resolve its content-addressed image IDs. */
        val secondaryShape: CustomGrains.Tile? = null,
        val secondaryGrain: CustomGrains.Tile? = null,
    )

    /** A `.brush` file: the brush's archive and images at the top of the zip. */
    fun brush(brush: Brush): ByteArray = zipOf { out -> brushFolder(out, "", brush) }

    /** A `.brushset` file: [brushes] in order, each in its own folder, listed by name in "brushset.plist". */
    fun brushset(
        name: String,
        brushes: List<Brush>,
    ): ByteArray {
        require(brushes.isNotEmpty()) { "A brush set needs at least one brush" }
        val folders = brushes.map { UUID.randomUUID().toString().uppercase() }
        return zipOf { out ->
            entry(out, "brushset.plist", BinaryPlist.write(mapOf("name" to name, "brushes" to folders)))
            brushes.zip(folders).forEach { (brush, folder) -> brushFolder(out, "$folder/", brush) }
        }
    }

    private fun brushFolder(
        out: ZipOutputStream,
        prefix: String,
        brush: Brush,
    ) {
        val dual = brush.parameters.dual
        require(dual != null || (brush.secondaryShape == null && brush.secondaryGrain == null)) {
            "Secondary images need a dual brush"
        }
        require(dual?.params?.dual == null) { "A dual brush combines exactly two brushes" }
        componentFolder(out, prefix, brush)
        if (dual == null) return
        val sourceName = dual.params.importedSourceScalars["name"]
        val name = sourceName?.takeIf { it.type == SourceBrushScalar.Type.TEXT && it.value.isNotBlank() }?.value ?: brush.name
        val secondary =
            Brush(
                name,
                dual.params,
                brush.secondaryShape ?: CustomGrains.get(dual.params.shapeId),
                brush.secondaryGrain ?: CustomGrains.get(dual.params.textureId),
            )
        componentFolder(out, prefix + "Sub01/", secondary)
    }

    private fun componentFolder(
        out: ZipOutputStream,
        prefix: String,
        brush: Brush,
    ) {
        entry(out, prefix + ARCHIVE, archive(brush))
        brush.shape?.let { entry(out, prefix + SHAPE, png(it, brush.parameters.tipFlipX, brush.parameters.tipFlipY)) }
        brush.grain?.let { entry(out, prefix + GRAIN, png(it)) }
    }

    /** The brush's settings as a keyed archive of class SilicaBrush, the form [ProcreateBrush.read] reads. */
    private fun archive(brush: Brush): ByteArray {
        val keyed = KeyedArchiveBuilder()
        // REA's reference archive identifies these as ValkyrieMagnitudinalCurve objects
        // containing NSArrays of point strings. Preserve the class and all four primary
        // curves as well as the supported auxiliary keys (no native interpolation inferred).
        val primary = primaryCurves(brush.parameters).filterValues { it != null }
        val curves =
            primary + brush.parameters.importedAuxiliaryCurves.filterKeys { it in ProcreateBrush.AUXILIARY_CURVE_KEYS }
        val keyedCurves =
            curves.mapValues { (_, entry) ->
                val response = requireNotNull(entry)
                val points = keyed.array(response.points.map { point -> keyed.add("{${point.input}, ${point.output}}") })
                keyed.instance("ValkyrieMagnitudinalCurve", mapOf("points" to points))
            }
        val originalScalars = brush.parameters.importedSourceScalars
        require(originalScalars.size <= SourceBrushScalar.MAX_FIELDS) { "Too many source brush scalar settings" }
        require(originalScalars.keys.all(SourceBrushScalar::validName)) { "Invalid source brush scalar field name" }
        // An embedded shape is the authoritative, possibly edited source. Do not leave a stale
        // bundle path pointing at the unflipped original. Native loader precedence is unverified.
        val preservedScalars =
            originalScalars.filterKeys { keepSourceScalar(brush, it) }.mapValues { (_, scalar) ->
                require(scalar.isValid()) { "Invalid stored source brush scalar" }
                when (scalar.type) {
                    SourceBrushScalar.Type.BOOLEAN -> scalar.value == "true"
                    SourceBrushScalar.Type.INTEGER -> requireNotNull(scalar.value.toLongOrNull())
                    SourceBrushScalar.Type.REAL -> requireNotNull(scalar.value.toDoubleOrNull())
                    SourceBrushScalar.Type.TEXT -> keyed.add(scalar.value)
                }
            }
        val originalStructures = brush.parameters.importedSourceStructures
        require(originalStructures.size <= SourceBrushStructuredField.MAX_OBJECTS) {
            "Too many source brush structured settings"
        }
        require(
            originalStructures.all { (name, data) ->
                data.isValidFor(name)
            },
        ) { "Invalid source brush structured setting" }
        val preservedStructures =
            originalStructures.mapValues { (_, data) ->
                val values: Map<String, Any?> =
                    when (data.kind) {
                        SourceBrushStructuredField.Kind.FIELDS ->
                            data.fields.mapValues { (_, scalar) -> sourceValue(keyed, scalar) }
                        SourceBrushStructuredField.Kind.ARRAY ->
                            mapOf(
                                "NS.objects" to
                                    requireNotNull(data.items).map { scalar ->
                                        keyed.add(sourceValue(keyed, scalar))
                                    },
                            )
                    }
                keyed.instance(data.className, values)
            }
        val originalColor =
            brush.parameters.importedSourceColorData?.let { encoded ->
                require(encoded.length == 24) { "Invalid source brush colour payload size" }
                val data = Base64.getDecoder().decode(encoded)
                require(data.size == 16 && Base64.getEncoder().encodeToString(data) == encoded) {
                    "Invalid source brush colour payload"
                }
                mapOf("color" to keyed.add(data))
            } ?: emptyMap()
        // Loaded tiles already include source inversion. If a tile is unavailable, preserve the
        // original flag alongside its resource reference rather than claiming to have normalized it.
        val imageSettings =
            buildMap<String, Any> {
                if (brush.shape != null) put("shapeInverted", false)
                if (brush.grain != null) put("textureInverted", false)
            }
        // Editable ArtFlow settings win for known scalar fields; source-only
        // primitive, structured and bounded 16-byte color data stay with the brush.
        val root =
            preservedScalars + preservedStructures + originalColor +
                mapOf("\$class" to keyed.classOf(BRUSH_CLASS), "name" to keyed.add(brush.name)) +
                sourceAwareSettings(brush.parameters) + keyedCurves + imageSettings + dualSettings(brush.parameters)
        return keyed.encode(root)
    }

    private fun keepSourceScalar(
        brush: Brush,
        name: String,
    ): Boolean =
        when (name) {
            "bundledShapePath" -> brush.shape == null
            "bundledGrainPath" -> brush.grain == null
            // An edited combine mode must not keep an unrelated extended ID that could override it.
            "dualBlendModeExtended" -> brush.parameters.dual?.let { retainedDualMode(it) != null } ?: true
            else -> true
        }

    private fun dualSettings(parameters: BrushParams): Map<String, Any> {
        val dual = parameters.dual ?: return emptyMap()
        val retained = retainedDualMode(dual)
        // The unchanged extended field is authoritative; preserve its distinct legacy fallback too.
        if (retained != null && "dualBlendModeExtended" in parameters.importedSourceScalars) return emptyMap()
        val mode =
            retained ?: when (dual.mode) {
                DualBrush.Mode.MULTIPLY -> 1
                DualBrush.Mode.ADD -> 3
                DualBrush.Mode.SUBTRACT -> 7
            }
        return mapOf("dualBlendMode" to mode.toLong())
    }

    /** Preserve a source identifier only while its imported local approximation remains selected. */
    private fun retainedDualMode(dual: DualBrush): Int? {
        val source = dual.sourceBlendMode ?: return null
        val approximation =
            when (source) {
                3 -> DualBrush.Mode.ADD
                7 -> DualBrush.Mode.SUBTRACT
                else -> DualBrush.Mode.MULTIPLY
            }
        return source.takeIf { dual.mode == approximation }
    }

    /**
     * Prevent an unedited source brush from losing original setting scales just because
     * ArtFlow's renderer clamps them. For every translated field, use the actual importer
     * as the baseline; editing a field makes ArtFlow's new value authoritative.
     *
     * E.g. an original plotSpacing=0 maps to 0.01f in ArtFlow. Exporting an unedited
     * brush should retain 0 rather than pretending that source value was 0.01.
     */
    private fun sourceAwareSettings(params: BrushParams): Map<String, Any?> {
        val rewritten = settings(params)
        val raw = params.importedSourceScalars
        if (raw.isEmpty()) return rewritten

        // The same source decoding path defines the baseline; do not implement a
        // second, subtly different set of scaling/clamping formulas here.
        val input = raw.mapValues { (_, value) -> sourceLiteral(value) }
        val imported = importBaseline(params, input)
        val keepOriginal = mutableSetOf<String>()

        fun retain(
            unchanged: Boolean,
            vararg fields: String,
        ) {
            if (unchanged) fields.forEach(keepOriginal::add)
        }

        retain(params.size == imported.size, "maxSize", "paintSize")
        retain(params.opacity == imported.opacity, "maxOpacity", "paintOpacity")
        retain(params.spacing == imported.spacing, "plotSpacing")
        retain(params.textureScale == imported.textureScale, "textureScale")
        retain(params.count == imported.count, "shapeCount")
        retain(params.countJitter == imported.countJitter, "shapeCountJitter")
        retain(params.scatter == imported.scatter, "plotJitter")
        retain(params.rotation == imported.rotation, "shapeAngle")
        retain(params.roundness == imported.roundness, "shapeRoundness")
        retain(params.flow == imported.flow, "dynamicsGlazedFlow")
        retain(params.smoothing == imported.smoothing, "plotSmoothing", "plotMovingAverageStabilization", "plotFFTSmoothingAmount")
        retain(params.taperStart == imported.taperStart, "pencilTaperStartLength")
        retain(params.taperEnd == imported.taperEnd, "pencilTaperEndLength")
        retain(params.taperOpacity == imported.taperOpacity, "pencilTaperOpacity")
        retain(params.pressureToSize == imported.pressureToSize, "dynamicsPressureSize")
        retain(params.pressureToFlow == imported.pressureToFlow, "dynamicsPressureOpacity")
        retain(params.pressureToOpacity == imported.pressureToOpacity, "dynamicsPressureOpacityTransfer")
        retain(params.velocityToSize == imported.velocityToSize, "dynamicsSpeedSize")
        retain(params.velocityToOpacity == imported.velocityToOpacity, "dynamicsSpeedOpacity")
        retain(params.wetDilution == imported.wetDilution, "dynamicsMix")
        retain(params.wetAttack == imported.wetAttack, "dynamicsPressureMix")
        retain(params.wetPull == imported.wetPull, "dynamicsWetAccumulation")
        retain(params.bleed == imported.bleed, "dynamicsPressureBleed")
        retain(params.wetBlur == imported.wetBlur, "dynamicsBlur")
        retain(params.wetBlurJitter == imported.wetBlurJitter, "dynamicsBlurJitter")
        retain(params.wetEdges == imported.wetEdges, "wetEdgesAmount")
        retain(params.burntEdges == imported.burntEdges, "burntEdgesAmount")
        retain(params.grainDepth == imported.grainDepth, "grainDepth")
        retain(params.falloff == imported.falloff, "dynamicsFalloff")
        retain(params.tipFlipXJitter == imported.tipFlipXJitter, "shapeFlipXJitter")
        retain(params.tipFlipYJitter == imported.tipFlipYJitter, "shapeFlipYJitter")
        retain(params.tipRandomized == imported.tipRandomized, "shapeRandomise")
        retain(params.grainMoving == imported.grainMoving, "textureMovement")
        retain(params.buildUp == imported.buildUp, "renderingRecursiveMixing")
        retain(params.tiltInfluence == imported.tiltInfluence, "dynamicsTiltSize")
        retain(params.tiltOpacityInfluence == imported.tiltOpacityInfluence, "dynamicsTiltOpacity")
        retain(params.tiltToRotation == imported.tiltToRotation, "shapeAzimuth", "oriented")
        retain(params.hueJitter == imported.hueJitter, "dynamicsJitterHue")
        retain(params.saturationJitter == imported.saturationJitter, "dynamicsJitterSaturation")
        retain(params.brightnessJitter == imported.brightnessJitter, "dynamicsJitterLightness", "dynamicsJitterDarkness")
        retain(params.sizeJitter == imported.sizeJitter, "dynamicsJitterSize")
        retain(params.opacityJitter == imported.opacityJitter, "dynamicsJitterOpacity")
        retain(params.secondaryPressure == imported.secondaryPressure, "dynamicsPressureSecondaryColor")
        retain(params.secondaryJitter == imported.secondaryJitter, "jitterSecondary")
        val dynamics = params.colorDynamics
        val baseline = imported.colorDynamics
        retain(dynamics.pressureHue == baseline.pressureHue, "dynamicsPressureHue")
        retain(dynamics.pressureSaturation == baseline.pressureSaturation, "dynamicsPressureSaturation")
        retain(dynamics.pressureBrightness == baseline.pressureBrightness, "dynamicsPressureBrightness")
        retain(dynamics.tiltHue == baseline.tiltHue, "dynamicsTiltHue")
        retain(dynamics.tiltSaturation == baseline.tiltSaturation, "dynamicsTiltSaturation")
        retain(dynamics.tiltBrightness == baseline.tiltBrightness, "dynamicsTiltBrightness")

        // Extended blend mode can have a native numeric ID ArtFlow does not yet know.
        // Do not invent an extra extendedBlend=Normal when the original carried only
        // the legacy blendMode key; adding one would shadow the native value on import.
        if (params.blendMode == imported.blendMode &&
            ("extendedBlend" in raw || "blendMode" in raw)
        ) {
            keepOriginal.add("extendedBlend")
        }

        return rewritten.filterKeys { key -> key !in keepOriginal }
    }

    /**
     * A saved library from before the native contract audit must keep its existing painting settings.
     * Compare edits with that importer interpretation; an untouched local default must not erase a
     * retained source field that the earlier importer did not understand. Re-import explicitly opts
     * into the corrected channel interpretation and records the current version.
     */
    private fun importBaseline(
        params: BrushParams,
        source: Map<String, Any>,
    ): BrushParams {
        val current = ProcreateBrush.parameters(source)
        if (!isLegacySource(params)) return current
        return current.copy(
            pressureToOpacity = current.pressureToFlow,
            pressureToFlow = 0f,
            wetMix = current.wetDilution,
            wetDilution = 0f,
            wetAttack = 1f,
            wetPull = 0f,
            bleed = 0f,
            wetBlur = 0f,
            wetBlurJitter = 0f,
            countJitter = 0f,
            textureScale = current.textureScale.coerceAtLeast(LEGACY_MIN_GRAIN_SCALE),
        )
    }

    /** Legacy opacity graphs were archived flow graphs; actual opacity lived among the auxiliary keys. */
    private fun primaryCurves(params: BrushParams): Map<String, PressureMapping?> {
        val pressure = params.importedPressure
        val legacy = isLegacySource(params)
        return mapOf(
            "dynamicsPressureOverall" to pressure?.overall,
            "dynamicsPressureSizeCurve" to pressure?.size,
            "dynamicsPressureTransferModulationCurve" to
                if (legacy) params.importedAuxiliaryCurves["dynamicsPressureTransferModulationCurve"] else pressure?.opacity,
            "dynamicsPressureOpacityCurve" to if (legacy) pressure?.flow ?: pressure?.opacity else pressure?.flow,
        )
    }

    private fun isLegacySource(params: BrushParams): Boolean = params.sourceImportVersion == 0 && params.importedSourceScalars.isNotEmpty()

    /** A typed literal for baseline import (not a keyed reference to another object). */
    private fun sourceLiteral(value: SourceBrushScalar): Any {
        require(value.isValid()) { "Invalid saved source brush scalar" }
        return when (value.type) {
            SourceBrushScalar.Type.BOOLEAN -> value.value == "true"
            SourceBrushScalar.Type.INTEGER -> requireNotNull(value.value.toLongOrNull())
            SourceBrushScalar.Type.REAL -> requireNotNull(value.value.toDoubleOrNull())
            SourceBrushScalar.Type.TEXT -> value.value
        }
    }

    private fun sourceValue(
        keyed: KeyedArchiveBuilder,
        scalar: SourceBrushScalar,
    ): Any {
        require(scalar.isValid()) { "Invalid source brush scalar" }
        return when (scalar.type) {
            SourceBrushScalar.Type.BOOLEAN -> scalar.value == "true"
            SourceBrushScalar.Type.INTEGER -> requireNotNull(scalar.value.toLongOrNull())
            SourceBrushScalar.Type.REAL -> requireNotNull(scalar.value.toDoubleOrNull())
            SourceBrushScalar.Type.TEXT -> keyed.add(scalar.value)
        }
    }

    private fun settings(p: BrushParams): Map<String, Any?> =
        mapOf(
            "maxSize" to (p.size / BASE_SIZE).coerceIn(MIN_SCALE, MAX_SCALE).toDouble(),
            "paintSize" to 1.0,
            "maxOpacity" to p.opacity.coerceIn(MIN_OPACITY, 1f).toDouble(),
            "paintOpacity" to 1.0,
            "plotSpacing" to p.spacing.coerceIn(MIN_SPACING, 1f).toDouble(),
            "plotJitter" to unit(p.scatter),
            "shapeCount" to unit((p.count - 1) / (MAX_COUNT - 1f)),
            "shapeCountJitter" to unit(p.countJitter),
            "shapeAngle" to Math.toRadians(p.rotation.toDouble()),
            "pencilTaperStartLength" to unit(p.taperStart),
            "pencilTaperEndLength" to unit(p.taperEnd),
            "pencilTaperOpacity" to unit(p.taperOpacity),
            "dynamicsPressureSize" to p.pressureToSize.coerceIn(-1f, 1f).toDouble(),
            "dynamicsPressureOpacity" to p.pressureToFlow.coerceIn(-1f, 1f).toDouble(),
            "dynamicsPressureOpacityTransfer" to p.pressureToOpacity.coerceIn(-1f, 1f).toDouble(),
            "dynamicsPressureHue" to signed(p.colorDynamics.pressureHue),
            "dynamicsPressureSaturation" to signed(p.colorDynamics.pressureSaturation),
            "dynamicsPressureBrightness" to signed(p.colorDynamics.pressureBrightness),
            "dynamicsTiltHue" to signed(p.colorDynamics.tiltHue),
            "dynamicsTiltSaturation" to signed(p.colorDynamics.tiltSaturation),
            "dynamicsTiltBrightness" to signed(p.colorDynamics.tiltBrightness),
            "dynamicsJitterHue" to unit(p.hueJitter),
            "dynamicsJitterSaturation" to unit(p.saturationJitter),
            "dynamicsJitterLightness" to unit(p.brightnessJitter),
            "dynamicsJitterSize" to unit(p.sizeJitter),
            "dynamicsJitterOpacity" to unit(p.opacityJitter),
            "plotSmoothing" to unit(p.smoothing),
            "dynamicsMix" to unit(p.wetDilution),
            "dynamicsPressureMix" to unit(p.wetAttack),
            "dynamicsWetAccumulation" to unit(p.wetPull),
            "dynamicsPressureBleed" to unit(p.bleed),
            "dynamicsBlur" to unit(p.wetBlur),
            "dynamicsBlurJitter" to unit(p.wetBlurJitter),
            "dynamicsGlazedFlow" to p.flow.coerceIn(MIN_OPACITY, 1f).toDouble(),
            "textureScale" to p.textureScale.coerceIn(MIN_SCALE, MAX_SCALE).toDouble(),
            "dynamicsTiltSize" to unit(p.tiltInfluence),
            "dynamicsTiltOpacity" to unit(p.tiltOpacityInfluence ?: p.tiltInfluence),
            "shapeAzimuth" to p.tiltToRotation,
            "dynamicsSpeedSize" to p.velocityToSize.coerceIn(-1f, 1f).toDouble(),
            "dynamicsSpeedOpacity" to p.velocityToOpacity.coerceIn(-1f, 1f).toDouble(),
            "shapeRoundness" to p.roundness.coerceIn(MIN_ROUNDNESS, 1f).toDouble(),
            "extendedBlend" to ProcreateBlendModes.numberOf(p.blendMode).toLong(),
            "wetEdgesAmount" to unit(p.wetEdges),
            "burntEdgesAmount" to unit(p.burntEdges),
            "grainDepth" to unit(p.grainDepth),
            "dynamicsFalloff" to unit(p.falloff),
            "shapeFlipXJitter" to p.tipFlipXJitter,
            "shapeFlipYJitter" to p.tipFlipYJitter,
            "shapeRandomise" to p.tipRandomized,
            "renderingRecursiveMixing" to p.buildUp,
            "textureMovement" to if (p.grainMoving) 1.0 else 0.0,
            "dynamicsPressureSecondaryColor" to unit(p.secondaryPressure),
            "jitterSecondary" to unit(p.secondaryJitter),
        )

    private fun unit(value: Float): Double = value.coerceIn(0f, 1f).toDouble()

    /** Round-trip signed imported colour dynamics without writing non-finite scalar values. */
    private fun signed(value: Float): Double = if (value.isFinite()) value.coerceIn(-1f, 1f).toDouble() else 0.0

    /**
     * A tile as an opaque grey PNG. A fixed tip flip belongs in the pixels, since the verified native
     * fields describe random mirroring only. Sample the original without mutating shared shape/grain tiles.
     */
    private fun png(
        tile: CustomGrains.Tile,
        flipX: Boolean = false,
        flipY: Boolean = false,
    ): ByteArray {
        val image = PixelBuffer(tile.size, tile.size)
        for (y in 0 until tile.size) {
            val sourceY = if (flipY) tile.size - 1 - y else y
            for (x in 0 until tile.size) {
                val sourceX = if (flipX) tile.size - 1 - x else x
                val value = tile.values[sourceY * tile.size + sourceX].toInt() and 0xFF
                image.pixels[y * tile.size + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }
        return PngCodec.encode(image)
    }

    private fun zipOf(write: (ZipOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use(write)
        return out.toByteArray()
    }

    private fun entry(
        out: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        out.putNextEntry(ZipEntry(name))
        out.write(bytes)
        out.closeEntry()
    }

    private const val ARCHIVE = "Brush.archive"
    private const val SHAPE = "Shape.png"
    private const val GRAIN = "Grain.png"
    private const val BRUSH_CLASS = "SilicaBrush"

    /** Procreate's size slider scale, as [ProcreateBrush] reads it. */
    private const val BASE_SIZE = 24f
    private const val MIN_SCALE = 0.05f
    private const val MAX_SCALE = 8f
    private const val MIN_OPACITY = 0.05f
    private const val MIN_SPACING = 0.01f
    private const val MIN_ROUNDNESS = 0.05f
    private const val LEGACY_MIN_GRAIN_SCALE = 0.25f
    private const val MAX_COUNT = 16
}
