package com.artflow.studio.domain.usecase.transform

import com.artflow.studio.core.transform.TransformManager
import javax.inject.Inject

/**
 * Use case: Apply translation transform
 * Phase 14: Transform System
 */
class ApplyTranslation @Inject constructor(
    private val transformManager: TransformManager
) {
    /**
     * Execute translation
     * @param deltaX Horizontal movement in pixels
     * @param deltaY Vertical movement in pixels
     */
    operator fun invoke(deltaX: Float, deltaY: Float) {
        transformManager.translate(deltaX, deltaY)
    }
}
