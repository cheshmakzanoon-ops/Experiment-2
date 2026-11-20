package com.artflow.studio.data.repository

import com.artflow.studio.core.lifecycle.retainCleanupFailure

/**
 * Publication cleanup must not replace the failure or cancellation that caused rollback.
 * The owner supplies any non-cancellable context needed to establish committed asset ownership.
 */
internal suspend fun <T> completeWithCleanup(
    action: suspend () -> T,
    cleanup: suspend () -> Unit,
): T {
    val outcome = runCatching { action() }
    val cleanupFailure = runCatching { cleanup() }.exceptionOrNull()
    if (cleanupFailure != null) {
        val original = outcome.exceptionOrNull() ?: throw cleanupFailure
        retainCleanupFailure(original, cleanupFailure)
    }
    return outcome.getOrThrow()
}
