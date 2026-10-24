package com.artflow.studio.core.export

import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.ColorDynamics
import com.artflow.studio.domain.model.brush.ImportedPressure
import com.artflow.studio.domain.model.brush.NATIVE_BRUSH_IMPORT_VERSION
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.RenderingMode
import com.artflow.studio.domain.model.brush.SourceBrushScalar
import com.artflow.studio.domain.model.brush.SourceBrushStructuredField
import com.artflow.studio.domain.model.layer.BlendMode
import java.util.Base64
import kotlin.math.roundToInt

/**
 * A Procreate brush's settings, from the keyed archive ("Brush.archive") inside a `.brush` file
 * (or each brush folder of a `.brushset`), translated to ArtFlow's brush model. Procreate keeps
 * far more settings than ArtFlow and their scales are not published, so this carries over what
 * maps cleanly (spacing, jitter, scatter, count, roundness, pressure, speed, tilt and colour
 * dynamics, tapers, wet mix, flow, blend mode, grain scale and orientation) as a close start.
 */
object ProcreateBrush {
    /** REA-observed curve dictionaries with real control points, beyond overall / size / opacity / flow. */
    internal val AUXILIARY_CURVE_KEYS: Set<String> =
        setOf(
            "dynamicsPressureAttackCurve",
            "dynamicsPressureBleedCurve",
            "dynamicsPressureBlurCurve",
            "dynamicsPressureBrightnessCurve",
            "dynamicsPressureCharge",
            "dynamicsPressureDilutionCurve",
            "dynamicsPressureGradeCurve",
            "dynamicsPressureGrainDepthCurve",
            "dynamicsPressureGrainDepthJitterCurve",
            "dynamicsPressureGrainMovementCurve",
            "dynamicsPressureGrainScaleCurve",
            "dynamicsPressureGrainZoomCurve",
            "dynamicsPressureHueCurve",
            "dynamicsPressureHueJitterCurve",
            "dynamicsPressureOpacityJitterCurve",
            "dynamicsPressurePlotJitterCurve",
            "dynamicsPressurePlotJitterLongitudinalCurve",
            "dynamicsPressurePlotSpacingJitter",
            "dynamicsPressureSaturationCurve",
            "dynamicsPressureSecondaryColorCurve",
            "dynamicsPressureShapeRoundessHorizontalJitterCurve",
            "dynamicsPressureShapeRoundessVerticalJitterCurve",
            "dynamicsPressureShapeRoundnessCurve",
            "dynamicsPressureShapeScatterCurve",
            "dynamicsPressureSizeJitterCurve",
            "dynamicsPressureSmudgePullCurve",
            "dynamicsPressureStampCountCurve",
            "dynamicsPressureStampCountJitterCurve",
            "dynamicsPressureWetPullCurve",
        )

    /** Keep dictionary schema names with the domain validation contract. */
    internal val ARRAY_OBJECT_KEYS: Set<String> = SourceBrushStructuredField.ARRAY_NAMES
    internal val STRUCTURED_OBJECT_KEYS: Set<String> = SourceBrushStructuredField.ALLOWED_NAMES

    class Settings(
        val name: String?,
        /** Settings without images; the importer adds the stored shape and grain. */
        val parameters: BrushParams,
        /** The shape and grain images paint black instead of white. */
        val shapeInverted: Boolean,
        val grainInverted: Boolean,
        /** The brush uses one of Procreate's own bundled shapes or grains, which files do not carry. */
        val bundledShape: Boolean,
        val bundledGrain: Boolean,
        /** Effective source identifier: the extended field wins, then legacy, then native default zero. */
        val dualBlendMode: Int? = null,
    )

    fun read(archive: ByteArray): Settings {
        val keyed = KeyedArchive(BinaryPlist.read(archive))
        val brush = requireNotNull(keyed.root) { "This is not a Procreate brush" }

        fun number(key: String): Float? = (brush[key] as? Number)?.toFloat()?.takeIf { it.isFinite() }

        fun unit(key: String): Float = number(key)?.coerceIn(0f, 1f) ?: 0f

        fun flag(key: String): Boolean = brush[key] == true

        fun signed(key: String): Float = number(key)?.coerceIn(-1f, 1f) ?: 0f

        val mode =
            number("extendedBlend")?.roundToInt()?.let(ProcreateBlendModes::of)
                ?: number("blendMode")?.roundToInt()?.let(ProcreateBlendModes::of)
        val parameters =
            BrushParams(
                sourceImportVersion = NATIVE_BRUSH_IMPORT_VERSION,
                size =
                    (
                        BASE_SIZE * (number("maxSize") ?: 1f).coerceIn(MIN_SCALE, MAX_SCALE) *
                            (number("paintSize") ?: 1f).coerceIn(MIN_SCALE, 1f)
                    ).coerceIn(1f, MAX_SIZE),
                opacity = ((number("maxOpacity") ?: 1f) * (number("paintOpacity") ?: 1f)).coerceIn(MIN_OPACITY, 1f),
                spacing = (number("plotSpacing") ?: DEFAULT_SPACING).coerceIn(MIN_SPACING, 1f),
                scatter = unit("plotJitter"),
                count = 1 + (unit("shapeCount") * (MAX_COUNT - 1)).roundToInt(),
                countJitter = unit("shapeCountJitter"),
                rotation = normalizedAngle(number("shapeAngle") ?: 0f),
                taperStart = unit("pencilTaperStartLength"),
                taperEnd = unit("pencilTaperEndLength"),
                taperOpacity = unit("pencilTaperOpacity"),
                pressureToSize = signed("dynamicsPressureSize"),
                // Native initWithCoder/getter offsets establish these historical archive aliases.
                // The field called Opacity is Flow; OpacityTransfer is the actual opacity channel.
                pressureToFlow = signed("dynamicsPressureOpacity"),
                pressureToOpacity = signed("dynamicsPressureOpacityTransfer"),
                importedPressure = primaryCurves(keyed, brush),
                importedAuxiliaryCurves =
                    AUXILIARY_CURVE_KEYS
                        .mapNotNull { key ->
                            curve(keyed, brush[key])?.let { mapping -> key to mapping }
                        }.toMap(),
                importedSourceScalars = sourceScalars(keyed, brush),
                importedSourceStructures = sourceStructures(keyed, brush),
                importedSourceColorData =
                    keyed
                        .data(brush["color"])
                        ?.takeIf { it.size == SOURCE_COLOR_BYTES }
                        ?.let { Base64.getEncoder().encodeToString(it) },
                hueJitter = unit("dynamicsJitterHue"),
                saturationJitter = unit("dynamicsJitterSaturation"),
                brightnessJitter = maxOf(unit("dynamicsJitterLightness"), unit("dynamicsJitterDarkness")),
                // The reference corpus contains all six colour pressure/tilt scalars.
                // ArtFlow applies its documented HSV approximation; reference pixel parity is unknown.
                colorDynamics =
                    ColorDynamics(
                        pressureHue = signed("dynamicsPressureHue"),
                        pressureSaturation = signed("dynamicsPressureSaturation"),
                        pressureBrightness = signed("dynamicsPressureBrightness"),
                        tiltHue = signed("dynamicsTiltHue"),
                        tiltSaturation = signed("dynamicsTiltSaturation"),
                        tiltBrightness = signed("dynamicsTiltBrightness"),
                    ),
                sizeJitter = unit("dynamicsJitterSize"),
                opacityJitter = unit("dynamicsJitterOpacity"),
                smoothing = maxOf(unit("plotSmoothing"), unit("plotMovingAverageStabilization"), unit("plotFFTSmoothingAmount")),
                // Native getters prove Mix is Dilution and PressureMix is Attack.
                wetDilution = unit("dynamicsMix"),
                wetAttack = (number("dynamicsPressureMix") ?: 1f).coerceIn(0f, 1f),
                wetPull = (number("dynamicsWetAccumulation") ?: number("dynamicsSmudgeAccumulation") ?: 0f).coerceIn(0f, 1f),
                bleed = unit("dynamicsPressureBleed"),
                wetBlur = unit("dynamicsBlur"),
                wetBlurJitter = unit("dynamicsBlurJitter"),
                flow = (number("dynamicsGlazedFlow") ?: 1f).coerceIn(MIN_OPACITY, 1f),
                textureScale = (number("textureScale") ?: 1f).coerceIn(MIN_GRAIN_SCALE, MAX_SCALE),
                tiltInfluence = unit("dynamicsTiltSize"),
                tiltOpacityInfluence = unit("dynamicsTiltOpacity"),
                tiltToRotation = flag("shapeAzimuth") || flag("oriented"),
                velocityToSize = (number("dynamicsSpeedSize") ?: 0f).coerceIn(-1f, 1f),
                velocityToOpacity = (number("dynamicsSpeedOpacity") ?: 0f).coerceIn(-1f, 1f),
                roundness = (number("shapeRoundness") ?: 1f).coerceIn(MIN_ROUNDNESS, 1f),
                blendMode = mode ?: BlendMode.NORMAL,
                wetEdges = unit("wetEdgesAmount"),
                burntEdges = unit("burntEdgesAmount"),
                grainDepth = number("grainDepth")?.coerceIn(0f, 1f) ?: 1f,
                falloff = unit("dynamicsFalloff"),
                tipFlipXJitter = flag("shapeFlipXJitter"),
                tipFlipYJitter = flag("shapeFlipYJitter"),
                tipRandomized = flag("shapeRandomise"),
                buildUp = flag("renderingRecursiveMixing"),
                renderingMode =
                    if (flag("renderingRecursiveMixing")) RenderingMode.BLEND_UNIFORM else RenderingMode.GLAZE_UNIFORM,
                grainMoving = unit("textureMovement") >= MOVING_GRAIN,
                secondaryPressure = unit("dynamicsPressureSecondaryColor"),
                secondaryJitter = unit("jitterSecondary"),
            )
        return Settings(
            name = keyed.string(brush["name"])?.trim()?.takeIf { it.isNotEmpty() },
            parameters = parameters,
            shapeInverted = flag("shapeInverted"),
            grainInverted = flag("textureInverted"),
            bundledShape = keyed.string(brush["bundledShapePath"]) != null,
            bundledGrain = keyed.string(brush["bundledGrainPath"]) != null,
            dualBlendMode = effectiveDualMode(number("dualBlendModeExtended"), number("dualBlendMode")),
        )
    }

    private fun effectiveDualMode(
        extended: Float?,
        legacy: Float?,
    ): Int = (extended ?: legacy ?: 0f).roundToInt()

    /**
     * Inspect a synthetic, decoded settings dictionary using the same importer as real
     * Brush.archive inputs. This is deliberately not a second, divergent parser.
     */
    fun parameters(fields: Map<String, Any?>): BrushParams = read(KeyedArchiveBuilder().encode(fields)).parameters

    private fun primaryCurves(
        keyed: KeyedArchive,
        brush: Map<String, Any?>,
    ): ImportedPressure? =
        ImportedPressure(
            overall = curve(keyed, brush["dynamicsPressureOverall"]),
            size = curve(keyed, brush["dynamicsPressureSizeCurve"]),
            opacity = curve(keyed, brush["dynamicsPressureTransferModulationCurve"]),
            flow = curve(keyed, brush["dynamicsPressureOpacityCurve"]),
        ).takeIf { it.overall != null || it.size != null || it.opacity != null || it.flow != null }

    /** Preserve bounded source values before the editable renderer parameters are clamped. */
    private fun sourceScalars(
        keyed: KeyedArchive,
        brush: Map<String, Any?>,
    ): Map<String, SourceBrushScalar> {
        val scalars =
            brush
                .mapNotNull { (name, raw) ->
                    if (!SourceBrushScalar.validName(name)) return@mapNotNull null
                    val field = SourceBrushScalar.capture(keyed.resolve(raw)) ?: return@mapNotNull null
                    name to field
                }.toMap()
        require(scalars.size <= SourceBrushScalar.MAX_FIELDS) { "Too many source brush scalar settings" }
        return scalars
    }

    /** Only observed primitive arrays/objects are retained; arbitrary nested archive data stays out. */
    private fun sourceStructures(
        keyed: KeyedArchive,
        brush: Map<String, Any?>,
    ): Map<String, SourceBrushStructuredField> =
        STRUCTURED_OBJECT_KEYS
            .mapNotNull { key ->
                val original = brush[key] ?: return@mapNotNull null
                val className = keyed.className(original) ?: return@mapNotNull null
                val objectFields = keyed.map(original) ?: return@mapNotNull null
                val captured =
                    if (key in ARRAY_OBJECT_KEYS) {
                        val rawValues = keyed.array(original)
                        if (rawValues.size > SourceBrushStructuredField.MAX_ITEMS) return@mapNotNull null
                        val values = rawValues.mapNotNull(SourceBrushScalar::capture)
                        if (values.size != rawValues.size) return@mapNotNull null
                        SourceBrushStructuredField(className, SourceBrushStructuredField.Kind.ARRAY, items = values)
                    } else {
                        val originalFields = objectFields.filterKeys { it != "\$class" }
                        if (originalFields.size > SourceBrushStructuredField.MAX_FIELDS) return@mapNotNull null
                        val values =
                            originalFields
                                .mapNotNull { (name, value) ->
                                    val scalar = SourceBrushScalar.capture(keyed.resolve(value)) ?: return@mapNotNull null
                                    name to scalar
                                }.toMap()
                        if (values.size != originalFields.size) return@mapNotNull null
                        SourceBrushStructuredField(className, SourceBrushStructuredField.Kind.FIELDS, fields = values)
                    }
                if (captured.isValidFor(key)) key to captured else null
            }.toMap()

    private fun normalizedAngle(radians: Float): Float {
        val degrees = Math.toDegrees(radians.toDouble()) % FULL_TURN
        return ((degrees + FULL_TURN) % FULL_TURN).toFloat()
    }

    /** REA shows NSArray point strings, not three evenly spaced monotone controls. */
    private fun curve(
        keyed: KeyedArchive,
        value: Any?,
    ): PressureMapping? {
        val curve = keyed.map(value) ?: return null
        val values = keyed.array(curve["points"])
        if (values.size !in 2..PressureMapping.MAX_POINTS) return null
        val points =
            values
                .map { item ->
                    val text = keyed.string(item)?.trim() ?: return null
                    if (!text.startsWith("{") || !text.endsWith("}")) return null
                    val coordinates = text.substring(1, text.lastIndex).split(',')
                    if (coordinates.size != 2) return null
                    val input = coordinates[0].trim().toFloatOrNull() ?: return null
                    val output = coordinates[1].trim().toFloatOrNull() ?: return null
                    PressureMapping.Point(input, output)
                }.sortedBy { it.input }
        return try {
            PressureMapping(points)
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }

    /** Procreate's size slider tops out at maxSize; 1.0 is taken as a medium brush. */
    private const val BASE_SIZE = 24f
    private const val MAX_SIZE = 500f
    private const val MIN_SCALE = 0.05f
    private const val MAX_SCALE = 8f

    /** The smallest grain scale the library stores and the renderer uses. */
    private const val MIN_GRAIN_SCALE = 0.05f
    private const val MIN_OPACITY = 0.05f
    private const val DEFAULT_SPACING = 0.1f
    private const val MIN_SPACING = 0.01f
    private const val MAX_COUNT = 16
    private const val FULL_TURN = 360f

    private const val MIN_ROUNDNESS = 0.05f
    private const val MOVING_GRAIN = 0.5f
    private const val SOURCE_COLOR_BYTES = 16

    private fun Map<String, Any?>.number(key: String): Float? = (this[key] as? Number)?.toFloat()?.takeIf { it.isFinite() }

    private fun Map<String, Any?>.unit(key: String): Float = number(key)?.coerceIn(0f, 1f) ?: 0f

    private fun Map<String, Any?>.flag(key: String): Boolean = this[key] == true
}

/** Procreate's blend mode numbers, as documents and brushes store them. */
internal object ProcreateBlendModes {
    private val MODES =
        mapOf(
            0 to BlendMode.NORMAL,
            1 to BlendMode.MULTIPLY,
            2 to BlendMode.SCREEN,
            3 to BlendMode.ADD,
            4 to BlendMode.LIGHTEN,
            5 to BlendMode.EXCLUSION,
            6 to BlendMode.DIFFERENCE,
            7 to BlendMode.SUBTRACT,
            8 to BlendMode.LINEAR_BURN,
            9 to BlendMode.COLOR_DODGE,
            10 to BlendMode.COLOR_BURN,
            11 to BlendMode.OVERLAY,
            12 to BlendMode.HARD_LIGHT,
            13 to BlendMode.COLOR,
            14 to BlendMode.LUMINOSITY,
            15 to BlendMode.HUE,
            16 to BlendMode.SATURATION,
            17 to BlendMode.SOFT_LIGHT,
            19 to BlendMode.DARKEN,
            20 to BlendMode.HARD_MIX,
            21 to BlendMode.VIVID_LIGHT,
            22 to BlendMode.LINEAR_LIGHT,
            23 to BlendMode.PIN_LIGHT,
            24 to BlendMode.LIGHTER_COLOR,
            25 to BlendMode.DARKER_COLOR,
            26 to BlendMode.DIVIDE,
        )

    fun of(number: Int): BlendMode? = MODES[number]

    /** The number Procreate stores for [mode]; modes it has no number for are written as Normal. */
    fun numberOf(mode: BlendMode): Int = MODES.entries.firstOrNull { it.value == mode }?.key ?: 0
}
