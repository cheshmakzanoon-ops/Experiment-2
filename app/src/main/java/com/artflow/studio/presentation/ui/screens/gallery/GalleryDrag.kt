package com.artflow.studio.presentation.ui.screens.gallery

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex

/** Where a dragged artwork landed: another artwork, or a stack. */
internal sealed interface GalleryDrop {
    data class OnProject(
        val projectId: Long,
    ) : GalleryDrop

    data class OnStack(
        val name: String,
    ) : GalleryDrop
}

/** Procreate's drag-to-stack: touch and hold an artwork, then drop it on another or on a stack. */
internal class GalleryDragState {
    var dragging by mutableStateOf<Long?>(null)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var pointer = Offset.Zero
    private val projects = mutableMapOf<Long, Rect>()
    private val stacks = mutableMapOf<String, Rect>()

    fun start(
        projectId: Long,
        local: Offset,
    ) {
        dragging = projectId
        offset = Offset.Zero
        pointer = (projects[projectId]?.topLeft ?: Offset.Zero) + local
    }

    fun move(delta: Offset) {
        offset += delta
        pointer += delta
    }

    fun cancel() {
        dragging = null
        offset = Offset.Zero
    }

    /** Ends the drag and reports what is under the finger, if anything other than the artwork itself. */
    fun drop(): Pair<Long, GalleryDrop>? {
        val id = dragging ?: return null
        cancel()
        val stack = stacks.entries.firstOrNull { it.value.contains(pointer) }?.key
        if (stack != null) return id to GalleryDrop.OnStack(stack)
        val other = projects.entries.firstOrNull { it.key != id && it.value.contains(pointer) }?.key ?: return null
        return id to GalleryDrop.OnProject(other)
    }

    fun placeProject(
        projectId: Long,
        bounds: Rect,
    ) {
        if (dragging != projectId) projects[projectId] = bounds
    }

    fun placeStack(
        name: String,
        bounds: Rect,
    ) {
        stacks[name] = bounds
    }
}

@Composable
internal fun rememberGalleryDrag() = remember { GalleryDragState() }

/** Lets an artwork card be picked up and dropped on another card. */
internal fun Modifier.draggableArtwork(
    state: GalleryDragState,
    projectId: Long,
    onDrop: (Long, GalleryDrop) -> Unit,
): Modifier =
    zIndex(if (state.dragging == projectId) 1f else 0f)
        .onGloballyPositioned { state.placeProject(projectId, it.boundsInRoot()) }
        .graphicsLayer {
            if (state.dragging == projectId) {
                translationX = state.offset.x
                translationY = state.offset.y
                scaleX = DRAG_SCALE
                scaleY = DRAG_SCALE
                alpha = DRAG_ALPHA
            }
        }.pointerInput(projectId) {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.start(projectId, it) },
                onDrag = { change, amount ->
                    change.consume()
                    state.move(amount)
                },
                onDragEnd = { state.drop()?.let { (id, target) -> onDrop(id, target) } },
                onDragCancel = state::cancel,
            )
        }

/** Marks a stack card as somewhere to drop artworks. */
internal fun Modifier.stackDropTarget(
    state: GalleryDragState,
    name: String,
): Modifier = onGloballyPositioned { state.placeStack(name, it.boundsInRoot()) }

/** A stack name not used yet: "Stack", then "Stack 2", "Stack 3"… */
internal fun newStackName(existing: List<String>): String =
    generateSequence(1) { it + 1 }
        .map { if (it == 1) "Stack" else "Stack $it" }
        .first { it !in existing }

private const val DRAG_SCALE = 1.05f
private const val DRAG_ALPHA = 0.9f
