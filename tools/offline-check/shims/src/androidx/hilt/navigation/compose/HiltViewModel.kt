// Harness shim of androidx.hilt:hilt-navigation-compose 1.3.0 (hiltViewModel).
@file:JvmName("ShimHiltNavigationComposeKt")
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.hilt.navigation.compose

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry

@Composable
inline fun <reified VM : ViewModel> hiltViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
): VM = throw UnsupportedOperationException("harness stub")

@Composable
inline fun <reified VM : ViewModel, reified VMF : Any> hiltViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
    noinline creationCallback: (VMF) -> VM,
): VM = throw UnsupportedOperationException("harness stub")
