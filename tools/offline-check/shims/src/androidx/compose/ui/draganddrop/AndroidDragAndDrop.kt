// Harness shim: androidx.compose.ui.draganddrop Android interop (androidMain, absent from the desktop jars).
@file:JvmName("ShimAndroidDragAndDropKt")
@file:Suppress("unused")

package androidx.compose.ui.draganddrop

import android.view.DragEvent

fun DragAndDropEvent.toAndroidDragEvent(): DragEvent = throw UnsupportedOperationException("harness stub")

fun DragAndDropEvent.mimeTypes(): Set<String> = throw UnsupportedOperationException("harness stub")
