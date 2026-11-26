package com.artflow.studio.core.lifecycle

import java.util.IdentityHashMap

/** Runs cleanup even on an early return, retaining failures from nested scopes in release order. */
internal inline fun <T> withCleanup(
    cleanup: () -> Unit,
    block: () -> T,
): T {
    var failure: Throwable? = null
    try {
        return runCatching(block).onFailure { failure = it }.getOrThrow()
    } finally {
        val cleanupResult = runCatching(cleanup)
        val original = failure
        if (original == null) {
            cleanupResult.getOrThrow()
        } else {
            cleanupResult.exceptionOrNull()?.let { retainCleanupFailure(original, it) }
        }
    }
}

/** Retains cleanup diagnostics when coroutine stack recovery replaces an intermediate failure copy. */
internal fun retainCleanupFailure(
    original: Throwable,
    cleanup: Throwable,
) {
    if (cleanup === original) return
    // Coroutine stack recovery may replace a same-type/message copy at a later dispatcher
    // boundary and retain only its original cause. Keep the diagnostic on that original too.
    // Ordinary causes with a different type or message keep their existing diagnostic scope.
    val visited = IdentityHashMap<Throwable, Unit>()
    var current = original
    while (visited.put(current, Unit) == null) {
        if (cleanup !== current && current.suppressed.none { it === cleanup }) current.addSuppressed(cleanup)
        val cause = current.cause
        if (cause == null || cause.javaClass != original.javaClass || cause.message != original.message) break
        current = cause
    }
}
