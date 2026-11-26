package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.render.GrainRegistry
import com.artflow.studio.data.local.dao.SettingsDao
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.data.repository.BrushLibraryStore
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException
import java.util.Base64

/** Owns the complete parse/install/database boundary, including a cancelled commit delivery. */
class BrushImportTransactionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun laterTextureConflictCannotLeaveTheFirstTextureInstalled() {
        val first = tile(37)
        val second = tile(79)
        val context = mock(Context::class.java)
        val files = temporary.newFolder()
        `when`(context.filesDir).thenReturn(files)
        val directory = GrainStorage.directory(files)
        val firstId = CustomGrains.idFor(first)
        val secondId = CustomGrains.idFor(second)
        val previous = CustomGrains.get(firstId)
        val occupied = requireNotNull(GrainStorage.fileFor(directory, secondId))
        assertTrue(occupied.mkdirs())
        val unrelated = File(occupied, "keep.txt").apply { writeText("Original content") }
        try {
            assertNull(BrushImports.read(context, encoded(first, second)))
            assertFalse(
                "A failed batch must not leave an earlier texture on disk",
                requireNotNull(GrainStorage.fileFor(directory, firstId)).exists(),
            )
            assertSame(previous, CustomGrains.get(firstId))
            assertEquals("Original content", unrelated.readText())
        } finally {
            // Keep this regression isolated even when deliberately run against the old implementation.
            CustomGrains.get(firstId)?.let { current ->
                if (previous == null) CustomGrains.removeIfSame(firstId, current) else CustomGrains.register(firstId, previous)
            }
        }
    }

    @Test
    fun aRejectedCompleteBatchPublishesNeitherFilesNorLibraryRows() =
        runBlocking {
            val fixture = Fixture(retainedTiles = 1)
            val failure = runCatching { fixture.import(encoded(tile(11), tile(12))) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(fixture.registry.ids().isEmpty())
            assertFalse(fixture.directory.exists())
            assertEquals(0, fixture.dao.writes)
        }

    @Test
    fun duplicateImagesShareOneAdmissionAndBothReferencesSurviveReopen() =
        runBlocking {
            val fixture = Fixture(retainedTiles = 1)
            val image = tile(83)
            assertEquals(1, fixture.import(encoded(image, image)))
            val id = CustomGrains.idFor(image)
            assertEquals(listOf(id), fixture.registry.ids())
            assertEquals(
                1,
                fixture.directory
                    .listFiles()
                    .orEmpty()
                    .size,
            )
            assertArrayEquals(image.values, fixture.file(id).readBytes())
            val saved = BrushLibraryStore(fixture.dao).brushes.first().single()
            assertEquals(id, saved.parameters.shapeId)
            assertEquals(id, saved.parameters.textureId)
            assertEquals(1, fixture.dao.writes)
        }

    @Test
    fun failedLibraryWriteRollsBackNewAssetsAndPreservesSharedFilesAndRows() =
        runBlocking {
            val fixture = Fixture()
            val shared = tile(23)
            val new = tile(29)
            val sharedId = fixture.save(shared)
            fixture.store.saveCopy("Existing", BrushParams(shapeId = sharedId))
            val before = fixture.dao.rows.toMap()
            fixture.dao.failure = IOException("Injected full storage")
            val failure = runCatching { fixture.import(encoded(shared, new)) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(before, fixture.dao.rows)
            assertEquals(listOf(sharedId), fixture.registry.ids())
            assertArrayEquals(shared.values, fixture.file(sharedId).readBytes())
            assertSame(shared, fixture.registry.get(sharedId))
            assertFalse(fixture.file(CustomGrains.idFor(new)).exists())
            assertEquals(
                listOf("Existing"),
                fixture.store.brushes
                    .first()
                    .map { it.name },
            )
        }

    @Test
    fun failedTextureDirectorySyncCannotReachLibraryPublication(): Unit =
        runBlocking {
            for (failParent in listOf(false, true)) {
                val fixture = Fixture()
                val shared = tile(24)
                val added = tile(30)
                val sharedId = fixture.save(shared)
                val addedId = CustomGrains.idFor(added)
                fixture.store.saveCopy("Existing", BrushParams(shapeId = sharedId))
                val before = fixture.dao.rows.toMap()
                val writesBefore = fixture.dao.writes
                val failure = IOException("Injected texture directory sync failure")
                val synced = mutableListOf<File>()
                val transaction =
                    GrainStorage.ImportTransaction(fixture.registry) { directory ->
                        synced += directory
                        assertArrayEquals(added.values, fixture.file(addedId).readBytes())
                        assertNull(fixture.registry.get(addedId))
                        if (!failParent || directory == fixture.directory.parentFile) throw failure
                    }
                val actual =
                    runCatching {
                        BrushImports.importInto(fixture.directory, encoded(shared, added), transaction, fixture.store::importAll)
                    }.exceptionOrNull()
                assertTrue(actual is IOException)
                assertEquals(failure.message, actual?.message)
                assertEquals(if (failParent) listOf(fixture.directory, fixture.directory.parentFile) else listOf(fixture.directory), synced)
                assertEquals(writesBefore, fixture.dao.writes)
                assertEquals(before, fixture.dao.rows)
                assertSame(shared, fixture.registry.get(sharedId))
                assertArrayEquals(shared.values, fixture.file(sharedId).readBytes())
                assertNull(fixture.registry.get(addedId))
                assertFalse(fixture.file(addedId).exists())
                assertEquals(
                    listOf("Existing"),
                    fixture.store.brushes
                        .first()
                        .map { it.name },
                )
            }
        }

    @Test
    fun textureEntriesAndTheirParentAreSyncedBeforeTheLibraryReceivesThem(): Unit =
        runBlocking {
            val fixture = Fixture()
            val first = tile(25)
            val second = tile(31)
            val synced = mutableListOf<File>()
            val transaction =
                GrainStorage.ImportTransaction(fixture.registry) { directory ->
                    synced += directory
                    assertEquals(0, fixture.dao.writes)
                    assertTrue(fixture.registry.ids().isEmpty())
                }
            val count =
                BrushImports.importInto(fixture.directory, encoded(first, second), transaction) { brushes, onCommitted ->
                    assertEquals(listOf(fixture.directory, fixture.directory.parentFile), synced)
                    fixture.store.importAll(brushes, onCommitted)
                }
            assertEquals(1, count)
            assertEquals(1, fixture.dao.writes)
            assertArrayEquals(first.values, fixture.file(CustomGrains.idFor(first)).readBytes())
            assertArrayEquals(second.values, fixture.file(CustomGrains.idFor(second)).readBytes())
            assertEquals(1, BrushLibraryStore(fixture.dao).brushes.first().size)
        }

    @Test
    fun fullSavedLibraryRejectsTheBatchWithoutKeepingItsAssets() =
        runBlocking {
            val fixture = Fixture()
            val full = List(BrushLibraryCodec.MAX_BRUSHES) { SavedBrush("brush-$it", "Saved $it", BrushParams()) }
            fixture.dao.rows[BrushLibraryStore.STORAGE_KEY] =
                SettingsEntity(BrushLibraryStore.STORAGE_KEY, BrushLibraryCodec.encode(full))
            val before = fixture.dao.rows.toMap()
            val failure = runCatching { fixture.import(encoded(tile(41), tile(43))) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(before, fixture.dao.rows)
            assertEquals(0, fixture.dao.writes)
            assertTrue(fixture.registry.ids().isEmpty())
            assertTrue(
                fixture.directory
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
            assertEquals(full, BrushLibraryStore(fixture.dao).brushes.first())
        }

    @Test(timeout = 10_000)
    fun cancellationBeforeTheLibraryCommitRollsBackEveryProvisionalAsset() =
        runBlocking {
            val fixture = Fixture()
            val entered = CompletableDeferred<Unit>()
            val first = tile(47)
            val second = tile(53)
            val importing =
                launch {
                    BrushImports.importInto(fixture.directory, encoded(first, second), fixture.transaction()) { _, _ ->
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            entered.await()
            assertEquals(2, fixture.registry.ids().size)
            importing.cancelAndJoin()
            assertTrue(fixture.registry.ids().isEmpty())
            assertTrue(
                fixture.directory
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
            assertEquals(0, fixture.dao.writes)
            // Closing the cancelled transaction also releases its complete transient allowance.
            assertEquals(1, fixture.import(encoded(first, second)))
        }

    @Test(timeout = 10_000)
    fun cancellationDuringDatabaseCommitKeepsTheWholePersistedBatch() =
        runBlocking {
            val fixture = Fixture()
            val first = tile(59)
            val second = tile(61)
            fixture.dao.pause = true
            val importing = launch { fixture.import(encoded(first, second)) }
            fixture.dao.entered.await()
            assertEquals(2, fixture.registry.ids().size)
            assertFalse(fixture.dao.rows.containsKey(BrushLibraryStore.STORAGE_KEY))
            importing.cancel()
            fixture.dao.release.complete(Unit)
            importing.join()
            assertTrue(importing.isCancelled)
            val reopened = BrushLibraryStore(fixture.dao).brushes.first().single()
            assertEquals(CustomGrains.idFor(first), reopened.parameters.shapeId)
            assertEquals(CustomGrains.idFor(second), reopened.parameters.textureId)
            for (image in listOf(first, second)) {
                val id = CustomGrains.idFor(image)
                assertArrayEquals(image.values, fixture.file(id).readBytes())
                assertArrayEquals(image.values, requireNotNull(fixture.registry.get(id)).values)
            }
            // A successful commit releases the transient batch claim even if delivery was cancelled.
            fixture.dao.pause = false
            assertEquals(1, fixture.import(encoded(first, second)))
        }

    @Test
    fun aCallbackThatDoesNotCommitCannotLeaveProvisionalAssets() =
        runBlocking {
            val fixture = Fixture()
            val failure =
                runCatching {
                    BrushImports.importInto(fixture.directory, encoded(tile(67), tile(71)), fixture.transaction()) { _, _ -> }
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(fixture.registry.ids().isEmpty())
            assertTrue(
                fixture.directory
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun unreadableInputDoesNotInstallAssetsOrCallPersistence() =
        runBlocking {
            val fixture = Fixture()
            val failure = runCatching { fixture.import("not a brush".toByteArray()) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(fixture.directory.exists())
            assertTrue(fixture.registry.ids().isEmpty())
            assertEquals(0, fixture.dao.writes)
        }

    @Test
    fun legacyReadPropagatesCancellationInsteadOfCallingItAnUnknownFile() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenThrow(CancellationException("Cancelled provider"))
        assertThrows(CancellationException::class.java) { BrushImports.read(context, encoded(tile(73), tile(89))) }
    }

    private inner class Fixture(
        retainedTiles: Int = 4,
    ) {
        val directory = GrainStorage.directory(temporary.newFolder())
        val registry = GrainRegistry(retainedTiles * TILE_BYTES, 4 * TILE_BYTES)
        val dao = ControlledDao()
        val store = BrushLibraryStore(dao)

        fun transaction() = GrainStorage.ImportTransaction(registry)

        suspend fun import(bytes: ByteArray): Int? = BrushImports.importInto(directory, bytes, transaction(), store::importAll)

        fun file(id: String): File = requireNotNull(GrainStorage.fileFor(directory, id))

        fun save(image: CustomGrains.Tile): String =
            CustomGrains.idFor(image).also { id -> GrainStorage.save(directory, id, image, registry) }
    }

    private class ControlledDao : SettingsDao by mock(SettingsDao::class.java) {
        val rows = linkedMapOf<String, SettingsEntity>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = false
        var writes = 0
        var failure: IOException? = null

        override suspend fun getSettingByKey(key: String) = rows[key]

        override suspend fun insertSetting(setting: SettingsEntity) {
            writes++
            if (pause) {
                entered.complete(Unit)
                release.await()
            }
            failure?.let { throw it }
            rows[setting.key] = setting
        }
    }

    private fun encoded(
        first: CustomGrains.Tile,
        second: CustomGrains.Tile,
    ): ByteArray {
        val brushes = listOf(SavedBrush("source", "Imported fixture", BrushParams(shapeId = FIRST, textureId = SECOND)))
        return buildJsonObject {
            put("format", "artflow-brush-1")
            put("library", BrushLibraryCodec.encode(brushes))
            put(
                "tiles",
                buildJsonObject {
                    put(FIRST, JsonPrimitive(Base64.getEncoder().encodeToString(first.values)))
                    put(SECOND, JsonPrimitive(Base64.getEncoder().encodeToString(second.values)))
                },
            )
        }.toString().toByteArray()
    }

    private fun tile(seed: Int): CustomGrains.Tile =
        CustomGrains.Tile(CustomGrains.TILE, ByteArray(TILE_BYTES.toInt()) { index -> (seed + index * 31 + index / 257).toByte() })

    companion object {
        private const val FIRST = "custom-a11"
        private const val SECOND = "custom-b22"
        private const val TILE_BYTES = 256L * 256
    }
}
