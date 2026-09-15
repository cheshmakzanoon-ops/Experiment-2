package com.artflow.studio.domain.usecase.selection

import android.graphics.Bitmap
import androidx.annotation.IntRange
import com.artflow.studio.core.selection.ColorSelectionAlgorithm
import com.artflow.studio.domain.model.selection.Selection
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Use case for performing magic wand selection based on color similarity
 * Implements Phase 13: Selection Tools - Magic Wand functionality
 * 
 * This use case provides:
 * - Contiguous (flood fill) selection
 * - Global color selection (all matching pixels)
 * - Adjustable tolerance
 * - Anti-aliasing support
 * - Progress tracking
 */
class PerformMagicWandSelection @Inject constructor(
    private val colorSelectionAlgorithm: ColorSelectionAlgorithm
) {

    /**
     * Get the progress flow for monitoring selection operation
     * Returns value from 0 to 100
     */
    val selectionProgress: StateFlow<Int>
        get() = colorSelectionAlgorithm.selectionProgress

    /**
     * Execute magic wand selection
     * 
     * @param bitmap Source bitmap to analyze
     * @param startX X coordinate of the seed point
     * @param startY Y coordinate of the seed point
     * @param tolerance Color tolerance (0-255). Higher values select more colors
     * @param contiguous If true, only select connected pixels. If false, select all matching pixels
     * @param sampleAlpha If true, include alpha channel in comparison
     * @param antiAlias If true, apply anti-aliasing to selection edges
     * @return Selection object containing selected region, or null if no pixels matched
     */
    suspend operator fun invoke(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        @IntRange(from = 0, to = 255) tolerance: Int = 32,
        contiguous: Boolean = true,
        sampleAlpha: Boolean = true,
        antiAlias: Boolean = true
    ): Selection? {
        return colorSelectionAlgorithm.performMagicWandSelection(
            bitmap = bitmap,
            startX = startX,
            startY = startY,
            tolerance = tolerance,
            contiguous = contiguous,
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
