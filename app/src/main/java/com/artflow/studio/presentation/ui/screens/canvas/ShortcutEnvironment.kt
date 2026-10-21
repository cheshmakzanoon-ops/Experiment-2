package com.artflow.studio.presentation.ui.screens.canvas

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

