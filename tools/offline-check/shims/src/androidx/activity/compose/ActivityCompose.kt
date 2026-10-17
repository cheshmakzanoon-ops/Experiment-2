// Harness shim of androidx.activity:activity-compose 1.11.x (setContent, BackHandler, rememberLauncherForActivityResult).
@file:JvmName("ShimActivityComposeKt")
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.core.app.ActivityOptionsCompat

fun ComponentActivity.setContent(
    parent: CompositionContext? = null,
    content: @Composable () -> Unit,
) {
    throw UnsupportedOperationException("harness stub")
}

@Composable
fun BackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    throw UnsupportedOperationException("harness stub")
}

class ManagedActivityResultLauncher<I, O> internal constructor(
    private val delegate: ActivityResultLauncher<I>?,
    private val currentContract: ActivityResultContract<I, O>,
) : ActivityResultLauncher<I>() {
    override val contract: ActivityResultContract<I, *> get() = currentContract

    override fun launch(
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        delegate?.launch(input, options)
    }

    @Deprecated("Registration is automatically handled by rememberLauncherForActivityResult")
    override fun unregister() {
        throw UnsupportedOperationException("Registration is automatically handled by rememberLauncherForActivityResult")
    }
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> = throw UnsupportedOperationException("harness stub")

object LocalActivityResultRegistryOwner {
    private val LocalComposition: ProvidableCompositionLocal<ActivityResultRegistryOwner?> = compositionLocalOf { null }

    val current: ActivityResultRegistryOwner?
        @Composable get() = LocalComposition.current

    infix fun provides(registryOwner: ActivityResultRegistryOwner) = LocalComposition.provides(registryOwner)
}

object LocalOnBackPressedDispatcherOwner {
    private val LocalOnBackPressedDispatcherOwner: ProvidableCompositionLocal<OnBackPressedDispatcherOwner?> = compositionLocalOf { null }

    val current: OnBackPressedDispatcherOwner?
        @Composable get() = LocalOnBackPressedDispatcherOwner.current

    infix fun provides(dispatcherOwner: OnBackPressedDispatcherOwner) = LocalOnBackPressedDispatcherOwner.provides(dispatcherOwner)
}
