// Harness shim of androidx.core:core (view/math/app/graphics packages).
@file:JvmName("ShimCore2Kt")
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.core.view

import android.view.View
import android.view.Window

class WindowInsetsControllerCompat(
    window: Window,
    view: View,
) {
    var isAppearanceLightStatusBars: Boolean = false

    var isAppearanceLightNavigationBars: Boolean = false

    var systemBarsBehavior: Int = 0

    fun hide(types: Int) {}

    fun show(types: Int) {}

    companion object {
        const val BEHAVIOR_DEFAULT: Int = 1
        const val BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: Int = 2
    }
}

object WindowCompat {
    const val FEATURE_ACTION_BAR: Int = 8
    const val FEATURE_ACTION_BAR_OVERLAY: Int = 9
    const val FEATURE_ACTION_MODE_OVERLAY: Int = 10

    @JvmStatic
    fun setDecorFitsSystemWindows(
        window: Window,
        decorFitsSystemWindows: Boolean,
    ) {}

    @JvmStatic
    fun getInsetsController(
        window: Window,
        view: View,
    ): WindowInsetsControllerCompat = WindowInsetsControllerCompat(window, view)
}
