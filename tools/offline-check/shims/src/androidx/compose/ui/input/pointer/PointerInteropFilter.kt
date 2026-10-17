// Harness shim: androidx.compose.ui.input.pointer.pointerInteropFilter (androidMain, experimental in ui 1.7).
@file:JvmName("ShimPointerInteropFilterKt")
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.input.pointer

import android.view.MotionEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier

@ExperimentalComposeUiApi
fun Modifier.pointerInteropFilter(
    requestDisallowInterceptTouchEvent: RequestDisallowInterceptTouchEvent? = null,
    onTouchEvent: (MotionEvent) -> Boolean,
): Modifier = throw UnsupportedOperationException("harness stub")

class RequestDisallowInterceptTouchEvent : (Boolean) -> Unit {
    var pointerInputFilter: Any? = null

    override fun invoke(disallowIntercept: Boolean) {}
}
