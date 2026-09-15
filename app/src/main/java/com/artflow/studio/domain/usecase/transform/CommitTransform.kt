package com.artflow.studio.domain.usecase.transform

import com.artflow.studio.core.transform.TransformManager
import javax.inject.Inject

/**
 * Use case: Commit and finalize the current transform operation
 * Phase 14: Transform System
 */
class CommitTransform @Inject constructor(
    private val transformManager: TransformManager
) {
    /**
     * Execute commit transform
     */
    operator fun invoke() {
        transformManager.commitTransform()
    }
}
