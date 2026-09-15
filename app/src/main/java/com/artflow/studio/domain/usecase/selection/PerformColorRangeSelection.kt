package com.artflow.studio.domain.usecase.selection

import android.graphics.Bitmap
import androidx.annotation.IntRange
import com.artflow.studio.core.selection.ColorSelectionAlgorithm
import com.artflow.studio.domain.model.selection.Selection
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Use case for performing global color selection (select all matching pixels)
 * Implements Phase 13: Selection Tools - Color Range selection
 * 
 * This use case selects ALL pixels in the image that match the reference color,
 * regardless of whether they are connected or not. Useful for:
 * - Selecting all instances of a specific color
 * - Background removal
 * - Color-based object isolation
 */
class PerformColorRangeSelection @Inject constructor(
    private val colorSelectionAlgorithm: ColorSelectionAlgorithm
) {

    /**
     * Get the progress flow for monitoring selection operation
     * Returns value from 0 to 100
     */
    val selectionProgress: StateFlow<Int>
        get() = colorSelectionAlgorithm.selectionProgress

    /**
     * Execute color range selection (non-contiguous)
     * 
     * @param bitmap Source bitmap to analyze
     * @param referenceColor The reference color to match (ARGB format)
     * @param tolerance Color tolerance (0-255). Higher values select more colors
     * @param sampleAlpha If true, include alpha channel in comparison
     * @param antiAlias If true, apply anti-aliasing to selection edges
     * @return Selection object containing selected region, or null if no pixels matched
     */
    suspend operator fun invoke(
        bitmap: Bitmap,
        referenceColor: Int,
        seedX: Int,
        seedY: Int,
        @IntRange(from = 0, to = 255) tolerance: Int = 32,
        sampleAlpha: Boolean = true,
        antiAlias: Boolean = true
    ): Selection? {
        // For color range, we use the global selection algorithm
        return colorSelectionAlgorithm.performMagicWandSelection(
            bitmap = bitmap,
            startX = seedX,
            startY = seedY,
            tolerance = tolerance,
            contiguous = false,  // Non-contiguous selection
            sampleAlpha = sampleAlpha,
            antiAlias = antiAlias
        )
    }

    /**
     * Cancel any ongoing selection operation
     */
    fun cancel() {
        colorSelectionAlgorithm.cancelSelection()
    }
}
