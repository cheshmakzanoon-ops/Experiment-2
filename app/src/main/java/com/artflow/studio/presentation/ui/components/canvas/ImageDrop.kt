package com.artflow.studio.presentation.ui.components.canvas

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext

/**
 * Lets images be dragged in from another app (split screen or a floating window) and dropped on
 * the canvas, like dragging a photo into Procreate; [onImage] gets the dropped picture's address.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.imageDropTarget(onImage: (Uri) -> Unit): Modifier {
    val activity = LocalContext.current.activity()
    val latest by rememberUpdatedState(onImage)
    val target =
        remember(activity) {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val drag = event.toAndroidDragEvent()
                    val clip = drag.clipData ?: return false
                    val uri = (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri } ?: return false
                    // Another app's content can be read only once the drop grants it.
                    activity?.requestDragAndDropPermissions(drag)
                    latest(uri)
                    return true
                }
            }
        }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.mimeTypes().any { it.startsWith("image/") } },
        target = target,
    )
}

private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
