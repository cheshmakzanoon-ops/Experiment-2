// Harness shim: androidx.compose.ui.viewinterop.AndroidView (androidMain, absent from the desktop jars).
@file:JvmName("ShimAndroidViewKt")
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.viewinterop

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

val NoOpUpdate: View.() -> Unit = {}

@Composable
fun <T : View> AndroidView(
    factory: (Context) -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = NoOpUpdate,
) {
    throw UnsupportedOperationException("harness stub")
}

@Composable
fun <T : View> AndroidView(
    factory: (Context) -> T,
    modifier: Modifier = Modifier,
    onReset: ((T) -> Unit)? = null,
    onRelease: (T) -> Unit = NoOpUpdate,
    update: (T) -> Unit = NoOpUpdate,
) {
    throw UnsupportedOperationException("harness stub")
}
