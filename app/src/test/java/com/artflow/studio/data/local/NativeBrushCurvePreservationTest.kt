package com.artflow.studio.data.local

import com.artflow.studio.core.export.BinaryPlist
import com.artflow.studio.core.export.KeyedArchive
import com.artflow.studio.core.export.KeyedArchiveBuilder
import com.artflow.studio.core.export.ProcreateBrush
import com.artflow.studio.core.export.ProcreateBrushWriter
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Archive fixtures use the independently recovered native channel order, never the importer's allowlist. */
class NativeBrushCurvePreservationTest {
    private val curves =
        NATIVE_KEYS
            .mapIndexed { index, key ->
                val value = (index + 1) / 64f
                key to
                    PressureMapping(
                        listOf(
                            PressureMapping.Point(0f, value),
                            PressureMapping.Point(0.375f, 1f - value),
                            PressureMapping.Point(1f, value / 2f),
                        ),
                        PressureMapping.Interpolation.NATIVE_SPLINE_1024,
                    )
            }.toMap()
    private val reversed = curves.mapValues { (_, curve) -> curve.copy(points = curve.points.map { it.copy(output = 1f - it.output) }) }
    private val auxiliary = curves.filterKeys { it !in PRIMARY_KEYS }
    private val secondaryAuxiliary = reversed.filterKeys { it !in PRIMARY_KEYS }

    @Test
    fun allThirtyNineNativeChannelsSurviveCompoundImportAndReexport() {
        val source = compoundArchive(sourceArchive(curves), sourceArchive(reversed))
        val imported = importCompound(source)
        assertChannels(curves, imported)
        assertChannels(reversed, requireNotNull(imported.dual).params)

        val exported = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Complete channels", imported))
        val files = archiveFiles(exported)
        assertEquals(setOf("Brush.archive", "Sub01/Brush.archive"), files.keys)
        assertEquals(curves.mapValues { it.value.points }, archiveCurves(files.getValue("Brush.archive")))
        assertEquals(reversed.mapValues { it.value.points }, archiveCurves(files.getValue("Sub01/Brush.archive")))
        val reopened = importCompound(exported)
        assertChannels(curves, reopened)
        assertChannels(reversed, requireNotNull(reopened.dual).params)
    }

    @Test
    fun writerPreservesCompleteStoredAuxiliaryMapsWithoutDependingOnImport() {
        val params = completeAuxiliaryParams()
        val exported = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Stored curves", params))
        val files = archiveFiles(exported)
        assertEquals(auxiliary.mapValues { it.value.points }, archiveCurves(files.getValue("Brush.archive")))
        assertEquals(secondaryAuxiliary.mapValues { it.value.points }, archiveCurves(files.getValue("Sub01/Brush.archive")))
        val imported = importCompound(exported)
        assertEquals(auxiliary, imported.importedAuxiliaryCurves)
        assertEquals(secondaryAuxiliary, requireNotNull(imported.dual).params.importedAuxiliaryCurves)
    }

    @Test
    fun completeImportedCurvesSurviveLibraryAndArtbrushPersistence() {
        val imported = importCompound(compoundArchive(sourceArchive(curves), sourceArchive(reversed)))
        val saved = listOf(SavedBrush("native-channels", "Complete channels", imported))
        assertEquals(35, imported.importedAuxiliaryCurves.size)
        assertEquals(35, requireNotNull(imported.dual).params.importedAuxiliaryCurves.size)
        val library = BrushLibraryCodec.decode(BrushLibraryCodec.encode(saved))
        assertEquals(saved, library)
        val (reopened, images) = BrushFiles.decode(BrushFiles.encode(library))
        assertEquals(saved, reopened)
        assertTrue(images.isEmpty())
    }

    @Test
    fun auxiliaryCapacityAcceptsThirtyFiveAndRejectsThirtySixInEitherComponent() {
        val complete = completeAuxiliaryParams()
        val saved = listOf(SavedBrush("capacity", "Capacity", complete))
        assertEquals(saved, BrushLibraryCodec.decode(BrushLibraryCodec.encode(saved)))
        val overflow = auxiliary + ("dynamicsPressureSyntheticOverflow" to curves.getValue(NATIVE_KEYS.first()))
        assertEquals(36, overflow.size)
        val invalid =
            listOf(
                complete.copy(importedAuxiliaryCurves = overflow),
                complete.copy(dual = DualBrush(requireNotNull(complete.dual).params.copy(importedAuxiliaryCurves = overflow))),
            )
        invalid.forEach { params ->
            val brushes = listOf(saved.single().copy(parameters = params))
            assertThrows(IllegalArgumentException::class.java) { BrushLibraryCodec.encode(brushes) }
            assertThrows(IllegalArgumentException::class.java) { BrushFiles.encode(brushes) }
        }
    }

    @Test
    fun signedCurveDocumentsRetainPublishedBytesAndRejectTampering() {
        for ((sourceVersion, publishedHash) in PUBLISHED_DOCUMENT_HASHES.withIndex()) {
            val original = document(completeAuxiliaryParams().copy(sourceImportVersion = sourceVersion))
            val encoded = CanvasDocumentCodec.encode(original)
            // Captured independently from unchanged 8d8d application classes, before the schema correction.
            assertEquals(publishedHash, sha256(encoded))
            val reopened = CanvasDocumentCodec.decode(encoded.decodeToString())
            assertEquals(original, reopened)
            assertArrayEquals(encoded, CanvasDocumentCodec.encode(reopened))
            val altered = encoded.decodeToString().replaceFirst("\"output\":0.015625", "\"output\":0.03125")
            assertFalse(altered == encoded.decodeToString())
            val failure = assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.decode(altered) }
            assertTrue(failure.message.orEmpty().contains("checksum", ignoreCase = true))
        }
    }

    @Test
    fun unknownKeysAndMalformedNewGraphsDoNotBypassExistingCurveValidation() {
        val missingBefore = NATIVE_KEYS.filter { it in NEW_KEYS }
        assertEquals(6, missingBefore.size)
        for (key in missingBefore) {
            val bytes =
                sourceArchive(
                    mapOf(key to curves.getValue(key)),
                    replacement = listOf("{0.0, 0.25}", "{0.0, 0.75}"),
                )
            assertTrue(
                ProcreateBrush
                    .read(bytes)
                    .parameters.importedAuxiliaryCurves
                    .isEmpty(),
            )
        }
        val unknownKey = "dynamicsPressureSyntheticUnknownCurve"
        val unknown = sourceArchive(mapOf(unknownKey to curves.getValue(NATIVE_KEYS.first())))
        assertTrue(
            ProcreateBrush
                .read(unknown)
                .parameters.importedAuxiliaryCurves
                .isEmpty(),
        )
        val params = BrushParams(importedAuxiliaryCurves = mapOf(unknownKey to curves.getValue(NATIVE_KEYS.first())))
        val exported = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Unknown", params))
        assertFalse(unknownKey in requireNotNull(KeyedArchive(BinaryPlist.read(archiveFiles(exported).getValue("Brush.archive"))).root))
    }

    private fun completeAuxiliaryParams(): BrushParams =
        BrushParams(importedAuxiliaryCurves = auxiliary, dual = DualBrush(BrushParams(importedAuxiliaryCurves = secondaryAuxiliary)))

    private fun assertChannels(
        expected: Map<String, PressureMapping>,
        params: BrushParams,
    ) {
        assertEquals(expected.filterKeys { it !in PRIMARY_KEYS }, params.importedAuxiliaryCurves)
        val primary = requireNotNull(params.importedPressure)
        assertEquals(expected.getValue("dynamicsPressureOverall"), primary.overall)
        assertEquals(expected.getValue("dynamicsPressureSizeCurve"), primary.size)
        assertEquals(expected.getValue("dynamicsPressureOpacityCurve"), primary.flow)
        assertEquals(expected.getValue("dynamicsPressureTransferModulationCurve"), primary.opacity)
    }

    private fun sourceArchive(
        mappings: Map<String, PressureMapping>,
        replacement: List<String>? = null,
    ): ByteArray {
        val keyed = KeyedArchiveBuilder()
        val fields =
            mappings.mapValues { (_, mapping) ->
                val values = replacement ?: mapping.points.map { "{${it.input}, ${it.output}}" }
                val points = keyed.array(values.map(keyed::add))
                keyed.instance("ValkyrieMagnitudinalCurve", mapOf("points" to points))
            }
        return keyed.encode(mapOf("\$class" to keyed.classOf("SilicaBrush"), "name" to keyed.add("Authored native channels")) + fields)
    }

    private fun compoundArchive(
        primary: ByteArray,
        secondary: ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in listOf("Brush.archive" to primary, "Sub01/Brush.archive" to secondary)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun importCompound(bytes: ByteArray): BrushParams =
        BrushArchives.read(bytes) { null }.single().parameters { error("This fixture has no images") }

    private fun archiveFiles(bytes: ByteArray): Map<String, ByteArray> =
        buildMap {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith("Brush.archive")) put(entry.name, zip.readBytes())
                }
            }
        }

    private fun archiveCurves(bytes: ByteArray): Map<String, List<PressureMapping.Point>> {
        val keyed = KeyedArchive(BinaryPlist.read(bytes))
        return requireNotNull(keyed.root)
            .mapNotNull { (name, value) ->
                val fields = keyed.map(value) ?: return@mapNotNull null
                if ("points" !in fields) return@mapNotNull null
                assertEquals("ValkyrieMagnitudinalCurve", keyed.className(value))
                name to
                    keyed.array(fields["points"]).map { item ->
                        val pair = requireNotNull(keyed.string(item)).removePrefix("{").removeSuffix("}").split(',')
                        PressureMapping.Point(pair[0].trim().toFloat(), pair[1].trim().toFloat())
                    }
            }.toMap()
    }

    private fun document(params: BrushParams): CanvasDocument {
        val stroke =
            Stroke(
                id = 403,
                points = listOf(StrokePoint(12.5f, 12.5f, timestamp = 0L)),
                brushParams = params,
                layerId = 1L,
                color = 0xff114477.toInt(),
                timestamp = 0L,
            )
        return CanvasDocument(
            width = 32,
            height = 32,
            dpi = 72,
            backgroundColor = 0,
            activeLayerId = 1L,
            nextLayerId = 2L,
            layers = listOf(Layer(1L, "Source curves", 0, strokes = listOf(stroke))),
            savedAt = 0L,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        val PRIMARY_KEYS =
            setOf(
                "dynamicsPressureOverall",
                "dynamicsPressureSizeCurve",
                "dynamicsPressureOpacityCurve",
                "dynamicsPressureTransferModulationCurve",
            )
        val NEW_KEYS =
            setOf(
                "dynamicsPressureBlurJitterCurve",
                "dynamicsPressureWetnessCurve",
                "dynamicsPressureSaturationJitterCurve",
                "dynamicsPressureLightnessJitterCurve",
                "dynamicsPressureDarknessJitterCurve",
                "dynamicsPressureSecondaryColorJitterCurve",
            )
        val PUBLISHED_DOCUMENT_HASHES =
            listOf(
                "b7be93fd45cfedc154a8e02ab6dc9d8421adf366fb448bc1c480820abf703020",
                "f50c9d5b294d6e933cb164d20811c87d1ecab6e94353d1ae96d10fd923fcc984",
                "8a8816eeee9f33ff7d25753656baea9b04ab45972caf818dfb44a22582da78d0",
            )
        val NATIVE_KEYS =
            listOf(
                "dynamicsPressurePlotJitterCurve",
                "dynamicsPressureStampCountCurve",
                "dynamicsPressureStampCountJitterCurve",
                "dynamicsPressureOpacityCurve",
                "dynamicsPressureOpacityJitterCurve",
                "dynamicsPressureSizeCurve",
                "dynamicsPressureSizeJitterCurve",
                "dynamicsPressureShapeRoundnessCurve",
                "dynamicsPressureBleedCurve",
                "dynamicsPressureCharge",
                "dynamicsPressureTransferModulationCurve",
                "dynamicsPressureShapeScatterCurve",
                "dynamicsPressureGrainMovementCurve",
                "dynamicsPressureGrainScaleCurve",
                "dynamicsPressureGrainZoomCurve",
                "dynamicsPressureGrainDepthCurve",
                "dynamicsPressureGrainDepthJitterCurve",
                "dynamicsPressureDilutionCurve",
                "dynamicsPressureAttackCurve",
                "dynamicsPressureWetPullCurve",
                "dynamicsPressureSmudgePullCurve",
                "dynamicsPressureGradeCurve",
                "dynamicsPressureBlurCurve",
                "dynamicsPressureBlurJitterCurve",
                "dynamicsPressureWetnessCurve",
                "dynamicsPressureHueCurve",
                "dynamicsPressureSaturationCurve",
                "dynamicsPressureBrightnessCurve",
                "dynamicsPressureSecondaryColorCurve",
                "dynamicsPressureHueJitterCurve",
                "dynamicsPressureSaturationJitterCurve",
                "dynamicsPressureLightnessJitterCurve",
                "dynamicsPressureDarknessJitterCurve",
                "dynamicsPressureSecondaryColorJitterCurve",
                "dynamicsPressureShapeRoundessVerticalJitterCurve",
                "dynamicsPressureShapeRoundessHorizontalJitterCurve",
                "dynamicsPressurePlotSpacingJitter",
                "dynamicsPressurePlotJitterLongitudinalCurve",
                "dynamicsPressureOverall",
            )
    }
}
