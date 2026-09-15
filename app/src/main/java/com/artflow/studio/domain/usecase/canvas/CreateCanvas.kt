package com.artflow.studio.domain.usecase.canvas

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for creating a new canvas with specified dimensions
 */
class CreateCanvas @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Create a new canvas
     * @param width Canvas width in pixels
     * @param height Canvas height in pixels
     * @param dpi Canvas DPI (dots per inch)
     * @return Canvas ID
     */
    suspend operator fun invoke(width: Int, height: Int, dpi: Int = 72): Long {
        return canvasRepository.createCanvas(width, height, dpi)
    }
}
