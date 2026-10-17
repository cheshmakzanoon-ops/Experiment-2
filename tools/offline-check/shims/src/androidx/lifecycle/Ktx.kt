// Harness shim of androidx.lifecycle:lifecycle-runtime-ktx extensions (lifecycleScope, repeatOnLifecycle, flowWithLifecycle).
@file:JvmName("ShimLifecycleKtxKt")
@file:Suppress("unused")

package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.CoroutineContext

abstract class LifecycleCoroutineScope internal constructor() : CoroutineScope {
    abstract val lifecycle: Lifecycle

    fun launchWhenCreated(block: suspend CoroutineScope.() -> Unit): Job = throw UnsupportedOperationException("harness stub")

    fun launchWhenStarted(block: suspend CoroutineScope.() -> Unit): Job = throw UnsupportedOperationException("harness stub")

    fun launchWhenResumed(block: suspend CoroutineScope.() -> Unit): Job = throw UnsupportedOperationException("harness stub")
}

private class StubLifecycleCoroutineScope(
    override val lifecycle: Lifecycle,
) : LifecycleCoroutineScope() {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default
}

val Lifecycle.coroutineScope: LifecycleCoroutineScope
    get() = StubLifecycleCoroutineScope(this)

val LifecycleOwner.lifecycleScope: LifecycleCoroutineScope
    get() = lifecycle.coroutineScope

suspend fun Lifecycle.repeatOnLifecycle(
    state: Lifecycle.State,
    block: suspend CoroutineScope.() -> Unit,
): Unit = throw UnsupportedOperationException("harness stub")

suspend fun LifecycleOwner.repeatOnLifecycle(
    state: Lifecycle.State,
    block: suspend CoroutineScope.() -> Unit,
): Unit = lifecycle.repeatOnLifecycle(state, block)

fun <T> Flow<T>.flowWithLifecycle(
    lifecycle: Lifecycle,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
): Flow<T> = this
