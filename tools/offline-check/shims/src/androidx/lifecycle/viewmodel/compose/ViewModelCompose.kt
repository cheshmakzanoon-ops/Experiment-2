// Harness shim of androidx.lifecycle:lifecycle-viewmodel-compose 2.9.x.
@file:JvmName("ShimViewModelComposeKt")
@file:Suppress("unused")

package androidx.lifecycle.viewmodel.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner

object LocalViewModelStoreOwner {
    private val LocalViewModelStoreOwner: ProvidableCompositionLocal<ViewModelStoreOwner?> = compositionLocalOf { null }

    val current: ViewModelStoreOwner?
        @Composable get() = LocalViewModelStoreOwner.current

    infix fun provides(viewModelStoreOwner: ViewModelStoreOwner): ProvidedValue<ViewModelStoreOwner?> =
        LocalViewModelStoreOwner.provides(viewModelStoreOwner)
}

@Composable
inline fun <reified VM : ViewModel> viewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner" },
    key: String? = null,
    factory: ViewModelProvider.Factory? = null,
): VM = if (key != null) ViewModelProvider(viewModelStoreOwner, factory ?: ViewModelProvider.NewInstanceFactory())[key, VM::class.java]
else ViewModelProvider(viewModelStoreOwner, factory ?: ViewModelProvider.NewInstanceFactory())[VM::class.java]
