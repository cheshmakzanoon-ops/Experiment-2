package com.artflow.studio.presentation.ui.screens.canvas

import com.artflow.studio.core.canvas.ShortcutAction
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.components.canvas.EditorInput
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel

/** What a keyboard shortcut needs from the open editor; panel state stays with the screen that owns it. */
internal class ShortcutEnvironment(
    val viewModel: CanvasViewModel,
    val input: EditorInput,
    val canvasView: ArtFlowCanvasView?,
    val activeLayerId: Long,
    val toggleInterface: () -> Unit,
    val openExport: () -> Unit,
)

/** One brush-size key press: about ten percent, and never less than a pixel, so small brushes still move. */
internal fun steppedSize(
    size: Float,
    up: Boolean,
): Float = if (up) maxOf(size * SIZE_STEP, size + 1f) else minOf(size / SIZE_STEP, size - 1f)

/** Runs [action]; the caller has already checked that an editor is open and cancelled any gesture. */
internal fun runShortcut(
    action: ShortcutAction,
    env: ShortcutEnvironment,
) {
    val viewModel = env.viewModel
    val view = env.canvasView
    when (action) {
        ShortcutAction.UNDO -> viewModel.undo()
        ShortcutAction.REDO -> viewModel.redo()
        ShortcutAction.CUT -> viewModel.clipboard.copy(cut = true)
        ShortcutAction.COPY -> viewModel.clipboard.copy()
        ShortcutAction.COPY_MERGED -> viewModel.clipboard.copyMerged()
        ShortcutAction.PASTE -> viewModel.clipboard.paste()
        ShortcutAction.CLEAR -> viewModel.clipboard.clear()
        ShortcutAction.SELECT_ALL -> viewModel.selectAll()
        ShortcutAction.DESELECT -> viewModel.clearSelection()
        ShortcutAction.INVERT_SELECTION -> viewModel.invertSelection()
        ShortcutAction.TOOL_BRUSH -> viewModel.setTool(ToolType.BRUSH)
        ShortcutAction.TOOL_SMUDGE -> viewModel.setTool(ToolType.SMUDGE)
        ShortcutAction.TOOL_ERASER -> viewModel.setTool(ToolType.ERASER)
        ShortcutAction.TOOL_EYEDROPPER -> viewModel.startEyedropper()
        ShortcutAction.TOOL_SELECT -> viewModel.setTool(ToolType.SELECT_FREEHAND)
        ShortcutAction.TOOL_TRANSFORM ->
            viewModel.setTool(if (env.input.tool == ToolType.TRANSFORM) ToolType.BRUSH else ToolType.TRANSFORM)
        ShortcutAction.SWAP_COLORS -> viewModel.swapColors()
        ShortcutAction.SIZE_UP, ShortcutAction.SIZE_DOWN -> {
            val up = action == ShortcutAction.SIZE_UP
            if (env.input.tool == ToolType.ERASER) {
                viewModel.setEraserSize(steppedSize(env.input.eraserSize, up))
            } else {
                viewModel.setBrushSize(steppedSize(env.input.brushParams.size, up))
            }
        }
        ShortcutAction.OPACITY_UP -> viewModel.setBrushOpacity(env.input.brushParams.opacity + OPACITY_STEP)
        ShortcutAction.OPACITY_DOWN -> viewModel.setBrushOpacity(env.input.brushParams.opacity - OPACITY_STEP)
        ShortcutAction.ZOOM_IN -> view?.zoomBy(ZOOM_STEP)
        ShortcutAction.ZOOM_OUT -> view?.zoomBy(1f / ZOOM_STEP)
        ShortcutAction.FIT_TO_SCREEN -> view?.fitToView()
        ShortcutAction.ACTUAL_SIZE -> view?.resetView()
        ShortcutAction.ROTATE_VIEW_RIGHT -> view?.rotateView(ROTATE_STEP)
        ShortcutAction.ROTATE_VIEW_LEFT -> view?.rotateView(-ROTATE_STEP)
        ShortcutAction.PAN_LEFT -> view?.panBy(PAN_STEP, 0f)
        ShortcutAction.PAN_RIGHT -> view?.panBy(-PAN_STEP, 0f)
        ShortcutAction.PAN_UP -> view?.panBy(0f, PAN_STEP)
        ShortcutAction.PAN_DOWN -> view?.panBy(0f, -PAN_STEP)
        ShortcutAction.TOGGLE_INTERFACE -> env.toggleInterface()
        ShortcutAction.NEW_LAYER -> viewModel.addLayer()
        ShortcutAction.DUPLICATE_LAYER -> viewModel.duplicateActiveLayer()
        ShortcutAction.MERGE_DOWN -> viewModel.mergeLayerDown(env.activeLayerId)
        ShortcutAction.SAVE -> viewModel.save()
        ShortcutAction.EXPORT -> env.openExport()
        // The screen handles this one itself, because it depends on what is open.
        ShortcutAction.CLOSE_PANEL -> Unit
    }
}

private const val SIZE_STEP = 1.1f
private const val OPACITY_STEP = 0.05f
private const val ZOOM_STEP = 1.25f
private const val ROTATE_STEP = 15f
private const val PAN_STEP = 80f
