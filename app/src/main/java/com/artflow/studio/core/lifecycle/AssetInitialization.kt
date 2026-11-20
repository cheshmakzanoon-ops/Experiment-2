package com.artflow.studio.core.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

enum class AssetStartupState {
    LOADING,
    READY,
    FAILED,
    CONTINUING_WITH_AVAILABLE,
    ;

    val allowsEditor: Boolean get() = this == READY || this == CONTINUING_WITH_AVAILABLE
}

/** Process-owned initialization: an Activity visit observes it rather than owning its lifetime. */
class AssetInitialization(
    private val scope: CoroutineScope,
    private val initialize: suspend () -> Unit,
    private val onFailure: (Exception) -> Unit = {},
) {
    private val lock = Any()
    private val _state = MutableStateFlow(AssetStartupState.LOADING)
    val state: StateFlow<AssetStartupState> = _state.asStateFlow()
    private var started = false
    private var running: Job? = null

    /** Starting another Activity must not duplicate work or silently retry a failed load. */
    fun start() =
        synchronized(lock) {
            if (!started) begin()
        }

    fun retry() =
        synchronized(lock) {
            if (_state.value == AssetStartupState.FAILED && running == null) begin()
        }

    /** Only an explicit choice from the failure screen permits use of partially loaded assets. */
    fun continueWithAvailableAssets() =
        synchronized(lock) {
            if (_state.value == AssetStartupState.FAILED) {
                _state.value = AssetStartupState.CONTINUING_WITH_AVAILABLE
            }
        }

    private fun begin() {
        started = true
        _state.value = AssetStartupState.LOADING
        var failure: Exception? = null
        val attempt =
            scope.launch(start = CoroutineStart.LAZY) {
                val error =
                    runCatching {
                        initialize()
                        coroutineContext.ensureActive()
                    }.exceptionOrNull()
                if (error != null) {
                    if (error is CancellationException || error !is Exception) throw error
                    failure = error
                    onFailure(error)
                }
            }
        running = attempt
        attempt.invokeOnCompletion { cancelledOrFailed ->
            complete(attempt, cancelledOrFailed != null || failure != null)
        }
        attempt.start()
    }

    private fun complete(
        attempt: Job,
        failed: Boolean,
    ) = synchronized(lock) {
        if (running === attempt) {
            running = null
            _state.value = if (failed) AssetStartupState.FAILED else AssetStartupState.READY
        }
    }
}
