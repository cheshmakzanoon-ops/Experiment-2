package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.canvas.SeamlessTileMode
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns live Physical Paint Studio state (30Hz watercolor diffusion loop, accelerometer tilt gravity,
 * 3D impasto raking light, Dry/Re-Wet commands) and Seamless Tiling state for [CanvasViewModel].
 */
class PhysicalStudioController(
    private val canvasRepository: CanvasRepository,
    private val scope: CoroutineScope,
    private val errorHandler: CoroutineExceptionHandler,
    private val onLayersChanged: () -> Unit,
) {
    private val _seamlessTileMode = MutableStateFlow(SeamlessTileMode.NONE)
    val seamlessTileMode: StateFlow<SeamlessTileMode> = _seamlessTileMode.asStateFlow()

    private val _physicalLightAngle = MutableStateFlow(45f)
    val physicalLightAngle: StateFlow<Float> = _physicalLightAngle.asStateFlow()

    private val _physicalLightElevation = MutableStateFlow(35f)
    val physicalLightElevation: StateFlow<Float> = _physicalLightElevation.asStateFlow()

    private val _physicalImpastoDepth = MutableStateFlow(0.5f)
    val physicalImpastoDepth: StateFlow<Float> = _physicalImpastoDepth.asStateFlow()

    private val _physicalWetPaused = MutableStateFlow(false)
    val physicalWetPaused: StateFlow<Boolean> = _physicalWetPaused.asStateFlow()

    private val _physicalTiltGravity = MutableStateFlow(false)
    val physicalTiltGravity: StateFlow<Boolean> = _physicalTiltGravity.asStateFlow()

    private val _hasActiveWetPaint = MutableStateFlow(false)
    val hasActiveWetPaint: StateFlow<Boolean> = _hasActiveWetPaint.asStateFlow()

    @Volatile
    private var gravityX: Float = 0f

    @Volatile
    private var gravityY: Float = 0f

    private var diffusionJob: Job? = null

    fun syncFromDocument() {
        _seamlessTileMode.value = canvasRepository.seamlessTileMode()
        _physicalLightAngle.value = canvasRepository.physicalLightAngle()
        _physicalLightElevation.value = canvasRepository.physicalLightElevation()
        _physicalImpastoDepth.value = canvasRepository.physicalImpastoDepth()
        _hasActiveWetPaint.value = canvasRepository.hasActiveWetPaint()
        if (_hasActiveWetPaint.value && !_physicalWetPaused.value) {
            ensureDiffusionRunning()
        }
    }

    fun setSeamlessTileMode(mode: SeamlessTileMode) {
        canvasRepository.setSeamlessTileMode(mode)
        _seamlessTileMode.value = mode
    }

    fun setPhysicalLighting(
        angle: Float,
        elevation: Float,
        impastoDepth: Float,
    ) {
        canvasRepository.setPhysicalLighting(angle, elevation, impastoDepth)
        _physicalLightAngle.value = canvasRepository.physicalLightAngle()
        _physicalLightElevation.value = canvasRepository.physicalLightElevation()
        _physicalImpastoDepth.value = canvasRepository.physicalImpastoDepth()
    }

    fun toggleWetPaused() {
        val next = !_physicalWetPaused.value
        _physicalWetPaused.value = next
        if (next) {
            cancelDiffusion()
        } else if (canvasRepository.hasActiveWetPaint()) {
            ensureDiffusionRunning()
        }
    }

    fun toggleTiltGravity() {
        _physicalTiltGravity.value = !_physicalTiltGravity.value
        if (!_physicalTiltGravity.value) {
            gravityX = 0f
            gravityY = 0f
        }
    }

    fun updateTiltGravity(
        gx: Float,
        gy: Float,
    ) {
        if (_physicalTiltGravity.value) {
            gravityX = gx.coerceIn(-1f, 1f)
            gravityY = gy.coerceIn(-1f, 1f)
        }
    }

    fun notifyStrokeCommitted() {
        val wet = canvasRepository.hasActiveWetPaint()
        _hasActiveWetPaint.value = wet
        if (wet && !_physicalWetPaused.value) {
            ensureDiffusionRunning()
        }
    }

    fun dryActiveLayer() {
        scope.launch(errorHandler) {
            val activeId = canvasRepository.getActiveLayerId()
            if (canvasRepository.dryPhysicalLayer(activeId)) {
                _hasActiveWetPaint.value = canvasRepository.hasActiveWetPaint()
                onLayersChanged()
            }
        }
    }

    fun dryAllLayers() {
        scope.launch(errorHandler) {
            if (canvasRepository.dryAllPhysicalLayers()) {
                _hasActiveWetPaint.value = false
                cancelDiffusion()
                onLayersChanged()
            }
        }
    }

    fun rewetActiveLayer() {
        scope.launch(errorHandler) {
            val activeId = canvasRepository.getActiveLayerId()
            if (canvasRepository.rewetPhysicalLayer(activeId)) {
                _hasActiveWetPaint.value = true
                if (!_physicalWetPaused.value) ensureDiffusionRunning()
                onLayersChanged()
            }
        }
    }

    fun cancelDiffusion() {
        diffusionJob?.cancel()
        diffusionJob = null
    }

    private fun ensureDiffusionRunning() {
        if (diffusionJob?.isActive == true) return
        diffusionJob =
            scope.launch(errorHandler) {
                while (!_physicalWetPaused.value) {
                    val stillWet =
                        canvasRepository.stepPhysicalDiffusion(
                            gravityX = if (_physicalTiltGravity.value) gravityX else 0f,
                            gravityY = if (_physicalTiltGravity.value) gravityY else 0f,
                        )
                    _hasActiveWetPaint.value = stillWet
                    if (!stillWet) break
                    delay(DIFFUSION_TICK_MS)
                }
                diffusionJob = null
            }
    }

    companion object {
        private const val DIFFUSION_TICK_MS = 33L
    }
}
