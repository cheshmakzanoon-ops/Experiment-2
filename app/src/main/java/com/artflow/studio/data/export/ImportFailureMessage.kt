package com.artflow.studio.data.export

import kotlinx.coroutines.CancellationException

/** Reports actionable import refusals while preserving cancellation and fatal runtime failures. */
internal fun importFailureMessage(
    failure: Throwable,
    fallback: String,
): String {
    if (failure is CancellationException || failure is Error) throw failure
    val explained = (failure is IllegalArgumentException || failure is IllegalStateException) && failure !is NumberFormatException
    return failure.message?.takeIf { explained && it.isNotBlank() } ?: fallback
}
