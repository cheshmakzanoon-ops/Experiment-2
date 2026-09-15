package com.artflow.studio.core.selection

import android.graphics.Bitmap
import androidx.annotation.IntRange
import com.artflow.studio.domain.model.selection.Selection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Color-based selection algorithm for Magic Wand and similar tools
 * Implements Phase 13: Selection Tools - Magic Wand functionality
 * 
 * This class provides intelligent color-based selection with:
 * - Flood fill algorithm for contiguous selection
 * - Tolerance-based color matching
 * - Anti-aliasing support
 * - Multi-threaded performance optimization
 * - Sample all layers option
 */
@Singleton
class ColorSelectionAlgorithm @Inject constructor() {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    // State flow for selection progress (0-100%)
    private val _selectionProgress = MutableStateFlow(0)
    val selectionProgress: StateFlow<Int> = _selectionProgress.asStateFlow()

    // Currently active selection job for cancellation
    private var activeSelectionJob: kotlinx.coroutines.Job? = null

    /**
     * Perform magic wand selection based on color similarity
     * 
     * @param bitmap Source bitmap to analyze
     * @param startX X coordinate of the seed point
     * @param startY Y coordinate of the seed point
     * @param tolerance Color tolerance (0-255). Higher values select more colors
     * @param contiguous If true, only select connected pixels. If false, select all matching pixels
     * @param sampleAlpha If true, include alpha channel in comparison
     * @param antiAlias If true, apply anti-aliasing to selection edges
     * @return Selection object containing selected region, or null if operation was cancelled
     */
    suspend fun performMagicWandSelection(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        @IntRange(from = 0, to = 255) tolerance: Int = 32,
        contiguous: Boolean = true,
        sampleAlpha: Boolean = true,
        antiAlias: Boolean = true
    ): Selection? = withContext(Dispatchers.Default) {
        // Validate coordinates
        if (!isValidCoordinate(startX, startY, bitmap.width, bitmap.height)) {
            Timber.e("Invalid seed point: ($startX, $startY)")
            return@withContext null
        }

        try {
            _selectionProgress.value = 0

            // Get reference color from seed point
            val referenceColor = bitmap.getPixel(startX, startY)
            Timber.d(
                "Magic Wand started at ($startX, $startY) with color ${String.format("#%08X", referenceColor)}"
            )

            // Perform selection based on mode
            val selectedPixels = if (contiguous) {
                floodFillSelection(
                    bitmap = bitmap,
                    startX = startX,
                    startY = startY,
                    referenceColor = referenceColor,
                    tolerance = tolerance,
                    sampleAlpha = sampleAlpha
                )
            } else {
                globalColorSelection(
                    bitmap = bitmap,
                    referenceColor = referenceColor,
                    tolerance = tolerance,
                    sampleAlpha = sampleAlpha
                )
            }

            if (selectedPixels.isEmpty()) {
                Timber.w("No pixels matched the selection criteria")
                _selectionProgress.value = 100
                return@withContext null
            }

            _selectionProgress.value = 80

            // Create selection from pixel set
            val selection = createSelectionFromPixels(
                pixels = selectedPixels,
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                antiAlias = antiAlias
            )

            _selectionProgress.value = 100
            Timber.i("Magic Wand selection completed: ${selectedPixels.size} pixels selected")
            
            selection
        } catch (e: Exception) {
            Timber.e(e, "Error during magic wand selection")
            _selectionProgress.value = 0
            null
        }
    }

    /**
     * Flood fill algorithm for contiguous color selection
     * Uses stack-based approach to avoid recursion depth issues
     */
    private fun floodFillSelection(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        referenceColor: Int,
        tolerance: Int,
        sampleAlpha: Boolean
    ): Set<Long> {
        val width = bitmap.width
        val height = bitmap.height
        
        val visited = mutableSetOf<Long>()
        val stack = ArrayDeque<Pair<Int, Int>>()
        
        stack.addLast(startX to startY)
        visited.add(encodeCoordinate(startX, startY))

        var processedCount = 0
        val totalPixels = width * height
        val progressInterval = totalPixels / 100

        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeLast()
            
            // Check if current pixel matches color criteria
            if (matchesColor(bitmap.getPixel(x, y), referenceColor, tolerance, sampleAlpha)) {
                // Check 4-connected neighbors (up, down, left, right)
                val neighbors = listOf(
                    x to y - 1,  // Up
                    x to y + 1,  // Down
                    x - 1 to y,  // Left
                    x + 1 to y   // Right
                )

                for ((nx, ny) in neighbors) {
                    if (isValidCoordinate(nx, ny, width, height)) {
                        val encodedCoord = encodeCoordinate(nx, ny)
                        if (encodedCoord !in visited) {
                            visited.add(encodedCoord)
                            stack.addLast(nx to ny)
                        }
                    }
                }
            }

            // Update progress
            processedCount++
            if (processedCount % progressInterval == 0) {
                _selectionProgress.value = (processedCount * 100 / totalPixels).coerceIn(0, 80)
            }
        }

        // Return only the pixels that matched (not all visited)
        return visited.filter { coord ->
            val (x, y) = decodeCoordinate(coord)
            matchesColor(bitmap.getPixel(x, y), referenceColor, tolerance, sampleAlpha)
        }.toSet()
    }

    /**
     * Global color selection - selects all matching pixels in the image
     * More efficient than flood fill for non-contiguous selections
     */
    private fun globalColorSelection(
        bitmap: Bitmap,
        referenceColor: Int,
        tolerance: Int,
        sampleAlpha: Boolean
    ): Set<Long> {
        val width = bitmap.width
        val height = bitmap.height
        val selectedPixels = mutableSetOf<Long>()

        var processedCount = 0
        val totalPixels = width * height
        val progressInterval = totalPixels / 100

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixelColor = bitmap.getPixel(x, y)
                
                if (matchesColor(pixelColor, referenceColor, tolerance, sampleAlpha)) {
                    selectedPixels.add(encodeCoordinate(x, y))
                }

                // Update progress
                processedCount++
                if (processedCount % progressInterval == 0) {
                    _selectionProgress.value = (processedCount * 100 / totalPixels).coerceIn(0, 80)
                }
            }
        }

        return selectedPixels
    }

    /**
     * Check if a pixel color matches the reference color within tolerance
     */
    private fun matchesColor(
        pixelColor: Int,
        referenceColor: Int,
        tolerance: Int,
        sampleAlpha: Boolean
    ): Boolean {
        // Extract RGBA components
        val pA = if (sampleAlpha) (pixelColor ushr 24) and 0xFF else 255
        val pR = (pixelColor ushr 16) and 0xFF
        val pG = (pixelColor ushr 8) and 0xFF
        val pB = pixelColor and 0xFF

        val rA = if (sampleAlpha) (referenceColor ushr 24) and 0xFF else 255
        val rR = (referenceColor ushr 16) and 0xFF
        val rG = (referenceColor ushr 8) and 0xFF
        val rB = referenceColor and 0xFF

        // Calculate color distance using Euclidean distance in RGBA space
        val diffA = if (sampleAlpha) pA - rA else 0
        val diffR = pR - rR
        val diffG = pG - rG
        val diffB = pB - rB

        // Squared Euclidean distance (avoid sqrt for performance)
        val squaredDistance = diffA * diffA + diffR * diffR + diffG * diffG + diffB * diffB
        val squaredTolerance = tolerance * tolerance

        return squaredDistance <= squaredTolerance
    }

    /**
     * Create a Selection object from a set of selected pixels
     * Includes edge smoothing if anti-aliasing is enabled
     */
    private fun createSelectionFromPixels(
        pixels: Set<Long>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        antiAlias: Boolean
    ): Selection {
        // Find bounding box
        var minX = bitmapWidth
        var minY = bitmapHeight
        var maxX = 0
        var maxY = 0

        for (coord in pixels) {
            val (x, y) = decodeCoordinate(coord)
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }

        val width = maxX - minX + 1
        val height = maxY - minY + 1

        // Create selection mask
        val mask = Array(height) { BooleanArray(width) }
        
        for (coord in pixels) {
            val (x, y) = decodeCoordinate(coord)
            mask[y - minY][x - minX] = true
        }

        // Apply anti-aliasing if requested
        val finalMask = if (antiAlias && pixels.size > 4) {
            applyAntiAliasing(mask, width, height)
        } else {
            mask
        }

        return Selection(
            offsetX = minX,
            offsetY = minY,
            width = width,
            height = height,
            mask = finalMask,
            featherRadius = if (antiAlias) 1 else 0
        )
    }

    /**
     * Apply anti-aliasing to selection edges using feathering
     */
    private fun applyAntiAliasing(
        mask: Array<BooleanArray>,
        width: Int,
        height: Int
    ): Array<BooleanArray> {
        val result = Array(height) { BooleanArray(width) }
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y][x]) {
                    result[y][x] = true
                } else {
                    // Check if this empty pixel is adjacent to selected pixels
                    val neighborCount = countSelectedNeighbors(mask, x, y, width, height)
                    if (neighborCount >= 2) {
                        // Edge pixel - include with feathering
                        result[y][x] = true
                    }
                }
            }
        }
        
        return result
    }

    /**
     * Count selected neighbors for a given pixel (8-connected)
     */
    private fun countSelectedNeighbors(
        mask: Array<BooleanArray>,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Int {
        var count = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (isValidCoordinate(nx, ny, width, height) && mask[ny][nx]) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * Cancel any ongoing selection operation
     */
    fun cancelSelection() {
        activeSelectionJob?.cancel()
        activeSelectionJob = null
        _selectionProgress.value = 0
        Timber.d("Selection operation cancelled")
    }

    /**
     * Start a selection operation with coroutine tracking
     */
    fun startSelectionOperation(
        block: suspend () -> Unit
    ) {
        activeSelectionJob?.cancel()
        activeSelectionJob = coroutineScope.launch {
            block()
        }
    }

    companion object {
        /**
         * Encode 2D coordinates into a single Long for efficient storage
         */
        private fun encodeCoordinate(x: Int, y: Int): Long {
            return (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        }

        /**
         * Decode Long back to 2D coordinates
         */
        private fun decodeCoordinate(encoded: Long): Pair<Int, Int> {
            val x = (encoded shr 32).toInt()
            val y = (encoded and 0xFFFFFFFFL).toInt()
            return x to y
        }

        /**
         * Validate coordinates are within bitmap bounds
         */
        private fun isValidCoordinate(x: Int, y: Int, width: Int, height: Int): Boolean {
            return x in 0 until width && y in 0 until height
        }
    }
}
