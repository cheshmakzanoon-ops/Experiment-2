package com.artflow.studio.presentation.ui.screens.gallery

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Where a dragged artwork landed: on another artwork, beside one, or on a stack. */
internal sealed interface GalleryDrop {
    data class OnProject(
        val projectId: Long,
    ) : GalleryDrop

    data class OnStack(
        val name: String,
    ) : GalleryDrop

    /** Between artworks: just before [projectId], or just after it. */
    data class Beside(
        val projectId: Long,
        val after: Boolean,
    ) : GalleryDrop
}

/**
 * Procreate's drag in the gallery: touch and hold an artwork, then drop it on another artwork or a stack to stack them, or at the
 * left or right edge of an artwork to put it there.
 */
internal class GalleryDragState {
    var dragging by mutableStateOf<Long?>(null)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** What dropping right now would do; the card or stack it names shows a hint. */
    var hover by mutableStateOf<GalleryDrop?>(null)
        private set

    /** The area the cards scroll in, in window coordinates, so the grid can scroll while a card is held near its edge. */
    var viewport: Rect = Rect.Zero
    private var pointer = Offset.Zero
    private val projects = mutableMapOf<Long, Rect>()
    private val stacks = mutableMapOf<String, Rect>()

    fun start(
        projectId: Long,
        local: Offset,
    ) {
        dragging = projectId
        offset = Offset.Zero
        hover = null
        pointer = (projects[projectId]?.topLeft ?: Offset.Zero) + local
    }

    fun move(delta: Offset) {
        offset += delta
        pointer += delta
        hover = dragging?.let(::resolve)
    }

    fun cancel() {
        dragging = null
        offset = Offset.Zero
        hover = null
    }

    /** Ends the drag and reports what is under the finger, if anything other than the artwork itself. */
    fun drop(): Pair<Long, GalleryDrop>? {
        val id = dragging ?: return null
        val target = resolve(id)
        cancel()
        return target?.let { id to it }
    }

    private fun resolve(draggedId: Long): GalleryDrop? {
        stacks.entries.firstOrNull { it.value.contains(pointer) }?.let { return GalleryDrop.OnStack(it.key) }
        val (other, bounds) = projects.entries.firstOrNull { it.key != draggedId && it.value.contains(pointer) } ?: return null
        val across = (pointer.x - bounds.left) / bounds.width
        return when {
            across < EDGE_ZONE -> GalleryDrop.Beside(other, after = false)
            across > 1f - EDGE_ZONE -> GalleryDrop.Beside(other, after = true)
            else -> GalleryDrop.OnProject(other)
        }
    }

    /** How far to scroll this frame (pixels, down is positive): 0 unless the finger is in the top or bottom band of the grid. */
    fun scrollStep(): Float {
        if (dragging == null || viewport == Rect.Zero) return 0f
        val band = (viewport.height * SCROLL_BAND).coerceAtMost(MAX_BAND_PX)
        val into =
            when {
                pointer.y < viewport.top + band -> pointer.y - (viewport.top + band)
                pointer.y > viewport.bottom - band -> pointer.y - (viewport.bottom - band)
                else -> 0f
            }
        return (into / band).coerceIn(-1f, 1f) * MAX_SCROLL_PX
    }

    /** Keeps the held card under the finger after the grid moved by [scrolled] pixels beneath it. */
    fun followScroll(scrolled: Float) {
        offset += Offset(0f, scrolled)
        hover = dragging?.let(::resolve)
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

    private companion object {
        const val EDGE_ZONE = 0.28f
        const val SCROLL_BAND = 0.18f
        const val MAX_BAND_PX = 220f
        const val MAX_SCROLL_PX = 28f
    }
}

@Composable
internal fun rememberGalleryDrag() = remember { GalleryDragState() }

/** Scrolls [grid] while a held card is near its top or bottom edge, so a far-away place can be reached. */
@Composable
internal fun AutoScrollWhileDragging(
    state: GalleryDragState,
    grid: LazyGridState,
) {
    val holding = state.dragging != null
    LaunchedEffect(holding) {
        while (holding) {
            val step = state.scrollStep()
            if (step != 0f) state.followScroll(grid.scrollBy(step))
            withFrameNanos { }
        }
    }
}

/** Lets an artwork card be picked up and dropped on another card or beside it. */
internal fun Modifier.draggableArtwork(
    state: GalleryDragState,
    projectId: Long,
    hint: Color,
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
        }.drawWithContent {
            drawContent()
            drawDropHint(state.hover, projectId, hint)
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

/** Keeps the card being dragged composed while the grid scrolls it out of view, which would otherwise end the drag. */
@Composable
internal fun KeepWhile(held: Boolean) {
    val container = LocalPinnableContainer.current
    DisposableEffect(container, held) {
        val handle = if (held) container?.pin() else null
        onDispose { handle?.release() }
    }
}

private fun DrawScope.drawDropHint(
    hover: GalleryDrop?,
    projectId: Long,
    color: Color,
) {
    when {
        hover is GalleryDrop.OnProject && hover.projectId == projectId -> drawRect(color, style = Stroke(width = HINT_STROKE.dp.toPx()))
        hover is GalleryDrop.Beside && hover.projectId == projectId -> {
            val bar = HINT_BAR.dp.toPx()
            drawRect(color, topLeft = Offset(if (hover.after) size.width - bar else 0f, 0f), size = Size(bar, size.height))
        }
    }
}

/** Marks a stack card as somewhere to drop artworks, outlined while an artwork is held over it. */
internal fun Modifier.stackDropTarget(
    state: GalleryDragState,
    name: String,
    hint: Color,
): Modifier =
    onGloballyPositioned { state.placeStack(name, it.boundsInRoot()) }
        .drawWithContent {
            drawContent()
            if (state.hover == GalleryDrop.OnStack(name)) drawRect(hint, style = Stroke(width = HINT_STROKE.dp.toPx()))
        }

/** A stack name not used yet: "Stack", then "Stack 2", "Stack 3"… */
internal fun newStackName(existing: List<String>): String =
    generateSequence(1) { it + 1 }
        .map { if (it == 1) "Stack" else "Stack $it" }
        .first { it !in existing }

private const val DRAG_SCALE = 1.05f
private const val DRAG_ALPHA = 0.9f
private const val HINT_STROKE = 4
private const val HINT_BAR = 6
