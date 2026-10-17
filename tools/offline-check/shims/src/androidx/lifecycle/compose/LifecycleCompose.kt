// Harness shim of androidx.lifecycle:lifecycle-runtime-compose 2.9.x.
@file:JvmName("ShimLifecycleComposeKt")
@file:Suppress("unused")

package androidx.lifecycle.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

val LocalLifecycleOwner: ProvidableCompositionLocal<LifecycleOwner> =
    compositionLocalOf { error("CompositionLocal LocalLifecycleOwner not present") }

@Composable
fun <T> StateFlow<T>.collectAsStateWithLifecycle(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
): State<T> = collectAsState(context)

@Composable
fun <T> StateFlow<T>.collectAsStateWithLifecycle(
    lifecycle: Lifecycle,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
): State<T> = collectAsState(context)

@Composable
fun <T : R, R> Flow<T>.collectAsStateWithLifecycle(
    initialValue: R,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
): State<R> = collectAsState(initialValue, context)

@Composable
fun <T : R, R> Flow<T>.collectAsStateWithLifecycle(
    initialValue: R,
    lifecycle: Lifecycle,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
): State<R> = collectAsState(initialValue, context)
