package com.artflow.studio.presentation.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.usecase.canvas.CreateCanvas
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the canvas screen
 * Manages drawing state, brush settings, and canvas operations
 */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    private val createCanvas: CreateCanvas,
    private val canvasRepository: CanvasRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<CanvasUiState>(CanvasUiState.Initializing)
    val uiState: StateFlow<CanvasUiState> = _uiState.asStateFlow()

    private val _brushParams = MutableStateFlow(BrushParams())
    val brushParams: StateFlow<BrushParams> = _brushParams.asStateFlow()

    private var currentCanvasId: Long = 0
    private var activeLayerId: Long = 1L
    private var currentStrokeId: Long = 0

    /**
     * Initialize a new canvas with specified dimensions
     */
    fun createNewCanvas(width: Int, height: Int, dpi: Int = 72) {
        viewModelScope.launch {
            try {
                _uiState.value = CanvasUiState.Loading
                currentCanvasId = createCanvas(width, height, dpi)
                
                // Observe canvas invalidation events
                launch {
                    canvasRepository.observeCanvasInvalidation().collect { event ->
                        handleCanvasInvalidation(event)
                    }
                }
                
                _uiState.value = CanvasUiState.Ready(currentCanvasId)
            } catch (e: Exception) {
                _uiState.value = CanvasUiState.Error(e.message ?: "Failed to create canvas")
            }
        }
    }

    /**
     * Handle canvas invalidation events from repository
     */
    private fun handleCanvasInvalidation(event: CanvasInvalidationEvent) {
        viewModelScope.launch {
            when (event) {
                is CanvasInvalidationEvent.Full -> {
                    // Trigger full canvas redraw
                    _uiState.value = (_uiState.value as? CanvasUiState.Ready)?.copy(
                        needsFullRedraw = true
                    ) ?: _uiState.value
                }
                is CanvasInvalidationEvent.Region -> {
                    // Trigger partial redraw of specific region
                    _uiState.value = (_uiState.value as? CanvasUiState.Ready)?.copy(
                        dirtyRegion = (event.left to event.top) to (event.right to event.bottom)
                    ) ?: _uiState.value
                }
                is CanvasInvalidationEvent.StrokeCompleted -> {
                    // Stroke completed, update UI
                    _uiState.value = (_uiState.value as? CanvasUiState.Ready)?.copy(
                        lastCompletedStrokeId = event.strokeId
                    ) ?: _uiState.value
                }
            }
        }
    }

    /**
     * Begin a new stroke at the given coordinates
     */
    fun onStrokeBegin(x: Float, y: Float, pressure: Float) {
        val params = _brushParams.value
        currentStrokeId = canvasRepository.beginStroke(x, y, pressure, params, activeLayerId)
    }

    /**
     * Continue the current stroke
     */
    fun onStrokeContinue(strokeId: Long, x: Float, y: Float, pressure: Float) {
        viewModelScope.launch {
            canvasRepository.continueStroke(strokeId, x, y, pressure)
        }
    }

    /**
     * End the current stroke
     */
    fun onStrokeEnd(strokeId: Long) {
        viewModelScope.launch {
            canvasRepository.endStroke(strokeId)
        }
    }

    /**
     * Update brush parameters
     */
    fun updateBrushParams(params: BrushParams) {
        _brushParams.value = params
    }

    /**
     * Update a specific brush parameter
     */
    fun updateBrushSize(size: Float) {
        _brushParams.value = _brushParams.value.copy(size = size)
    }

    /**
     * Update brush opacity
     */
    fun updateBrushOpacity(opacity: Float) {
        _brushParams.value = _brushParams.value.copy(opacity = opacity)
    }

    /**
     * Set the active layer for drawing
     */
    fun setActiveLayer(layerId: Long) {
        activeLayerId = layerId
    }

    /**
     * Save the current canvas state
     */
    fun saveCanvas(projectId: Long) {
        viewModelScope.launch {
            try {
                canvasRepository.saveCanvas(projectId)
            } catch (e: Exception) {
                // Handle save error
                _uiState.value = CanvasUiState.Error(e.message ?: "Failed to save canvas")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            canvasRepository.dispose()
        }
    }
}

/**
 * Sealed class representing canvas UI state
 */
sealed class CanvasUiState {
    object Initializing : CanvasUiState()
    object Loading : CanvasUiState()
    data class Ready(
        val canvasId: Long,
        val needsFullRedraw: Boolean = false,
        val dirtyRegion: Pair<Pair<Float, Float>, Pair<Float, Float>>? = null,
        val lastCompletedStrokeId: Long? = null
    ) : CanvasUiState()
    data class Error(val message: String) : CanvasUiState()
}
