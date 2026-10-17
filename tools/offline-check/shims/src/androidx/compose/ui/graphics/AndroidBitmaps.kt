// Harness shim: androidx.compose.ui.graphics Android interop (androidMain, absent from the desktop jars).
@file:JvmName("ShimAndroidBitmapsKt")
@file:Suppress("unused")

package androidx.compose.ui.graphics

fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = throw UnsupportedOperationException("harness stub")

fun ImageBitmap.asAndroidBitmap(): android.graphics.Bitmap = throw UnsupportedOperationException("harness stub")
