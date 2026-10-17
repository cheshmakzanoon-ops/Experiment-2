// Harness shim: androidx.compose.ui.graphics Path interop with android.graphics.Path (androidMain only).
@file:JvmName("ShimAndroidPathsKt")
@file:Suppress("unused")

package androidx.compose.ui.graphics

fun Path.asAndroidPath(): android.graphics.Path = throw UnsupportedOperationException("harness stub")

fun android.graphics.Path.asComposePath(): Path = throw UnsupportedOperationException("harness stub")
