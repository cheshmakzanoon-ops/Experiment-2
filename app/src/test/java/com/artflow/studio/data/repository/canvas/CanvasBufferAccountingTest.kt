package com.artflow.studio.data.repository.canvas

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasBufferAccountingTest {
    @Test
    fun differentSelectionsRemainCountedWhenSnapshotsShareTheirRaster() {
        val raster = PixelBuffer(64, 64)
        val accounting = CanvasBufferAccounting()

        repeat(30) {
            accounting.includeRaster(raster)
            accounting.includeSelection(SelectionMask(64, 64))
        }

        assertEquals(64L * 64 * (Int.SIZE_BYTES + 30), accounting.bytes)
    }

    @Test
    fun wrappersSharingAnArrayRetainOneAllocationAcrossUndoAndRedo() {
        val pixels = IntArray(12)
        val coverage = ByteArray(12)
        val accounting = CanvasBufferAccounting()

        repeat(4) {
            accounting.includeRaster(PixelBuffer(4, 3, pixels))
            accounting.includeSelection(SelectionMask(4, 3, coverage))
        }

        assertEquals(12L * (Int.SIZE_BYTES + 1), accounting.bytes)
    }

    @Test
    fun equalContentInIndependentArraysStillRetainsBothAllocations() {
        val accounting = CanvasBufferAccounting()

        repeat(2) {
            accounting.includeRaster(PixelBuffer(4, 3))
            accounting.includeSelection(SelectionMask(4, 3))
        }

        assertEquals(2L * 12 * (Int.SIZE_BYTES + 1), accounting.bytes)
    }

    @Test
    fun missingRastersAndSelectionsDoNotAddMemory() {
        val accounting = CanvasBufferAccounting()
        accounting.includeRaster(null)
        accounting.includeSelection(null)
        assertEquals(0L, accounting.bytes)
    }
}
