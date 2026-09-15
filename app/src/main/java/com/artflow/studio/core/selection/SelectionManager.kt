package com.artflow.studio.core.selection

import android.graphics.Path
import android.graphics.RectF
import com.artflow.studio.domain.model.selection.FloodFillResult
import com.artflow.studio.domain.model.selection.MagicWandConfig
import com.artflow.studio.domain.model.selection.MarchingAntsState
import com.artflow.studio.domain.model.selection.Selection
import com.artflow.studio.domain.model.selection.SelectionOperation
import com.artflow.studio.domain.model.selection.SelectionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selection Manager for Phase 13: Selection Tools
 * Manages all selection operations including creation, modification, and manipulation
 * 
 * Features:
 * - Multiple selection tools (freehand, lasso, rectangle, ellipse, magic wand)
 * - Marching ants animation
 * - Selection operations (fill, stroke, transform, invert, feather)
 * - Undo/redo support
 */
@Singleton
class SelectionManager @Inject constructor() {

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    private val _marchingAntsState = MutableStateFlow(MarchingAntsState())
    val marchingAntsState: StateFlow<MarchingAntsState> = _marchingAntsState.asStateFlow()

    private var nextSelectionId = 1L
    private val selectionHistory = mutableListOf<SelectionHistoryEntry>()
    private var historyIndex = -1

    /**
     * Create a rectangular selection
     */
    fun createRectangleSelection(left: Float, top: Float, right: Float, bottom: Float): Selection {
        val rectLeft = minOf(left, right)
        val rectTop = minOf(top, bottom)
        val rectRight = maxOf(left, right)
        val rectBottom = maxOf(top, bottom)

        val path = Path().apply {
            addRect(rectLeft, rectTop, rectRight, rectBottom, Path.Direction.CW)
        }

        val bounds = RectF(rectLeft, rectTop, rectRight, rectBottom)

        return createSelection(SelectionType.RECTANGLE, path, bounds)
    }

    /**
     * Create an elliptical selection
     */
    fun createEllipseSelection(centerX: Float, centerY: Float, radiusX: Float, radiusY: Float): Selection {
        val bounds = RectF(
            centerX - radiusX,
            centerY - radiusY,
            centerX + radiusX,
            centerY + radiusY
        )

        val path = Path().apply {
            addOval(bounds, Path.Direction.CW)
        }

        return createSelection(SelectionType.ELLIPSE, path, bounds)
    }

    /**
     * Create a freehand/lasso selection from a path
     */
    fun createFreehandSelection(points: List<Pair<Float, Float>>): Selection? {
        if (points.size < 3) return null

        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) {
                lineTo(points[i].first, points[i].second)
            }
            close()
        }

        val bounds = RectF()
        path.computeBounds(bounds, true)

        return createSelection(SelectionType.FREEHAND, path, bounds)
    }

    /**
     * Create a polygonal lasso selection from discrete points
     */
    fun createLassoSelection(points: List<Pair<Float, Float>>): Selection? {
        if (points.size < 3) return null

        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) {
                lineTo(points[i].first, points[i].second)
            }
            close()
        }

        val bounds = RectF()
        path.computeBounds(bounds, true)

        return createSelection(SelectionType.LASSO, path, bounds)
    }

    /**
     * Create a magic wand selection based on color similarity
     */
    fun createMagicWandSelection(
        pixels: Array<IntArray>,
        x: Int,
        y: Int,
        config: MagicWandConfig
    ): Selection? {
        if (pixels.isEmpty() || y !in pixels.indices || x !in pixels[y].indices) {
            return null
        }

        val targetColor = pixels[y][x]
        val result = FloodFillResult.floodFill(
            pixels = pixels,
            startX = x,
            startY = y,
            targetColor = targetColor,
            tolerance = config.tolerance,
            contiguous = config.contiguous
        )

        if (result.pointCount == 0) return null

        // Create a path from the selected points
        val path = Path().apply {
            // Start from the first point
            if (result.selectedPoints.isNotEmpty()) {
                val (startX, startY) = result.selectedPoints.first()
                moveTo(startX.toFloat(), startY.toFloat())

                // Simple approach: create a path that covers all points
                // In production, you'd want to trace the edge of the selection
                result.selectedPoints.forEach { (px, py) ->
                    lineTo(px.toFloat(), py.toFloat())
                }
                close()
            }
        }

        return createSelection(SelectionType.MAGIC_WAND, path, result.bounds)
    }

    /**
     * Helper to create and register a new selection
     */
    private fun createSelection(type: SelectionType, path: Path, bounds: RectF): Selection {
        saveHistoryState(SelectionOperation.CREATE, oldSelection = _selection.value)

        val selection = Selection(
            id = nextSelectionId++,
            type = type,
            path = path,
            bounds = bounds,
            isActive = true
        )

        _selection.value = selection
        _marchingAntsState.value = MarchingAntsState(isAnimating = true)

        Timber.d("Selection created: type=$type, bounds=$bounds")
        return selection
    }

    /**
     * Clear the current selection
     */
    fun clearSelection() {
        if (_selection.value == null) return

        saveHistoryState(SelectionOperation.DELETE, oldSelection = _selection.value)

        _selection.value = null
        _marchingAntsState.value = MarchingAntsState(isAnimating = false)

        Timber.d("Selection cleared")
    }

    /**
     * Invert the current selection (select everything except current selection)
     * Note: This is a simplified implementation - full inversion requires canvas bounds
     */
    fun invertSelection(canvasWidth: Float, canvasHeight: Float) {
        val currentSelection = _selection.value ?: return

        saveHistoryState(SelectionOperation.INVERT, oldSelection = currentSelection)

        // Subtract the current selection from the full canvas rectangle.
        // Path.op(DIFFERENCE) performs a real boolean subtraction, unlike adding the
        // selection path again (which is what the old code attempted and could not do).
        val inversePath = Path().apply {
            addRect(0f, 0f, canvasWidth, canvasHeight, Path.Direction.CW)
            op(currentSelection.path, Path.Op.DIFFERENCE)
        }

        val inverseBounds = RectF(0f, 0f, canvasWidth, canvasHeight)

        _selection.value = currentSelection.copy(
            path = inversePath,
            bounds = inverseBounds,
            type = SelectionType.FREEHAND // Changed type since it's now complex
        )

        Timber.d("Selection inverted")
    }

    /**
     * Feather the selection edges by creating a blurred boundary
     * Returns the feather amount in pixels
     */
    fun featherSelection(featherRadius: Float): Boolean {
        val currentSelection = _selection.value ?: return false

        if (featherRadius <= 0) return false

        saveHistoryState(SelectionOperation.FEATHER, oldSelection = currentSelection)

        // Note: Actual feathering requires modifying the path with rounded corners
        // and implementing soft-edge rendering in the compositor
        // This is a placeholder for the full implementation

        Timber.d("Selection feathered by $featherRadius px")
        return true
    }

    /**
     * Contract the selection by a specified number of pixels
     */
    fun contractSelection(pixels: Float): Boolean {
        val currentSelection = _selection.value ?: return false

        if (pixels <= 0) return false

        saveHistoryState(SelectionOperation.CONTRACT, oldSelection = currentSelection)

        // Use Path.op to shrink the selection
        val contractedPath = Path().apply {
            // Offset the path inward
            op(currentSelection.path, getPathOffset(currentSelection.bounds, -pixels), Path.Op.INTERSECT)
        }

        if (contractedPath.isEmpty) {
            clearSelection()
            return false
        }

        val newBounds = RectF()
        contractedPath.computeBounds(newBounds, true)

        _selection.value = currentSelection.copy(
            path = contractedPath,
            bounds = newBounds
        )

        Timber.d("Selection contracted by $pixels px")
        return true
    }

    /**
     * Expand the selection by a specified number of pixels
     */
    fun expandSelection(pixels: Float): Boolean {
        val currentSelection = _selection.value ?: return false

        if (pixels <= 0) return false

        saveHistoryState(SelectionOperation.EXPAND, oldSelection = currentSelection)

        // Use Path.op to grow the selection
        val expandedPath = Path().apply {
            // Offset the path outward
            op(currentSelection.path, getPathOffset(currentSelection.bounds, pixels), Path.Op.UNION)
        }

        val newBounds = RectF()
        expandedPath.computeBounds(newBounds, true)

        _selection.value = currentSelection.copy(
            path = expandedPath,
            bounds = newBounds
        )

        Timber.d("Selection expanded by $pixels px")
        return true
    }

    /**
     * Helper to create an offset path for contraction/expansion
     */
    private fun getPathOffset(bounds: RectF, offset: Float): Path {
        return Path().apply {
            addRect(
                bounds.left - offset,
                bounds.top - offset,
                bounds.right + offset,
                bounds.bottom + offset,
                Path.Direction.CW
            )
        }
    }

    /**
     * Update the marching ants animation
     */
    fun updateAnimation(deltaTimeMs: Long) {
        if (_selection.value == null) return
        _marchingAntsState.value = _marchingAntsState.value.update(deltaTimeMs)
    }

    /**
     * Check if a point is within the current selection
     */
    fun isPointInSelection(x: Float, y: Float): Boolean {
        return _selection.value?.containsPoint(x, y) ?: false
    }

    /**
     * Get the current selection area in square pixels
     */
    fun getSelectionArea(): Float {
        return _selection.value?.getArea() ?: 0f
    }

    /**
     * Check if there's an active selection
     */
    fun hasActiveSelection(): Boolean {
        return _selection.value?.isActive == true
    }

    /**
     * Save state to history for undo/redo
     */
    private fun saveHistoryState(
        operation: SelectionOperation,
        oldSelection: Selection? = null,
        newSelection: Selection? = null
    ) {
        // Remove any future states if we're not at the end
        while (historyIndex < selectionHistory.lastIndex) {
            selectionHistory.removeAt(selectionHistory.lastIndex)
        }

        val entry = SelectionHistoryEntry(
            operation = operation,
            oldSelection = oldSelection?.let { copySelection(it) },
            newSelection = newSelection?.let { copySelection(it) },
            timestamp = System.currentTimeMillis()
        )

        selectionHistory.add(entry)
        historyIndex++

        // Limit history size
        if (selectionHistory.size > 50) {
            selectionHistory.removeAt(0)
            historyIndex--
        }
    }

    /**
     * Create a deep copy of a selection for history
     */
    private fun copySelection(selection: Selection): Selection {
        return selection.copy(path = Path(selection.path))
    }

    /**
     * Undo the last selection operation
     */
    fun undo(): Boolean {
        if (historyIndex < 0) return false

        val entry = selectionHistory[historyIndex]
        historyIndex--

        when (entry.operation) {
            SelectionOperation.CREATE -> {
                _selection.value = entry.oldSelection
            }
            SelectionOperation.DELETE -> {
                _selection.value = entry.oldSelection
                _marchingAntsState.value = MarchingAntsState(isAnimating = true)
            }
            SelectionOperation.MODIFY,
            SelectionOperation.INVERT,
            SelectionOperation.FEATHER,
            SelectionOperation.CONTRACT,
            SelectionOperation.EXPAND -> {
                _selection.value = entry.oldSelection
            }
            else -> {
                // For fill/stroke/transform, we can't easily undo without canvas state
                Timber.w("Undo not fully supported for ${entry.operation}")
            }
        }

        Timber.d("Selection undone: ${entry.operation}")
        return true
    }

    /**
     * Redo a previously undone selection operation
     */
    fun redo(): Boolean {
        if (historyIndex >= selectionHistory.lastIndex) return false

        historyIndex++
        val entry = selectionHistory[historyIndex]

        when (entry.operation) {
            SelectionOperation.CREATE,
            SelectionOperation.MODIFY,
            SelectionOperation.INVERT,
            SelectionOperation.FEATHER,
            SelectionOperation.CONTRACT,
            SelectionOperation.EXPAND -> {
                _selection.value = entry.newSelection
            }
            SelectionOperation.DELETE -> {
                _selection.value = null
                _marchingAntsState.value = MarchingAntsState(isAnimating = false)
            }
            else -> {
                Timber.w("Redo not fully supported for ${entry.operation}")
            }
        }

        Timber.d("Selection redone: ${entry.operation}")
        return true
    }

    /**
     * Clear selection history
     */
    fun clearHistory() {
        selectionHistory.clear()
        historyIndex = -1
    }
}

/**
 * History entry for selection operations
 */
private data class SelectionHistoryEntry(
    val operation: SelectionOperation,
    val oldSelection: Selection?,
    val newSelection: Selection?,
    val timestamp: Long
)
