package com.artflow.studio.presentation.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artflow.studio.data.repository.BrushLibraryStore
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Storage failures stay visible and retryable; a successful save is never reported before Room commits. */
@HiltViewModel
class BrushLibraryViewModel
    @Inject
    constructor(
        private val store: BrushLibraryStore,
    ) : ViewModel() {
        data class State(
            val brushes: List<SavedBrush> = emptyList(),
            val loading: Boolean = true,
            val busy: Boolean = false,
            val error: String? = null,
            val revision: Long = 0L,
        )

        private val mutableState = MutableStateFlow(State())
        val state = mutableState.asStateFlow()
        private var observation: Job? = null
        private val errors =
            CoroutineExceptionHandler { _, error ->
                Timber.e(error, "Saved brush operation failed")
                mutableState.value =
                    mutableState.value.copy(
                        loading = false,
                        busy = false,
                        error = "Saved brushes could not be read or changed. Stored data was not replaced. ${error.message.orEmpty()}",
                    )
            }

        init {
            retry()
        }

        fun retry() {
            if (mutableState.value.busy) return
            observation?.cancel()
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            observation =
                viewModelScope.launch(errors) {
                    store.brushes.collect { mutableState.value = mutableState.value.copy(brushes = it, loading = false) }
                }
        }

        fun saveCopy(
            name: String,
            parameters: BrushParams,
        ) = edit { store.saveCopy(name, parameters) }

        fun rename(
            id: String,
            name: String,
        ) = edit { store.rename(id, name) }

        fun delete(id: String) = edit { store.delete(id) }

        private fun edit(operation: suspend () -> Unit) {
            if (mutableState.value.loading || mutableState.value.busy) return
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            viewModelScope.launch(errors) {
                try {
                    operation()
                    mutableState.value = mutableState.value.copy(revision = mutableState.value.revision + 1L)
                } finally {
                    mutableState.value = mutableState.value.copy(busy = false)
                }
            }
        }
    }
