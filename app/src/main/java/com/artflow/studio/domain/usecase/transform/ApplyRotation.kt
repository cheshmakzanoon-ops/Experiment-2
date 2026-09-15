package com.artflow.studio.domain.usecase.transform

import com.artflow.studio.core.transform.TransformManager
import javax.inject.Inject

/**
 * Use case: Apply rotation transform
 * Phase 14: Transform System
 */
class ApplyRotation @Inject constructor(
    private val transformManager: TransformManager
) {
    /**
     * Execute rotation
     * @param deltaDegrees Rotation amount in degrees (positive = clockwise)
     */
    operator fun invoke(deltaDegrees: Float) {
        transformManager.rotate(deltaDegrees)
    }
}
