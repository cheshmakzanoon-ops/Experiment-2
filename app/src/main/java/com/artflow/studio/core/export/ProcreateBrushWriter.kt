package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SourceBrushScalar
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes Procreate brushes, the inverse of [ProcreateBrush]: a `.brush` is a zip holding the brush's
 * settings as a keyed archive ("Brush.archive") with its shape and grain images as PNG ("Shape.png",
 * "Grain.png"), and a `.brushset` is a zip of such folders with "brushset.plist" listing them in order.
 *
 * Each setting is written on the scale [ProcreateBrush.read] maps it back from, so a brush read back has
 * the same value within float rounding wherever the value is in range. Settings that [ProcreateBrush] does
 * not read (pressure curves, the second brush, texture rotation) are not written.
 * Signed color-pressure and color-tilt scalars are written, though native pixel parity is unverified.
 */
object ProcreateBrushWriter {
    /** One brush to write: its name, settings, and its shape and grain tiles (null when it has none). */
    class Brush(
        val name: String,
        val parameters: BrushParams,
        val shape: CustomGrains.Tile? = null,
        val grain: CustomGrains.Tile? = null,
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
        entry(out, prefix + ARCHIVE, archive(brush))
        brush.shape?.let { entry(out, prefix + SHAPE, png(it)) }
        brush.grain?.let { entry(out, prefix + GRAIN, png(it)) }
    }

    /** The brush's settings as a keyed archive of class SilicaBrush, the form [ProcreateBrush.read] reads. */
    private fun archive(brush: Brush): ByteArray {
        val keyed = KeyedArchiveBuilder()
        // Preserve known source curve points as keyed NSArray values. Native interpolation,
        // per-field response units and pixel output remain unverified.
        val auxiliary =
            brush.parameters.importedAuxiliaryCurves
                .filterKeys { it in ProcreateBrush.AUXILIARY_CURVE_KEYS }
                .mapValues { (_, curve) ->
                    val points = keyed.array(curve.points.map { point -> keyed.add("{${point.input}, ${point.output}}") })
                    keyed.instance("NSDictionary", mapOf("points" to points))
                }
        val preservedScalars =
            brush.parameters.importedSourceScalars.mapValues { (_, scalar) ->
                require(scalar.isValid()) { "Invalid stored source brush scalar" }
                when (scalar.type) {
                    SourceBrushScalar.Type.BOOLEAN -> scalar.value == "true"
                    SourceBrushScalar.Type.INTEGER -> requireNotNull(scalar.value.toLongOrNull())
                    SourceBrushScalar.Type.REAL -> requireNotNull(scalar.value.toDoubleOrNull())
                    SourceBrushScalar.Type.TEXT -> keyed.add(scalar.value)
                }
            }
        // Editable ArtFlow settings win for known fields; original values survive
        // for every other primitive source setting. Compound settings and opaque
        // native data are outside this scalar-preservation contract.
        val root =
            preservedScalars + mapOf("\$class" to keyed.classOf(BRUSH_CLASS), "name" to keyed.add(brush.name)) +
                settings(brush.parameters) + auxiliary
        return keyed.encode(root)
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
            "shapeAngle" to Math.toRadians(p.rotation.toDouble()),
            "pencilTaperStartLength" to unit(p.taperStart),
            "pencilTaperEndLength" to unit(p.taperEnd),
            "pencilTaperOpacity" to unit(p.taperOpacity),
            "dynamicsPressureSize" to p.pressureToSize.coerceIn(-1f, 1f).toDouble(),
            "dynamicsPressureOpacity" to p.pressureToOpacity.coerceIn(-1f, 1f).toDouble(),
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
            "dynamicsMix" to unit(p.wetMix),
            "dynamicsGlazedFlow" to p.flow.coerceIn(MIN_OPACITY, 1f).toDouble(),
            "textureScale" to p.textureScale.coerceIn(MIN_SCALE, MAX_SCALE).toDouble(),
            "dynamicsTiltSize" to unit(p.tiltInfluence),
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
            "shapeInverted" to false,
            "textureInverted" to false,
        )

    private fun unit(value: Float): Double = value.coerceIn(0f, 1f).toDouble()

    /** Round-trip signed imported colour dynamics without writing non-finite scalar values. */
    private fun signed(value: Float): Double = if (value.isFinite()) value.coerceIn(-1f, 1f).toDouble() else 0.0

    /** A tile as an opaque grey PNG: its values are the brightness, which the reader takes back as the tile. */
    private fun png(tile: CustomGrains.Tile): ByteArray {
        val image = PixelBuffer(tile.size, tile.size)
        for (index in tile.values.indices) {
            val value = tile.values[index].toInt() and 0xFF
            image.pixels[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
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
    private const val MAX_COUNT = 16
}
