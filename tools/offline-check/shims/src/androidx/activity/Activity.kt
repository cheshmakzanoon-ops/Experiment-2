// Harness shim of androidx.activity:activity 1.11.x (ComponentActivity, OnBackPressedDispatcher, edge-to-edge helpers).
@file:JvmName("ShimActivityKt")
@file:Suppress("unused")

package androidx.activity

import android.content.res.Resources
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

abstract class OnBackPressedCallback(
    enabled: Boolean,
) {
    var isEnabled: Boolean = enabled

    abstract fun handleOnBackPressed()

    open fun handleOnBackStarted(backEvent: BackEventCompat) {}

    open fun handleOnBackProgressed(backEvent: BackEventCompat) {}

    open fun handleOnBackCancelled() {}

    fun remove() {}
}

class BackEventCompat(
    val touchX: Float,
    val touchY: Float,
    val progress: Float,
    val swipeEdge: Int,
) {
    companion object {
        const val EDGE_LEFT = 0
        const val EDGE_RIGHT = 1
    }
}

class OnBackPressedDispatcher(
    fallbackOnBackPressed: Runnable? = null,
) {
    fun addCallback(onBackPressedCallback: OnBackPressedCallback) {}

    fun addCallback(
        owner: LifecycleOwner,
        onBackPressedCallback: OnBackPressedCallback,
    ) {}

    fun hasEnabledCallbacks(): Boolean = false

    fun onBackPressed() {}
}

interface OnBackPressedDispatcherOwner : LifecycleOwner {
    val onBackPressedDispatcher: OnBackPressedDispatcher
}

fun OnBackPressedDispatcher.addCallback(
    owner: LifecycleOwner? = null,
    enabled: Boolean = true,
    onBackPressed: OnBackPressedCallback.() -> Unit,
): OnBackPressedCallback {
    val callback =
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() = onBackPressed()
        }
    if (owner != null) addCallback(owner, callback) else addCallback(callback)
    return callback
}

private class HarnessRegistry : ActivityResultRegistry() {
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {}
}

open class ComponentActivity :
    android.app.Activity,
    LifecycleOwner,
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory,
    OnBackPressedDispatcherOwner,
    ActivityResultRegistryOwner,
    ActivityResultCaller {
    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val dispatcher = OnBackPressedDispatcher()
    private val resultRegistry: ActivityResultRegistry = HarnessRegistry()

    constructor() : super()

    constructor(contentLayoutId: Int) : super()

    override val lifecycle: Lifecycle
        get() = registry

    override val viewModelStore: ViewModelStore
        get() = store

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = ViewModelProvider.NewInstanceFactory()

    override val onBackPressedDispatcher: OnBackPressedDispatcher
        get() = dispatcher

    override val activityResultRegistry: ActivityResultRegistry
        get() = resultRegistry

    override fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: ActivityResultCallback<O>,
    ): ActivityResultLauncher<I> = resultRegistry.register("harness", this, contract, callback)

    override fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        registry: ActivityResultRegistry,
        callback: ActivityResultCallback<O>,
    ): ActivityResultLauncher<I> = registry.register("harness", this, contract, callback)
}

class SystemBarStyle private constructor(
    internal val lightScrim: Int,
    internal val darkScrim: Int,
    internal val nightMode: Int,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun auto(
            lightScrim: Int,
            darkScrim: Int,
            detectDarkMode: (Resources) -> Boolean = { false },
        ): SystemBarStyle = SystemBarStyle(lightScrim, darkScrim, 0)

        @JvmStatic
        fun dark(scrim: Int): SystemBarStyle = SystemBarStyle(scrim, scrim, 2)

        @JvmStatic
        fun light(
            scrim: Int,
            darkScrim: Int,
        ): SystemBarStyle = SystemBarStyle(scrim, darkScrim, 1)
    }
}

fun ComponentActivity.enableEdgeToEdge(
    statusBarStyle: SystemBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
    navigationBarStyle: SystemBarStyle = SystemBarStyle.auto(0x1b000000, 0x80000000.toInt()),
) {}
