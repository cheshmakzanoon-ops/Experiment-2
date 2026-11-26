package com.artflow.studio.core.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.util.IdentityHashMap

/** Coroutine recovery may copy a failure, but must retain its exact original as a cause. */
internal fun assertOriginalFailure(
    original: Throwable,
    observed: Throwable?,
) {
    val failure = observed ?: throw AssertionError("The operation must fail")
    assertEquals("Failure type must survive delivery", original.javaClass, failure.javaClass)
    assertEquals("Failure message must survive delivery", original.message, failure.message)
    val visited = IdentityHashMap<Throwable, Unit>()
    var current: Throwable? = failure
    while (current != null && visited.put(current, Unit) == null) {
        if (current === original) return
        current = current.cause
    }
    fail("The delivered failure must retain the exact original throwable in its cause chain")
}
