package com.artflow.studio.presentation.ui.components.editor

/** Workflow routes, distinct from tools that operate directly on the canvas. */
enum class StudioAction(
    val label: String,
) {
    BRUSH_STUDIO("Brush Studio"),
    TOOL_OPTIONS("Tool options"),
    SELECTION("Selection options"),
    TRANSFORM("Layer transform"),
    GUIDES("Drawing guides"),
    ANIMATION("Animation"),
    CANVAS("Canvas setup"),
    TEXT("Text settings"),
    EXPORT("Export artwork"),
}
