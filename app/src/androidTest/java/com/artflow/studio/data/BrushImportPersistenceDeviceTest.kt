package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.ProcreateBrushWriter
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.local.BrushFiles
import com.artflow.studio.data.local.BrushImports
import com.artflow.studio.data.local.GrainStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.repository.BrushLibraryStore
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.ImportedPressure
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.SavedBrush
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Exercises the shared Gallery/Brush Studio importer, real Android PNG decoding, private files and Room. */
@RunWith(AndroidJUnit4::class)
class BrushImportPersistenceDeviceTest {
    private lateinit var context: Context
    private lateinit var directory: File

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "brush-import-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
    }

    @After fun tearDown() = StorageFileTree.delete(directory)

    @Test fun procreateCompoundImportKeepsBothComponentsAndUnstretchedCoverageOnDisk() =
        runBlocking {
            val fixture = fixture()
            val bytes =
                ProcreateBrushWriter.brush(
                    ProcreateBrushWriter.Brush(
                        name = "Compound fixture",
                        parameters = fixture.parameters,
                        shape = fixture.tiles[0],
                        grain = fixture.tiles[1],
                        secondaryShape = fixture.tiles[2],
                        secondaryGrain = fixture.tiles[3],
                    ),
                )
            val imported = requireNotNull(BrushImports.read(context, bytes)).single()
            assertEquals("Compound fixture", imported.name)
            assertComponents(fixture, imported.parameters)
            assertStoredAndReloaded(fixture, imported)
        }

    @Test fun artbrushCompoundImportRemapsSecondaryImagesAndSurvivesStorageReload() =
        runBlocking {
            val fixture = fixture()
            val sourceIds =
                listOf(fixture.parameters, requireNotNull(fixture.parameters.dual).params)
                    .flatMap { listOfNotNull(it.shapeId, it.textureId) }
            sourceIds.zip(fixture.tiles).forEach { (id, tile) -> CustomGrains.register(id, tile) }
            val bytes = BrushFiles.encode(listOf(SavedBrush("source", "Compound fixture", fixture.parameters))).toByteArray()
            // No input image identity is content-addressed. The shared importer must remap
            // both components, and must use the file's pixels rather than these stale cache entries.
            fixture.parameters.imageIds.forEach { id -> CustomGrains.register(id, blackTile()) }
            val imported = requireNotNull(BrushImports.read(context, bytes)).single()
            assertComponents(fixture, imported.parameters)
            assertFalse(imported.parameters.imageIds.any { it in fixture.parameters.imageIds })
            assertStoredAndReloaded(fixture, imported)
        }

    @Test fun nonDivisibleProcreateImagesKeepLastRowAndColumnThroughDiskReload() {
        val sourceSide = 511
        val source =
            CustomGrains.Tile(
                sourceSide,
                ByteArray(sourceSide * sourceSide) { index ->
                    if (index / sourceSide == sourceSide - 1 || index % sourceSide == sourceSide - 1) 255.toByte() else 0
                },
            )
        val bytes =
            ProcreateBrushWriter.brush(
                ProcreateBrushWriter.Brush("Edge coverage", BrushParams(), shape = source, grain = source),
            )
        val imported = requireNotNull(BrushImports.read(context, bytes)).single()
        val id = requireNotNull(imported.parameters.shapeId)
        assertEquals("Both PNG images must use the same preserved coverage", id, imported.parameters.textureId)
        val tile = requireNotNull(CustomGrains.get(id))
        val side = CustomGrains.TILE
        assertEquals(256, side)
        assertEquals(side, tile.size)

        // In units of 1/256 source pixel, each output footprint is 511 units wide and
        // the white edge occupies 256 units. The corner is the union of those two strips;
        // its black square is 255 x 255. Integer areas give an independent box-filter oracle.
        fun coverage(
            whiteArea: Long,
            area: Long,
        ): Int = ((255L * whiteArea + area / 2L) / area).toInt()
        val cellSide = sourceSide.toLong()
        val edge = coverage(side.toLong(), cellSide)
        val blackCornerSide = cellSide - side
        val corner = coverage(cellSide * cellSide - blackCornerSide * blackCornerSide, cellSide * cellSide)
        assertEquals(128, edge)
        assertEquals(191, corner)
        val expected =
            ByteArray(side * side) { index ->
                val finalRow = index / side == side - 1
                val finalColumn = index % side == side - 1
                when {
                    finalRow && finalColumn -> corner.toByte()
                    finalRow || finalColumn -> edge.toByte()
                    else -> 0
                }
            }
        assertArrayEquals("Android PNG import must retain the final source row and column", expected, tile.values)
        assertEquals(CustomGrains.idFor(CustomGrains.Tile(side, expected)), id)

        val grains = GrainStorage.directory(context.filesDir)
        val file = File(grains, "$id.grain")
        assertTrue("Reduced coverage must be persisted", file.isFile)
        assertArrayEquals("Stored 511-to-256 coverage", expected, file.readBytes())
        CustomGrains.register(id, blackTile())
        try {
            val stale = requireNotNull(CustomGrains.get(id))
            assertTrue("Reload must replace the deliberately stale cache", stale.values.all { it == 0.toByte() })
            GrainStorage.loadAll(grains)
            assertArrayEquals("Coverage after disk reload", expected, requireNotNull(CustomGrains.get(id)).values)
        } finally {
            CustomGrains.register(id, tile)
        }
    }

    @Test fun importedPressureCurvesUseNativeTablesAndSurviveArtbrushReload() {
        val constant = PressureMapping(listOf(PressureMapping.Point(0f, 0.5f), PressureMapping.Point(1f, 0.5f)))
        val descending = PressureMapping(listOf(PressureMapping.Point(0f, 1f), PressureMapping.Point(1f, 0f)))
        val bent =
            PressureMapping(
                listOf(PressureMapping.Point(0f, 0f), PressureMapping.Point(0.5f, 0.25f), PressureMapping.Point(1f, 1f)),
            )
        val bytes =
            ProcreateBrushWriter.brushset(
                "Pressure fixture",
                listOf(
                    ProcreateBrushWriter.Brush("Constant", BrushParams(importedPressure = ImportedPressure(overall = constant))),
                    ProcreateBrushWriter.Brush("Descending", BrushParams(importedPressure = ImportedPressure(overall = descending))),
                    ProcreateBrushWriter.Brush("Bent", BrushParams(importedPressure = ImportedPressure(overall = bent))),
                ),
            )
        val imported = requireNotNull(BrushImports.read(context, bytes))
        assertEquals(listOf("Constant", "Descending", "Bent"), imported.map { it.name })
        val reopened = requireNotNull(BrushImports.read(context, BrushFiles.encode(imported).toByteArray()))
        assertEquals(imported, reopened)

        // The recovered native table scales coordinates by 1024 and divides sampled Y by 1023.
        // These affine cases have closed-form expectations, independent of the spline solver.
        // Running through the shared importer on API 26 also detects unsupported platform math calls.
        val pressures = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val descendingOutputs = listOf(1f, 0.7509775f, 0.5009775f, 0.2509775f, 0.0009775171f)
        // Independently derived native fixture values exercise nonzero second derivatives and FMA.
        val bentSamples = listOf(0.25f to 0.0780946314f, 0.5f to 0.2497563362f, 1f to 0.9992668629f)
        for (brushes in listOf(imported, reopened)) {
            val constantParams = brushes[0].parameters
            val descendingParams = brushes[1].parameters
            val bentParams = brushes[2].parameters
            assertEquals(
                constant.copy(interpolation = PressureMapping.Interpolation.NATIVE_SPLINE_1024),
                constantParams.importedPressure?.overall,
            )
            assertEquals(
                descending.copy(interpolation = PressureMapping.Interpolation.NATIVE_SPLINE_1024),
                descendingParams.importedPressure?.overall,
            )
            assertEquals(
                bent.copy(interpolation = PressureMapping.Interpolation.NATIVE_SPLINE_1024),
                bentParams.importedPressure?.overall,
            )
            pressures.forEachIndexed { index, pressure ->
                assertEquals("Constant response at $pressure", 0.50048876f, constantParams.pressureResponse(pressure), 0.000002f)
                assertEquals(
                    "Descending response at $pressure",
                    descendingOutputs[index],
                    descendingParams.pressureResponse(pressure),
                    0.000002f,
                )
            }
            bentSamples.forEach { (pressure, expected) ->
                assertEquals("Bent response at $pressure", expected, bentParams.pressureResponse(pressure), 0.000002f)
            }
        }
    }

    private suspend fun assertStoredAndReloaded(
        fixture: Fixture,
        imported: SavedBrush,
    ) {
        val name = "compound-import-${System.nanoTime()}.db"
        var database = Room.databaseBuilder(context, ArtFlowDatabase::class.java, name).build()
        try {
            val store = BrushLibraryStore(database.settingsDao())
            store.saveCopy(imported.name, imported.parameters)
            val saved = store.brushes.first().single()
            database.close()
            database = Room.databaseBuilder(context, ArtFlowDatabase::class.java, name).build()
            val reopened = BrushLibraryStore(database.settingsDao()).brushes.first().single()
            assertEquals(saved, reopened)
            assertComponents(fixture, reopened.parameters)

            val tilesById = fixture.tiles.associateBy(CustomGrains::idFor)
            val grains = GrainStorage.directory(context.filesDir)
            tilesById.forEach { (id, tile) ->
                val file = File(grains, "$id.grain")
                assertTrue("The importer must persist $id", file.isFile)
                assertArrayEquals("Stored coverage for $id", tile.values, file.readBytes())
                CustomGrains.register(id, blackTile())
            }
            try {
                // Overwrite the cached values before reloading, so an in-memory-only success
                // cannot hide a missing or damaged primary or secondary image file.
                GrainStorage.loadAll(grains)
                tilesById.forEach { (id, tile) ->
                    assertArrayEquals("Reloaded coverage for $id", tile.values, requireNotNull(CustomGrains.get(id)).values)
                }
            } finally {
                tilesById.forEach { (id, tile) -> CustomGrains.register(id, tile) }
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun assertComponents(
        fixture: Fixture,
        actual: BrushParams,
    ) {
        val expected = fixture.parameters
        assertEquals(expected.spacing, actual.spacing, 0.00001f)
        assertEquals(expected.size, actual.size, 0.0001f)
        assertEquals(expected.tiltInfluence, actual.tiltInfluence, 0.00001f)
        assertEquals(requireNotNull(expected.tiltOpacityInfluence), requireNotNull(actual.tiltOpacityInfluence), 0.00001f)
        val dual = requireNotNull(actual.dual) { "The shared importer discarded the Sub01 component" }
        val expectedDual = requireNotNull(expected.dual)
        assertEquals(DualBrush.Mode.ADD, dual.mode)
        assertEquals(3, dual.sourceBlendMode)
        assertEquals(expectedDual.params.size, dual.params.size, 0.0001f)
        assertEquals(expectedDual.params.spacing, dual.params.spacing, 0.00001f)
        assertEquals(expectedDual.params.tiltInfluence, dual.params.tiltInfluence, 0.00001f)
        assertEquals(requireNotNull(expectedDual.params.tiltOpacityInfluence), requireNotNull(dual.params.tiltOpacityInfluence), 0.00001f)
        assertNotEquals(actual.tiltOpacityInfluence, dual.params.tiltOpacityInfluence)
        val ids = listOf(actual.shapeId, actual.textureId, dual.params.shapeId, dual.params.textureId)
        assertEquals(fixture.tiles.map(CustomGrains::idFor), ids)
    }

    private data class Fixture(
        val parameters: BrushParams,
        val tiles: List<CustomGrains.Tile>,
    )

    private fun fixture(): Fixture {
        val side = CustomGrains.TILE
        val tiles =
            listOf(
                CustomGrains.Tile(side, ByteArray(side * side) { 255.toByte() }),
                CustomGrains.Tile(side, ByteArray(side * side) { 128.toByte() }),
                CustomGrains.Tile(side, ByteArray(side * side) { (96 + it % 17).toByte() }),
                CustomGrains.Tile(side, ByteArray(side * side) { (192 + it / side % 17).toByte() }),
            )
        val secondary =
            BrushParams(
                size = 32f,
                spacing = 0.24f,
                shapeId = "custom-ab1003",
                textureId = "custom-ab1004",
                blendTexture = true,
                tiltInfluence = 0.25f,
                tiltOpacityInfluence = 0.73f,
            )
        val primary =
            BrushParams(
                size = 64f,
                spacing = 0.12f,
                shapeId = "custom-ab1001",
                textureId = "custom-ab1002",
                blendTexture = true,
                tiltInfluence = 0.85f,
                tiltOpacityInfluence = 0.27f,
                dual = DualBrush(secondary, DualBrush.Mode.ADD, sourceBlendMode = 3),
            )
        return Fixture(primary, tiles)
    }

    private fun blackTile(): CustomGrains.Tile = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE))
}
