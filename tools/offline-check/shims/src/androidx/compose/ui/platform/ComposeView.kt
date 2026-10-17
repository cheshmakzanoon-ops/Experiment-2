// Harness shim: androidx.compose.ui.platform.ComposeView / ViewCompositionStrategy (androidMain only).
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.platform

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.lifecycle.Lifecycle

interface ViewCompositionStrategy {
    object DisposeOnDetachedFromWindow : ViewCompositionStrategy

    object DisposeOnDetachedFromWindowOrReleasedFromPool : ViewCompositionStrategy

    object DisposeOnViewTreeLifecycleDestroyed : ViewCompositionStrategy

    class DisposeOnLifecycleDestroyed(
        val lifecycle: Lifecycle,
    ) : ViewCompositionStrategy

    companion object {
        val Default: ViewCompositionStrategy = DisposeOnDetachedFromWindowOrReleasedFromPool
    }
}

abstract class AbstractComposeView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : ViewGroup(context, attrs, defStyleAttr) {
        fun setViewCompositionStrategy(strategy: ViewCompositionStrategy) {}

        fun setParentCompositionContext(parent: CompositionContext?) {}

        fun disposeComposition() {}

        @Composable
        abstract fun Content()

        override fun onLayout(
            changed: Boolean,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ) {}
    }

class ComposeView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : AbstractComposeView(context, attrs, defStyleAttr) {
        fun setContent(content: @Composable () -> Unit) {}

        @Composable
        override fun Content() {}
    }
