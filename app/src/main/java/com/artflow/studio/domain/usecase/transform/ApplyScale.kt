package com.artflow.studio.domain.usecase.transform

import com.artflow.studio.core.transform.TransformManager
import javax.inject.Inject

/**
 * Use case: Apply scale transform
 * Phase 14: Transform System
 */
class ApplyScale @Inject constructor(
    private val transformManager: TransformManager
) {
    /**
     * Execute scaling
     * @param scaleFactorX Horizontal scale factor (1.0 = 100%)
     * @param scaleFactorY Vertical scale factor (null for uniform scaling)
     */
    operator fun invoke(scaleFactorX: Float, scaleFactorY: Float? = null) {
        transformManager.scale(scaleFactorX, scaleFactorY)
    }
}
