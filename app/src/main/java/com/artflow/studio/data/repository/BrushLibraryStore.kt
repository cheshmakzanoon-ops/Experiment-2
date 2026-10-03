package com.artflow.studio.data.repository

import com.artflow.studio.data.local.dao.SettingsDao
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One atomic Room row, separate from legacy partial BrushEntity records and artwork documents. */
@Singleton
class BrushLibraryStore
    @Inject
    constructor(
        private val dao: SettingsDao,
    ) {
        private val mutex = Mutex()
        private val snapshot = MutableStateFlow<List<SavedBrush>>(emptyList())
        private var loaded = false

        val brushes: Flow<List<SavedBrush>> =
            flow {
                mutex.withLock { load() }
                emitAll(snapshot.map { it.toList() })
            }

        private val favouriteIds = MutableStateFlow<Set<String>?>(null)

        /** Brushes starred into the Favourites set: preset ids and "saved-" ids of saved brushes. */
        val favourites: Flow<Set<String>> =
            flow {
                mutex.withLock { loadFavourites() }
                emitAll(favouriteIds.map { it.orEmpty() })
            }

        suspend fun setFavourite(
            id: String,
            favourite: Boolean,
        ) {
            require(id.matches(FAVOURITE_ID)) { "Unsupported brush identity" }
            mutex.withLock {
                val current = loadFavourites()
                val updated = if (favourite) current + id else current - id
                if (updated == current) return@withLock
                withContext(NonCancellable) {
                    dao.insertSetting(SettingsEntity(key = FAVOURITES_KEY, value = updated.sorted().joinToString("\n"), category = "brush"))
                    favouriteIds.value = updated
                }
            }
        }

        private suspend fun loadFavourites(): Set<String> {
            favouriteIds.value?.let { return it }
            val stored = dao.getSettingByKey(FAVOURITES_KEY)?.value.orEmpty()
            return stored
                .lines()
                .filter { it.matches(FAVOURITE_ID) }
                .take(MAX_FAVOURITES)
                .toSet()
                .also { favouriteIds.value = it }
        }

        private val brushSets = MutableStateFlow<Map<String, List<String>>?>(null)

        /** The artist's own brush sets, by name, in creation order. */
        val sets: Flow<Map<String, List<String>>> =
            flow {
                mutex.withLock { loadSets() }
                emitAll(brushSets.map { it.orEmpty() })
            }

        /** Applies [change] to the brush sets and stores the result; invalid names or sizes are refused. */
        suspend fun editSets(change: (Map<String, List<String>>) -> Map<String, List<String>>) {
            mutex.withLock {
                val current = loadSets()
                val updated = change(current)
                require(updated.size <= MAX_SETS && updated.all { (name, ids) -> validSet(name, ids) }) { "Unsupported brush set" }
                if (updated == current) return@withLock
                val encoded = setsJson.encodeToString(SETS_SERIALIZER, updated)
                withContext(NonCancellable) {
                    dao.insertSetting(SettingsEntity(key = SETS_KEY, value = encoded, category = "brush"))
                    brushSets.value = updated
                }
            }
        }

        private fun validSet(
            name: String,
            ids: List<String>,
        ): Boolean {
            val validName = BrushLibraryCodec.validName(name) && name.length <= MAX_SET_NAME
            return validName && ids.size <= MAX_FAVOURITES && ids.all { it.matches(FAVOURITE_ID) }
        }

        private suspend fun loadSets(): Map<String, List<String>> {
            brushSets.value?.let { return it }
            val stored = dao.getSettingByKey(SETS_KEY)?.value
            val decoded =
                stored
                    ?.let { runCatching { setsJson.decodeFromString(SETS_SERIALIZER, it) }.getOrNull() }
                    .orEmpty()
                    .filter { (name, ids) -> validSet(name, ids) }
                    .entries
                    .take(MAX_SETS)
                    .associate { it.key to it.value }
            brushSets.value = decoded
            return decoded
        }

        suspend fun saveCopy(
            name: String,
            parameters: BrushParams,
        ) {
            val brush = SavedBrush(UUID.randomUUID().toString(), name.trim(), parameters)
            mutate { it + brush }
        }

        /** Adds shared brushes as new copies, keeping their names. */
        suspend fun importAll(brushes: List<SavedBrush>) {
            mutate { existing -> existing + brushes.map { it.copy(id = UUID.randomUUID().toString(), name = it.name.trim()) } }
        }

        suspend fun rename(
            id: String,
            name: String,
        ) {
            mutate { existing ->
                check(existing.any { it.id == id }) { "This saved brush no longer exists" }
                existing.map { if (it.id == id) it.copy(name = name.trim()) else it }
            }
        }

        suspend fun delete(id: String) {
            mutate { existing ->
                check(existing.any { it.id == id }) { "This saved brush no longer exists" }
                existing.filterNot { it.id == id }
            }
        }

        /** A read/decode failure leaves loaded false and never overwrites the original stored row. */
        private suspend fun load() {
            if (loaded) return
            val row = dao.getSettingByKey(STORAGE_KEY)
            val decoded = withContext(Dispatchers.Default) { row?.value?.let(BrushLibraryCodec::decode) ?: emptyList() }
            snapshot.value = decoded
            loaded = true
        }

        private suspend fun mutate(transform: (List<SavedBrush>) -> List<SavedBrush>) {
            mutex.withLock {
                load()
                val updated = transform(snapshot.value.toList()).toList()
                val encoded = withContext(Dispatchers.Default) { BrushLibraryCodec.encode(updated) }
                if (updated == snapshot.value) return@withLock
                currentCoroutineContext().ensureActive()
                // Once the small transaction begins, complete both storage and publication.
                // A failed write changes neither state nor the previous durable library.
                withContext(NonCancellable) {
                    dao.insertSetting(SettingsEntity(key = STORAGE_KEY, value = encoded, category = "brush"))
                    snapshot.value = updated
                }
            }
        }

        companion object {
            const val STORAGE_KEY = "brush.savedLibrary.v1"
            const val FAVOURITES_KEY = "brush.favourites.v1"
            const val SETS_KEY = "brush.sets.v1"
            private const val MAX_SETS = 32
            private const val MAX_SET_NAME = 40
            private val setsJson = Json { ignoreUnknownKeys = true }
            private val SETS_SERIALIZER = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
            private const val MAX_FAVOURITES = 256
            private val FAVOURITE_ID = Regex("[a-zA-Z0-9-]{1,72}")
        }
    }
