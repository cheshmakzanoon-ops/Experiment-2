package com.artflow.studio.data.local

import com.artflow.studio.core.export.BinaryPlist
import com.artflow.studio.core.export.KeyedArchive
import com.artflow.studio.core.export.ProcreateBrush
import com.artflow.studio.core.export.ProcreateBrushWriter
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.SavedBrush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/** Actual exported fields, checked against the pinned native archive-decoder/setter contract. */
class BrushSpacingExportTest {
    @Test
    fun localSingleAndCompoundComponentsAllUseVersionOne() {
        val local = BrushParams(spacing = 0.25f)
        assertSpacing(exported(local).getValue(PRIMARY), 0.25, 1L)
        for (mode in DualBrush.Mode.entries) {
            val components = exported(local.copy(dual = DualBrush(BrushParams(spacing = 0.5f), mode)))
            assertEquals(setOf(PRIMARY, SECONDARY), components.keys)
            assertSpacing(components.getValue(PRIMARY), 0.25, 1L)
            assertSpacing(components.getValue(SECONDARY), 0.5, 1L)
            assertTrue("The primary really carries a combine key", "dualBlendMode" in components.getValue(PRIMARY))
        }
    }

    @Test
    fun untouchedExplicitVersionsSurviveEveryStoredImportVersion() {
        for (importVersion in 0..2) {
            for (nativeVersion in listOf(-1L, 0L, 1L, 7L)) {
                val original =
                    ProcreateBrush
                        .parameters(mapOf("plotSpacing" to 0.25, "plotSpacingVersion" to nativeVersion))
                        .copy(sourceImportVersion = importVersion)
                val unchanged = exported(original).getValue(PRIMARY)
                assertSpacing(unchanged, 0.25, nativeVersion)
                val compound = exported(original.copy(dual = DualBrush(BrushParams()))).getValue(PRIMARY)
                assertSpacing(compound, 0.25, nativeVersion)
                assertEquals(nativeVersion.toString(), original.importedSourceScalars.getValue("plotSpacingVersion").value)
            }
        }
    }

    @Test
    fun addingASecondaryCannotChangeAnImplicitVersionOneSourceToVersionZero() {
        for (importVersion in 0..2) {
            val original =
                ProcreateBrush
                    .parameters(mapOf("plotSpacing" to 0.25))
                    .copy(sourceImportVersion = importVersion)
            assertSpacing(exported(original).getValue(PRIMARY), 0.25, 1L)
            val withSecondary = original.copy(dual = DualBrush(BrushParams(), DualBrush.Mode.ADD))
            val primary = exported(withSecondary).getValue(PRIMARY)
            assertEquals(3L, primary["dualBlendMode"])
            assertSpacing(primary, 0.25, 1L)
            assertFalse("Export must not mutate the saved raw fields", "plotSpacingVersion" in original.importedSourceScalars)
        }
    }

    @Test
    fun legacyCombineKeyPresenceKeepsVersionZeroAcrossCombineChangesAndRemoval() {
        // The native fallback tests presence, including a zero-valued key; it does not test truthiness.
        for (sourceMode in listOf(0L, 1L, 28L)) {
            val original = ProcreateBrush.parameters(mapOf("plotSpacing" to 0.25, "dualBlendMode" to sourceMode))
            val compound = original.copy(dual = DualBrush(BrushParams(), sourceBlendMode = sourceMode.toInt()))
            assertSpacing(exported(compound).getValue(PRIMARY), 0.25, 0L)
            val changed = compound.copy(dual = requireNotNull(compound.dual).copy(mode = DualBrush.Mode.ADD))
            val changedPrimary = exported(changed).getValue(PRIMARY)
            assertEquals(3L, changedPrimary["dualBlendMode"])
            assertSpacing(changedPrimary, 0.25, 0L)
            val removed = exported(compound.copy(dual = null))
            assertEquals(setOf(PRIMARY), removed.keys)
            assertSpacing(removed.getValue(PRIMARY), 0.25, 0L)
        }
    }

    @Test
    fun extendedOnlySourceKeepsVersionOneWhenAnEditedCombineModeAddsTheLegacyKey() {
        val source = ProcreateBrush.parameters(mapOf("plotSpacing" to 0.25, "dualBlendModeExtended" to 34L))
        val compound = source.copy(dual = DualBrush(BrushParams(), sourceBlendMode = 34))
        val unchanged = exported(compound).getValue(PRIMARY)
        assertEquals(34L, unchanged["dualBlendModeExtended"])
        assertFalse("dualBlendMode" in unchanged)
        assertSpacing(unchanged, 0.25, 1L)

        val edited = compound.copy(dual = requireNotNull(compound.dual).copy(mode = DualBrush.Mode.SUBTRACT))
        val changed = exported(edited).getValue(PRIMARY)
        assertFalse("dualBlendModeExtended" in changed)
        assertEquals(7L, changed["dualBlendMode"])
        assertSpacing(changed, 0.25, 1L)
    }

    @Test
    fun deliberateSpacingEditsUseVersionOneIndependentlyForEachComponent() {
        val primary = ProcreateBrush.parameters(mapOf("plotSpacing" to 0.25, "plotSpacingVersion" to 0L))
        val secondary = ProcreateBrush.parameters(mapOf("plotSpacing" to 0.25, "dualBlendMode" to 0L))
        val original = primary.copy(dual = DualBrush(secondary))
        val primaryEdited = exported(original.copy(spacing = 0.5f))
        assertSpacing(primaryEdited.getValue(PRIMARY), 0.5, 1L)
        assertSpacing(primaryEdited.getValue(SECONDARY), 0.25, 0L)
        val secondaryEdited = exported(original.copy(dual = DualBrush(secondary.copy(spacing = 0.5f))))
        assertSpacing(secondaryEdited.getValue(PRIMARY), 0.25, 0L)
        assertSpacing(secondaryEdited.getValue(SECONDARY), 0.5, 1L)
        assertSpacing(exported(secondary.copy(spacing = 0.5f)).getValue(PRIMARY), 0.5, 1L)
    }

    @Test
    fun unchangedClampedSourceSpacingStaysExactUntilEdited() {
        for (raw in listOf(0.0, 0.005, 1.5)) {
            val source = ProcreateBrush.parameters(mapOf("plotSpacing" to raw, "plotSpacingVersion" to 0L))
            val compound = source.copy(dual = DualBrush(BrushParams()))
            assertSpacing(exported(compound).getValue(PRIMARY), raw, 0L)
            assertSpacing(exported(compound.copy(spacing = 0.5f)).getValue(PRIMARY), 0.5, 1L)
            assertEquals(
                raw,
                source.importedSourceScalars
                    .getValue("plotSpacing")
                    .value
                    .toDouble(),
                0.0,
            )
        }
    }

    @Test
    fun omittedSourceSpacingIsNotSynthesizedByAnUnrelatedEdit() {
        for (legacy in listOf(false, true)) {
            val fields =
                buildMap<String, Any> {
                    put("name", "Omitted spacing")
                    if (legacy) put("dualBlendMode", 0L)
                }
            val source = ProcreateBrush.parameters(fields).copy(dual = DualBrush(BrushParams()))
            val unchanged = exported(source).getValue(PRIMARY)
            assertFalse("plotSpacing" in unchanged)
            assertEquals(if (legacy) 0L else 1L, unchanged["plotSpacingVersion"])
            val edited = exported(source.copy(spacing = 0.25f)).getValue(PRIMARY)
            assertSpacing(edited, 0.25, 1L)
        }
    }

    @Test
    fun libraryArtbrushAndBrushsetKeepIndependentComponentHistories() {
        val secondary = ProcreateBrush.parameters(mapOf("plotSpacing" to 0.5, "dualBlendModeExtended" to 34L))
        val primary =
            ProcreateBrush
                .parameters(mapOf("plotSpacing" to 0.25, "dualBlendMode" to 0L))
                .copy(dual = DualBrush(secondary))
        val saved = listOf(SavedBrush("spacing-source", "Spacing histories", primary))
        val library = BrushLibraryCodec.decode(BrushLibraryCodec.encode(saved))
        val artbrush = BrushFiles.decode(BrushFiles.encode(library)).first
        assertEquals(saved, artbrush)
        val bytes =
            ProcreateBrushWriter.brushset(
                "Spacing set",
                artbrush.map { ProcreateBrushWriter.Brush(it.name, it.parameters) },
            )
        val components = archives(bytes)
        assertEquals(2, components.size)
        assertSpacing(components.entries.single { !it.key.endsWith("/$SECONDARY") }.value, 0.25, 0L)
        assertSpacing(components.entries.single { it.key.endsWith("/$SECONDARY") }.value, 0.5, 1L)
        assertEquals(saved, artbrush)
        assertFalse("plotSpacingVersion" in primary.importedSourceScalars)
        assertFalse("plotSpacingVersion" in secondary.importedSourceScalars)
    }

    private fun assertSpacing(
        fields: Map<String, Any?>,
        spacing: Double,
        version: Long,
    ) {
        assertEquals(spacing, (fields.getValue("plotSpacing") as Number).toDouble(), 0.0)
        assertEquals(version, fields["plotSpacingVersion"])
        // Reproduce only the native archive-version decision, not ArtFlow's spacing importer.
        val nativeVersion =
            (fields["plotSpacingVersion"] as? Number)?.toLong() ?: if ("dualBlendMode" in fields) 0L else 1L
        assertEquals(version, nativeVersion)
    }

    private fun exported(params: BrushParams): Map<String, Map<String, Any?>> =
        archives(ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Spacing contract", params)))

    private fun archives(bytes: ByteArray): Map<String, Map<String, Any?>> =
        ZipInputStream(bytes.inputStream()).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith(PRIMARY)) {
                        val archive = KeyedArchive(BinaryPlist.read(zip.readBytes()))
                        put(entry.name, requireNotNull(archive.root))
                    }
                }
            }
        }

    private companion object {
        const val PRIMARY = "Brush.archive"
        const val SECONDARY = "Sub01/Brush.archive"
    }
}
