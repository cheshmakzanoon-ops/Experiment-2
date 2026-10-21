package com.artflow.studio.core.export

import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.ImportedPressure
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.layer.BlendMode
import kotlin.math.roundToInt

/**
 * A Procreate brush's settings, from the keyed archive ("Brush.archive") inside a `.brush` file
 * (or each brush folder of a `.brushset`), translated to ArtFlow's brush model. Procreate keeps
 * far more settings than ArtFlow and their scales are not published, so this carries over what
 * maps cleanly (spacing, jitter, scatter, count, roundness, pressure, speed, tilt and colour
 * dynamics, tapers, wet mix, flow, blend mode, grain scale and orientation) as a close start.
 */
object ProcreateBrush {
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
        /** Raw combine-mode identifier; retained even when no equivalent is available yet. */
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
                size =
                    (
                        BASE_SIZE * (number("maxSize") ?: 1f).coerceIn(MIN_SCALE, MAX_SCALE) *
                            (number("paintSize") ?: 1f).coerceIn(MIN_SCALE, 1f)
                    ).coerceIn(1f, MAX_SIZE),
                opacity = ((number("maxOpacity") ?: 1f) * (number("paintOpacity") ?: 1f)).coerceIn(MIN_OPACITY, 1f),
                spacing = (number("plotSpacing") ?: DEFAULT_SPACING).coerceIn(MIN_SPACING, 1f),
                scatter = unit("plotJitter"),
                count = 1 + (unit("shapeCount") * (MAX_COUNT - 1)).roundToInt(),
                rotation = normalizedAngle(number("shapeAngle") ?: 0f),
                taperStart = unit("pencilTaperStartLength"),
                taperEnd = unit("pencilTaperEndLength"),
                taperOpacity = unit("pencilTaperOpacity"),
                pressureToSize = signed("dynamicsPressureSize"),
                pressureToOpacity = signed("dynamicsPressureOpacity"),
                importedPressure =
                    ImportedPressure(
                        overall = curve(keyed, brush["dynamicsPressureOverall"]),
                        size = curve(keyed, brush["dynamicsPressureSizeCurve"]),
                        opacity = curve(keyed, brush["dynamicsPressureOpacityCurve"]),
                    ).takeIf { it.overall != null || it.size != null || it.opacity != null },
                hueJitter = unit("dynamicsJitterHue"),
                saturationJitter = unit("dynamicsJitterSaturation"),
                brightnessJitter = maxOf(unit("dynamicsJitterLightness"), unit("dynamicsJitterDarkness")),
                sizeJitter = unit("dynamicsJitterSize"),
                opacityJitter = unit("dynamicsJitterOpacity"),
                smoothing = maxOf(unit("plotSmoothing"), unit("plotMovingAverageStabilization"), unit("plotFFTSmoothingAmount")),
                wetMix = unit("dynamicsMix"),
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
                renderingMode = if (flag("renderingRecursiveMixing")) com.artflow.studio.domain.model.brush.RenderingMode.BLEND_UNIFORM else com.artflow.studio.domain.model.brush.RenderingMode.GLAZE_UNIFORM,
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
            dualBlendMode = number("dualBlendMode")?.roundToInt(),
        )
    }

    /**
     * Inspect a synthetic, decoded settings dictionary using the same importer as real
     * Brush.archive inputs. This is deliberately not a second, divergent parser.
     */
    fun parameters(fields: Map<String, Any?>): BrushParams =
        read(KeyedArchiveBuilder().encode(fields)).parameters

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
    private const val MIN_GRAIN_SCALE = 0.25f
    private const val MIN_OPACITY = 0.05f
    private const val DEFAULT_SPACING = 0.1f
    private const val MIN_SPACING = 0.01f
    private const val MAX_COUNT = 16
    private const val FULL_TURN = 360f

    /** Kotlin's % keeps the sign of the dividend, so a negative angle needs wrapping to [0, 360). */
    private fun wrapDegrees(degrees: Float): Float = ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN

    private const val MIN_ROUNDNESS = 0.05f
    private const val MOVING_GRAIN = 0.5f

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
