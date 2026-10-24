package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.export.BinaryPlist
import com.artflow.studio.core.export.KeyedArchive
import com.artflow.studio.core.export.KeyedArchiveBuilder
import com.artflow.studio.data.local.BrushArchives
import com.artflow.studio.data.local.BrushFiles
import com.artflow.studio.data.local.BrushImports
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.repository.BrushLibraryStore
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.SavedBrush
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Metadata preservation through the actual Android importer and Room; no rendered-pressure claim. */
@RunWith(AndroidJUnit4::class)
class NativeCurvePersistenceDeviceTest {
    private lateinit var context: Context
    private lateinit var directory: File

    // Names come from the independently recovered native serializedKeyForCurve table, not
    // ProcreateBrush.AUXILIARY_CURVE_KEYS. Every coordinate is exactly representable in binary32.
    private val primary =
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
    private val secondary =
        primary.mapValues { (_, curve) -> curve.copy(points = curve.points.map { it.copy(output = 1f - it.output) }) }

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "native-curves-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
    }

    @After fun tearDown() {
        StorageFileTree.delete(directory)
    }

    @Test
    fun allNativeAuxiliaryCurvesSurviveCompoundStorageAndBrushInterchange(): Unit =
        runBlocking {
            assertEquals(39, NATIVE_KEYS.size)
            assertEquals(39, NATIVE_KEYS.toSet().size)
            assertEquals(35, primary.keys.count { it !in PRIMARY_FIELDS })
            assertTrue(RESTORED_KEYS.all { it in primary && it !in PRIMARY_FIELDS })
            val files = mutableListOf<File>()
            val source = compoundArchive()
            files += retain("native-curves-source.brush", source)
            assertNativeArchive(source)
            val imported = requireNotNull(BrushImports.read(context, source)).single()
            assertEquals(NAME, imported.name)
            assertComponents("Native import", imported.parameters)

            val databaseName = "native-curves-${System.nanoTime()}.db"
            var database = Room.databaseBuilder(context, ArtFlowDatabase::class.java, databaseName).build()
            try {
                val initialStore = BrushLibraryStore(database.settingsDao())
                initialStore.importAll(listOf(imported))
                val saved = initialStore.brushes.first().single()
                assertEquals(imported.parameters, saved.parameters)
                val initialRow = requireNotNull(database.settingsDao().getSettingByKey(BrushLibraryStore.STORAGE_KEY)).value
                database.close()
                database = Room.databaseBuilder(context, ArtFlowDatabase::class.java, databaseName).build()
                val reopenedStore = BrushLibraryStore(database.settingsDao())
                val reopened = reopenedStore.brushes.first().single()
                val reopenedRow = requireNotNull(database.settingsDao().getSettingByKey(BrushLibraryStore.STORAGE_KEY)).value
                files += retain("native-curves-library.json", reopenedRow.toByteArray(Charsets.UTF_8))
                assertEquals("Room close/reopen preserves the actual stored row", initialRow, reopenedRow)
                assertEquals("Room close/reopen preserves identity and complete parameters", saved, reopened)
                assertComponents("First Room reopen", reopened.parameters)
                assertRawLibrary(reopenedRow, reopened)

                val shared = BrushFiles.encode(listOf(reopened)).toByteArray(Charsets.UTF_8)
                files += retain("native-curves-shared.artbrush", shared)
                val fromShared = requireNotNull(BrushImports.read(context, shared)).single()
                assertEquals("ArtFlow interchange keeps the complete saved brush", reopened, fromShared)
                assertComponents("ArtFlow interchange", fromShared.parameters)

                // Use the same saved-brush export entry point as the real sharing route.
                val exported = BrushArchives.procreateBrush(fromShared)
                files += retain("native-curves-exported.brush", exported)
                assertNativeArchive(exported)
                val fromNative = requireNotNull(BrushImports.read(context, exported)).single()
                assertEquals(NAME, fromNative.name)
                assertComponents("Native reimport", fromNative.parameters)

                // A second durable save proves the native result is accepted by the full library
                // validator, including all 35 auxiliary graphs in its secondary component.
                reopenedStore.delete(reopened.id)
                reopenedStore.importAll(listOf(fromNative))
                val nativeSaved = reopenedStore.brushes.first().single()
                assertNotEquals(reopened.id, nativeSaved.id)
                assertEquals(fromNative.parameters, nativeSaved.parameters)
                val nativeRow = requireNotNull(database.settingsDao().getSettingByKey(BrushLibraryStore.STORAGE_KEY)).value
                database.close()
                database = Room.databaseBuilder(context, ArtFlowDatabase::class.java, databaseName).build()
                val finalSaved = BrushLibraryStore(database.settingsDao()).brushes.first().single()
                val finalRow = requireNotNull(database.settingsDao().getSettingByKey(BrushLibraryStore.STORAGE_KEY)).value
                files += retain("native-curves-reimported-library.json", finalRow.toByteArray(Charsets.UTF_8))
                assertEquals("Native result remains byte-exact in Room", nativeRow, finalRow)
                assertEquals("Native result survives a second database close/reopen", nativeSaved, finalSaved)
                assertComponents("Second Room reopen", finalSaved.parameters)
                assertRawLibrary(finalRow, finalSaved)
                retain("native-curves-report.json", report(files, saved, finalSaved).toString(2).toByteArray(Charsets.UTF_8))
            } finally {
                database.close()
                context.deleteDatabase(databaseName)
            }
        }

    private fun assertComponents(
        stage: String,
        params: BrushParams,
    ) {
        assertCurves("$stage primary", primary, params)
        assertCurves("$stage secondary", secondary, requireNotNull(params.dual).params)
    }

    private fun assertCurves(
        stage: String,
        expected: Map<String, PressureMapping>,
        params: BrushParams,
    ) {
        val auxiliary = expected.filterKeys { it !in PRIMARY_FIELDS }
        assertEquals("$stage auxiliary count", 35, params.importedAuxiliaryCurves.size)
        assertEquals("$stage all auxiliary points and interpolation", auxiliary, params.importedAuxiliaryCurves)
        RESTORED_KEYS.forEach { key ->
            assertEquals("$stage previously omitted $key", expected.getValue(key), params.importedAuxiliaryCurves[key])
        }
        val dedicated = requireNotNull(params.importedPressure)
        assertEquals(expected.getValue("dynamicsPressureOverall"), dedicated.overall)
        assertEquals(expected.getValue("dynamicsPressureSizeCurve"), dedicated.size)
        assertEquals(expected.getValue("dynamicsPressureOpacityCurve"), dedicated.flow)
        assertEquals(expected.getValue("dynamicsPressureTransferModulationCurve"), dedicated.opacity)
    }

    private fun assertRawLibrary(
        text: String,
        saved: SavedBrush,
    ) {
        val archive = JSONObject(text)
        assertEquals(1, archive.getInt("version"))
        val brushes = archive.getJSONArray("brushes")
        assertEquals(1, brushes.length())
        val brush = brushes.getJSONObject(0)
        assertEquals(saved.id, brush.getString("id"))
        assertEquals(NAME, brush.getString("name"))
        val params = brush.getJSONObject("parameters")
        assertJsonCurves(primary, params)
        assertJsonCurves(secondary, params.getJSONObject("dual").getJSONObject("params"))
    }

    private fun assertJsonCurves(
        expected: Map<String, PressureMapping>,
        params: JSONObject,
    ) {
        val auxiliary = params.getJSONObject("importedAuxiliaryCurves")
        assertEquals(expected.keys - PRIMARY_FIELDS.keys, auxiliary.keys().asSequence().toSet())
        expected.forEach { (key, curve) ->
            val field = PRIMARY_FIELDS[key]
            val stored = if (field == null) auxiliary.getJSONObject(key) else params.getJSONObject("importedPressure").getJSONObject(field)
            assertEquals("NATIVE_SPLINE_1024", stored.getString("interpolation"))
            val points = stored.getJSONArray("points")
            assertEquals(curve.points.size, points.length())
            curve.points.forEachIndexed { index, point ->
                val storedPoint = points.getJSONObject(index)
                assertEquals(point.input, storedPoint.getDouble("input").toFloat(), 0f)
                assertEquals(point.output, storedPoint.getDouble("output").toFloat(), 0f)
            }
        }
    }

    private fun compoundArchive(): ByteArray {
        val output = ByteArrayOutputStream()
        val components =
            listOf(
                "Brush.archive" to archive(primary, NAME),
                "Sub01/Brush.archive" to archive(secondary, "Secondary curves"),
            )
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in components) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun archive(
        curves: Map<String, PressureMapping>,
        name: String,
    ): ByteArray {
        val keyed = KeyedArchiveBuilder()
        val fields =
            linkedMapOf<String, Any?>(
                "\$class" to keyed.classOf("SilicaBrush"),
                "name" to keyed.add(name),
                "dualBlendMode" to 1L,
            )
        curves.forEach { (key, curve) ->
            val points = keyed.array(curve.points.map { keyed.add("{${it.input}, ${it.output}}") })
            fields[key] = keyed.instance("ValkyrieMagnitudinalCurve", mapOf("points" to points))
        }
        return keyed.encode(fields)
    }

    private fun assertNativeArchive(bytes: ByteArray) {
        val archives = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertTrue("This authored fixture has only settings archives", entry.name.endsWith("Brush.archive"))
                assertTrue("Archive paths must be unique", archives.put(entry.name, zip.readBytes()) == null)
            }
        }
        assertEquals(setOf("Brush.archive", "Sub01/Brush.archive"), archives.keys)
        for ((path, expected) in listOf("Brush.archive" to primary, "Sub01/Brush.archive" to secondary)) {
            val keyed = KeyedArchive(BinaryPlist.read(archives.getValue(path)))
            val actual =
                requireNotNull(keyed.root)
                    .mapNotNull { (key, value) ->
                        val fields = keyed.map(value) ?: return@mapNotNull null
                        if ("points" !in fields) return@mapNotNull null
                        assertEquals("$path $key native curve class", "ValkyrieMagnitudinalCurve", keyed.className(value))
                        key to
                            keyed.array(fields["points"]).map { item ->
                                val pair = requireNotNull(keyed.string(item)).removePrefix("{").removeSuffix("}").split(',')
                                assertEquals(2, pair.size)
                                PressureMapping.Point(pair[0].trim().toFloat(), pair[1].trim().toFloat())
                            }
                    }.toMap()
            assertEquals("$path complete native curve names and authored points", expected.mapValues { it.value.points }, actual)
        }
    }

    private fun report(
        files: List<File>,
        initial: SavedBrush,
        reimported: SavedBrush,
    ): JSONObject =
        JSONObject()
            .put("formatVersion", 1)
            .put(
                "scope",
                "Native curve metadata preservation through Android import, Room and brush interchange; no rendering or native-app parity claim.",
            ).put("sdk", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("components", 2)
            .put("nativeCurvesPerComponent", 39)
            .put("auxiliaryCurvesPerComponent", 35)
            .put("nativeOrder", JSONArray(NATIVE_KEYS))
            .put("dedicatedFields", JSONObject(PRIMARY_FIELDS))
            .put("previouslyOmittedKeys", JSONArray(RESTORED_KEYS))
            .put("expectedPrimary", jsonCurves(primary))
            .put("expectedSecondary", jsonCurves(secondary))
            .put("initialSavedId", initial.id)
            .put("nativeReimportSavedId", reimported.id)
            .put("databaseCloseReopenCycles", 2)
            .put("artbrushPreservesCompleteSavedBrush", true)
            .put("nativeRoundTripChecks", "Exact 39 keys, native curve class, control points and imported interpolation in each component")
            .put(
                "files",
                JSONObject().apply {
                    files.forEach { file ->
                        put(file.name, JSONObject().put("bytes", file.length()).put("sha256", sha256(file.readBytes())))
                    }
                },
            )

    private fun jsonCurves(curves: Map<String, PressureMapping>): JSONObject =
        JSONObject().apply {
            curves.forEach { (key, curve) ->
                put(key, JSONArray(curve.points.map { listOf(it.input, it.output) }))
            }
        }

    private fun retain(
        name: String,
        bytes: ByteArray,
    ): File = File(TestEvidence.directory(), name).apply { writeBytes(bytes) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val NAME = "Native curve lifecycle"
        val PRIMARY_FIELDS =
            mapOf(
                "dynamicsPressureOverall" to "overall",
                "dynamicsPressureSizeCurve" to "size",
                "dynamicsPressureOpacityCurve" to "flow",
                "dynamicsPressureTransferModulationCurve" to "opacity",
            )
        val RESTORED_KEYS =
            listOf(
                "dynamicsPressureBlurJitterCurve",
                "dynamicsPressureWetnessCurve",
                "dynamicsPressureSaturationJitterCurve",
                "dynamicsPressureLightnessJitterCurve",
                "dynamicsPressureDarknessJitterCurve",
                "dynamicsPressureSecondaryColorJitterCurve",
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
