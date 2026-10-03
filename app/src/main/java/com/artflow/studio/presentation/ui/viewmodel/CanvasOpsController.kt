package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Canvas-wide changes from Crop & Resize and the canvas menus: size, crop, rotation, flips, resolution and background. */
class CanvasOpsController(
    private val repository: CanvasRepository,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val onSize: (Int, Int, Int) -> Unit,
) {
    fun resizeCanvas(
        width: Int,
        height: Int,
        resample: Boolean,
        anchor: CanvasOperations.Anchor,
    ) {
        scope.launch {
            if (repository.resizeCanvas(width, height, resample, anchor)) {
                onSize(width, height, canvasSize().third)
                notify("Canvas resized to ${width}x$height")
            } else {
                notify("That canvas size is not supported")
            }
        }
    }

    fun cropCanvas(bounds: IntBounds) {
        scope.launch {
            if (repository.cropCanvas(bounds)) {
                val (width, height, dpi) = canvasSize()
                onSize(width, height, dpi)
            } else {
                notify("Crop not applied. Select an area inside the canvas and finish active edits.")
            }
        }
    }

    fun rotateCanvas(degrees: Int) {
        scope.launch {
            if (repository.rotateCanvas(degrees)) {
                val (width, height, dpi) = canvasSize()
                onSize(width, height, dpi)
                notify("Canvas rotated $degrees°")
            } else {
                notify("Rotation not applied. Choose a quarter turn and finish active edits.")
            }
        }
    }

    fun flipCanvas(vertical: Boolean) {
        scope.launch {
            if (repository.flipCanvas(vertical)) {
                notify(if (vertical) "Canvas flipped vertically" else "Canvas flipped horizontally")
            } else {
                notify("Flip not applied. Finish active edits first.")
            }
        }
    }

    fun trimTransparent() {
        scope.launch {
            if (repository.trimTransparent()) {
                val (width, height, dpi) = canvasSize()
                onSize(width, height, dpi)
                notify("Trimmed to content")
            } else {
                notify("Nothing to trim")
            }
        }
    }

    fun trimToSelection() {
        val mask =
            repository.selection() ?: run {
                notify("Make a selection first")
                return
            }
        val bounds =
            mask.bounds() ?: run {
                notify("The selection is empty")
                return
            }
        cropCanvas(bounds)
    }

    fun setCanvasDpi(dpi: Int) {
        scope.launch {
            repository.setCanvasDpi(dpi)
            val size = canvasSize()
            onSize(size.first, size.second, size.third)
        }
    }

    fun setCanvasBackgroundColor(color: Int) {
        scope.launch { repository.setCanvasBackgroundColor(color) }
    }

    /** Assigns the colour profile the canvas's colours are read in; undoable. */
    fun setColorProfile(profile: ColorProfile) {
        scope.launch { if (repository.setColorProfile(profile)) notify("Colour profile: ${profile.label}") }
    }

    private fun canvasSize(): Triple<Int, Int, Int> {
        val size = repository.getCanvasSize()
        return Triple(size.width, size.height, size.dpi)
    }
}
