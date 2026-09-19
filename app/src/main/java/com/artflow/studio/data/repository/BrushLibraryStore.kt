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

        suspend fun saveCopy(
            name: String,
            parameters: BrushParams,
        ) {
            val brush = SavedBrush(UUID.randomUUID().toString(), name.trim(), parameters)
            mutate { it + brush }
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
        }
    }
