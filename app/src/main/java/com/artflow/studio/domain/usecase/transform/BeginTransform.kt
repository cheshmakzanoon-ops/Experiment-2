package com.artflow.studio.domain.usecase.transform

import android.graphics.RectF
import com.artflow.studio.core.transform.TransformManager
import javax.inject.Inject

/**
 * Use case: Begin a transform operation on selected content
 * Phase 14: Transform System
 */
class BeginTransform @Inject constructor(
    private val transformManager: TransformManager
) {
    /**
     * Execute begin transform
     * @param bounds The bounding box of content to transform
     */
    operator fun invoke(bounds: RectF) {
        transformManager.beginTransform(bounds)
    }
}
