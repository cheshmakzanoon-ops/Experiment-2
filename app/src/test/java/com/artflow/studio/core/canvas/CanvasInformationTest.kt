package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasInformationTest {
    private val mib = 1024L * 1024L

    @Test fun layerBytesIsOneArgbRasterOfTheCanvas() {
        assertEquals(4L * 1000 * 1000, CanvasInformation.layerBytes(1000, 1000))
        assertEquals(0L, CanvasInformation.layerBytes(0, 1000))
        assertEquals(0L, CanvasInformation.layerBytes(1000, -1))
    }

    @Test fun smallCanvasIsLimitedByTheHardCountOnly() {
        // 100 x 100 is 40 KB per layer: a 256 MB heap has room for far more than the hard limit.
        assertEquals(1024, CanvasInformation.layerLimit(100, 100, 256 * mib, hardLimit = 1024))
    }

    @Test fun largeCanvasIsLimitedByTheOpenTimeMemoryBudget() {
        // Budget = 3000 MiB / 3 = 1000 MiB = 1,048,576,000 bytes. A 1000 x 1000 layer is 4,000,000 bytes,
        // so 262 layers fit (262.1 rounded down).
        assertEquals(262, CanvasInformation.layerLimit(1000, 1000, 3000 * mib, hardLimit = 1024))
    }

    @Test fun limitRoundsDownToWholeLayers() {
        // Budget = 100 MiB / 3 = 34,952,533 bytes. A 4,000,000 byte layer fits 8 times (8.7 rounded down).
        assertEquals(8, CanvasInformation.layerLimit(1000, 1000, 100 * mib, hardLimit = 1024))
    }

    @Test fun canvasTooLargeForOneLayerReportsZero() {
        // 8192 x 8192 is 256 MiB per layer, which a 256 MiB heap cannot budget even once.
        assertEquals(0, CanvasInformation.layerLimit(8192, 8192, 256 * mib, hardLimit = 1024))
    }

    @Test fun emptyCanvasReportsTheHardLimit() {
        assertEquals(1024, CanvasInformation.layerLimit(0, 0, 256 * mib, hardLimit = 1024))
    }

    @Test fun unlimitedHeapDoesNotOverflow() {
        assertEquals(1024, CanvasInformation.layerLimit(4000, 4000, Long.MAX_VALUE, hardLimit = 1024))
    }

    @Test fun layersAreCountedAcrossFrames() {
        assertEquals(7, CanvasInformation.layerTotal(listOf(3, 0, 4)))
        assertEquals(0, CanvasInformation.layerTotal(emptyList()))
    }

    @Test fun summaryNamesUsedAndMaximum() {
        assertEquals("3 of 1024 layers", CanvasInformation.layerSummary(3, 1024))
    }
}
