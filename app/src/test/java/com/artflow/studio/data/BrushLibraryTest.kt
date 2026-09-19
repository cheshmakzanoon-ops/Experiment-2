package com.artflow.studio.data

import com.artflow.studio.data.local.dao.SettingsDao
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.data.repository.BrushLibraryStore
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PressureResponse
import com.artflow.studio.domain.model.brush.SavedBrush
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BrushLibraryTest {
    @Test
    fun completeParametersAndUnicodeNamesRoundTrip() {
        val parameters =
            BrushParams(
                size = 412f,
                opacity = 0.7f,
                spacing = 0.31f,
                scatter = 1.2f,
                count = 3,
                rotation = 72f,
                taperStart = 0.2f,
                taperEnd = 0.8f,
                pressureToSize = 0.63f,
                pressureToOpacity = 0.37f,
                pressureCurve = BrushParams.PressureCurve.CUSTOM,
                customPressure = PressureResponse(0.2f, 0.7f, 0.9f),
                hueJitter = 0.1f,
                saturationJitter = 0.2f,
                brightnessJitter = 0.3f,
                colorPressure = true,
                sizeJitter = 0.4f,
                opacityJitter = 0.5f,
                smoothing = 0.6f,
                wetMix = 0.7f,
                flow = 0.8f,
                textureId = "canvas",
                textureScale = 2f,
                textureRotation = 33f,
                blendTexture = true,
                tiltInfluence = 0.9f,
                tiltToRotation = true,
                velocityToSize = 0.2f,
                velocityToOpacity = 0.4f,
                velocityToHue = 0.6f,
            )
        val saved = SavedBrush("brush-1", "Ink ;; \"blue\" / آبی", parameters)
        assertEquals(listOf(saved), BrushLibraryCodec.decode(BrushLibraryCodec.encode(listOf(saved))))
    }

    @Test
    fun malformedFutureAndAmbiguousLibrariesAreRejected() {
        val brush = SavedBrush("a", "Ink", BrushParams())
        val valid = BrushLibraryCodec.encode(listOf(brush))
        listOf("", "null", "[]", "{broken", valid.replace("\"version\":1", "\"version\":9")).forEach {
            assertTrue(runCatching { BrushLibraryCodec.decode(it) }.isFailure)
        }
        assertTrue(runCatching { BrushLibraryCodec.encode(listOf(brush, brush)) }.isFailure)
        assertTrue(runCatching { BrushLibraryCodec.encode(listOf(brush.copy(name = "\n"))) }.isFailure)
        assertTrue(runCatching { BrushLibraryCodec.encode(listOf(brush.copy(name = "x".repeat(81)))) }.isFailure)
        assertTrue(runCatching { BrushLibraryCodec.decode(" ".repeat(524_289)) }.isFailure)
    }

    @Test
    fun unsupportedAndNonFiniteParametersCannotEnterTheLibrary() {
        val original = BrushParams()
        listOf(
            original.copy(size = Float.NaN),
            original.copy(opacity = Float.POSITIVE_INFINITY),
            original.copy(count = 999),
            original.copy(textureId = "missing"),
            original.copy(velocityToHue = -1f),
        ).forEach { invalid ->
            assertTrue(runCatching { BrushLibraryCodec.encode(listOf(SavedBrush("a", "Ink", invalid))) }.isFailure)
        }
    }

    @Test(timeout = 10_000)
    fun saveRenameDeleteAndReopenPreserveFullBrushesAndUnrelatedRows() =
        runBlocking {
            val dao = MemoryDao()
            dao.rows["theme.mode"] = SettingsEntity("theme.mode", "DARK")
            val store = BrushLibraryStore(dao)
            assertTrue(store.brushes.first().isEmpty())
            assertEquals(0, dao.writes)
            store.saveCopy(" First ", BrushParams(wetMix = 0.7f))
            store.saveCopy("First", BrushParams(tiltToRotation = true))
            val saved = store.brushes.first()
            assertEquals(2, saved.map { it.id }.toSet().size)
            store.rename(saved[0].id, "Renamed")
            store.delete(saved[1].id)
            val reopened = BrushLibraryStore(dao).brushes.first().single()
            assertEquals(saved[0].id, reopened.id)
            assertEquals("Renamed", reopened.name)
            assertEquals(saved[0].parameters, reopened.parameters)
            assertEquals("DARK", dao.rows["theme.mode"]!!.value)
            assertEquals(2, dao.rows.size)
        }

    @Test(timeout = 10_000)
    fun failedWritesPublishNothingAndCanBeRetried() =
        runBlocking {
            val dao = MemoryDao()
            val store = BrushLibraryStore(dao)
            store.saveCopy("Keep", BrushParams())
            val before = store.brushes.first()
            val durable = dao.rows.toMap()
            dao.failure = IOException("Disk full")
            assertTrue(runCatching { store.rename(before.single().id, "Wrong") }.exceptionOrNull() is IOException)
            assertEquals(before, store.brushes.first())
            assertEquals(durable, dao.rows)
            dao.failure = null
            store.rename(before.single().id, "Updated")
            val reopened = BrushLibraryStore(dao).brushes.first()
            assertEquals("Updated", reopened.single().name)
        }

    @Test(timeout = 10_000)
    fun corruptDataIsNotOverwrittenByAnyMutation() =
        runBlocking {
            val dao = MemoryDao()
            val damaged = SettingsEntity(BrushLibraryStore.STORAGE_KEY, "{damaged")
            dao.rows[damaged.key] = damaged
            val store = BrushLibraryStore(dao)
            assertTrue(runCatching { store.brushes.first() }.isFailure)
            assertTrue(runCatching { store.saveCopy("No", BrushParams()) }.isFailure)
            assertTrue(runCatching { store.rename("a", "No") }.isFailure)
            assertTrue(runCatching { store.delete("a") }.isFailure)
            assertEquals(damaged, dao.rows[damaged.key])
            assertEquals(0, dao.writes)
            // A failed initial read is not cached as a successful empty library.
            dao.rows[damaged.key] = damaged.copy(value = BrushLibraryCodec.encode(emptyList()))
            assertTrue(store.brushes.first().isEmpty())
        }

    @Test(timeout = 10_000)
    fun overlappingSavesKeepBothCopiesAndCancellationCompletesAnEnteredCommit() =
        runBlocking {
            val dao = MemoryDao()
            val store = BrushLibraryStore(dao)
            store.brushes.first()
            dao.gate = CompletableDeferred()
            dao.entered = CompletableDeferred()
            val first = launch { store.saveCopy("First", BrushParams()) }
            dao.entered!!.await()
            val second = launch { store.saveCopy("Second", BrushParams(size = 7f)) }
            assertEquals(1, dao.writes)
            assertFalse(dao.rows.containsKey(BrushLibraryStore.STORAGE_KEY))
            first.cancel()
            dao.gate!!.complete(Unit)
            first.join()
            second.join()
            assertTrue(first.isCancelled)
            assertEquals(listOf("First", "Second"), BrushLibraryStore(dao).brushes.first().map { it.name })
        }

    @Test(timeout = 10_000)
    fun cancelledInitialReadAndOwnedListsDoNotCorruptTheStore() =
        runBlocking {
            val dao = MemoryDao()
            dao.readGate = CompletableDeferred()
            dao.readEntered = CompletableDeferred()
            val store = BrushLibraryStore(dao)
            val reading = launch { store.brushes.first() }
            dao.readEntered!!.await()
            reading.cancelAndJoin()
            dao.readGate!!.complete(Unit)
            store.saveCopy("One", BrushParams())
            store.saveCopy("Two", BrushParams())
            val snapshot = store.brushes.first()
            (snapshot as MutableList<SavedBrush>).clear()
            assertEquals(2, store.brushes.first().size)
            assertEquals(2, BrushLibraryStore(dao).brushes.first().size)
        }

    @Test(timeout = 10_000)
    fun libraryLimitAndMissingTargetsFailWithoutDroppingSavedBrushes() =
        runBlocking {
            val dao = MemoryDao()
            val brushes = List(128) { SavedBrush("brush-$it", "Brush $it", BrushParams()) }
            dao.rows[BrushLibraryStore.STORAGE_KEY] = SettingsEntity(BrushLibraryStore.STORAGE_KEY, BrushLibraryCodec.encode(brushes))
            val store = BrushLibraryStore(dao)
            assertTrue(runCatching { store.saveCopy("Too many", BrushParams()) }.isFailure)
            assertTrue(runCatching { store.rename("missing", "No") }.isFailure)
            assertTrue(runCatching { store.delete("missing") }.isFailure)
            assertEquals(brushes, store.brushes.first())
            assertEquals(0, dao.writes)
        }

    private class MemoryDao : SettingsDao {
        val rows = mutableMapOf<String, SettingsEntity>()
        var writes = 0
        var failure: IOException? = null
        var gate: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null
        var readEntered: CompletableDeferred<Unit>? = null

        override suspend fun getSettingByKey(key: String): SettingsEntity? {
            readEntered?.complete(Unit)
            readGate?.await()
            return rows[key]
        }

        override suspend fun insertSetting(setting: SettingsEntity) {
            writes++
            entered?.complete(Unit)
            gate?.await()
            failure?.let { throw it }
            rows[setting.key] = setting
        }

        override fun getAllSettings(): Flow<List<SettingsEntity>> = flowOf(rows.values.toList())

        override fun getSettingsByCategory(category: String): Flow<List<SettingsEntity>> = error("Unused")

        override suspend fun insertSettings(settings: List<SettingsEntity>): Unit = error("Unused")

        override suspend fun updateSetting(setting: SettingsEntity): Unit = error("Unused")

        override suspend fun deleteSetting(setting: SettingsEntity): Unit = error("Unused")

        override suspend fun deleteSettingByKey(key: String): Unit = error("Unused")

        override suspend fun updateSettingValue(
            key: String,
            value: String,
            timestamp: Long,
        ): Unit = error("Unused")
    }
}
