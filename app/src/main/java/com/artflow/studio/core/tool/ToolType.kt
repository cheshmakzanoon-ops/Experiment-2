package com.artflow.studio.core.tool

/**
 * Every tool the editor can activate (Phases 7-24).
 *
 * The enum is the single source of truth for the tool bar, the quick menu, gesture bindings and
 * the "which panels should be visible" logic, so adding a tool does not require touching the UI
 * in several places.
 */
enum class ToolType(
    val displayName: String,
    val group: ToolGroup,
    /** Tools that write pixels directly rather than producing strokes. */
    val isPixelTool: Boolean = false,
    /** Tools that require the user to drag on the canvas before anything happens. */
    val requiresDrag: Boolean = true
) {
    BRUSH("Brush", ToolGroup.PAINT),
    ERASER("Eraser", ToolGroup.PAINT),
    SMUDGE("Smudge", ToolGroup.PAINT, isPixelTool = true),
    CLONE_STAMP("Clone Stamp", ToolGroup.PAINT, isPixelTool = true),
    HEALING("Healing", ToolGroup.PAINT, isPixelTool = true),
    LIQUIFY("Liquify", ToolGroup.PAINT, isPixelTool = true),
    PAINT_BUCKET("Paint Bucket", ToolGroup.FILL, isPixelTool = true),
    GRADIENT("Gradient", ToolGroup.FILL, isPixelTool = true),
    TEXT("Text", ToolGroup.VECTOR, requiresDrag = false),
    SHAPE("Shape", ToolGroup.VECTOR),
    SELECT_RECTANGLE("Rectangle Select", ToolGroup.SELECTION),
    SELECT_ELLIPSE("Ellipse Select", ToolGroup.SELECTION),
    SELECT_FREEHAND("Freehand Select", ToolGroup.SELECTION),
    SELECT_LASSO("Lasso Select", ToolGroup.SELECTION),
    SELECT_MAGIC_WAND("Magic Wand", ToolGroup.SELECTION, requiresDrag = false),
    TRANSFORM("Transform", ToolGroup.TRANSFORM),
    EYEDROPPER("Eyedropper", ToolGroup.UTILITY, requiresDrag = false),
    MOVE("Move", ToolGroup.UTILITY),
    ZOOM("Zoom", ToolGroup.UTILITY);

    /** Tools that are destructive to pixels and therefore always snapshot before they run. */
    fun isDestructive(): Boolean = isPixelTool || this == ERASER

    /** Whether the brush size / opacity controls are relevant. */
    fun usesBrushSize(): Boolean = when (this) {
        SMUDGE, CLONE_STAMP, HEALING, LIQUIFY, ERASER, BRUSH, SELECT_FREEHAND, GRADIENT -> true
        else -> false
    }

    companion object {
        /** The tools shown in the primary tool bar, in display order. */
        fun primaryTools(): List<ToolType> = listOf(
            BRUSH, ERASER, SMUDGE, CLONE_STAMP, HEALING, LIQUIFY,
            PAINT_BUCKET, GRADIENT, TEXT, SHAPE,
            SELECT_RECTANGLE, SELECT_FREEHAND, SELECT_MAGIC_WAND, TRANSFORM, EYEDROPPER
        )

        /** Tools that build a selection. */
        fun selectionTools(): List<ToolType> = listOf(
            SELECT_RECTANGLE, SELECT_ELLIPSE, SELECT_FREEHAND, SELECT_LASSO, SELECT_MAGIC_WAND
        )
    }
}

/** Grouping used by the tool bar and quick menu. */
enum class ToolGroup(val displayName: String) {
    PAINT("Paint"),
    FILL("Fill"),
    VECTOR("Vector"),
    SELECTION("Selection"),
    TRANSFORM("Transform"),
    UTILITY("Utility")
}
