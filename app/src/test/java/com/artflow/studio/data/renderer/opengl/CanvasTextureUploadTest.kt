package com.artflow.studio.data.renderer.opengl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasTextureUploadTest {
    @Test
    fun honorsQueriedLimitsBelow2048WithoutUpscalingSmallerArtwork() {
        assertEquals(CanvasTextureSize(512, 128), CanvasTextureUpload.fit(8192, 2048, 512))
        assertEquals(CanvasTextureSize(16, 64), CanvasTextureUpload.fit(2048, 8192, 64))
        assertEquals(CanvasTextureSize(13, 7), CanvasTextureUpload.fit(13, 7, 512))
    }

    @Test
    fun panoramaAndPortraitNeverRoundTheShortEdgeToZeroOrOverflow() {
        assertEquals(CanvasTextureSize(64, 1), CanvasTextureUpload.fit(8192, 1, 64))
        assertEquals(CanvasTextureSize(1, 64), CanvasTextureUpload.fit(1, 8192, 64))
        assertEquals(CanvasTextureSize(2048, 2047), CanvasTextureUpload.fit(Int.MAX_VALUE, Int.MAX_VALUE - 1, 2048))
    }

    @Test
    fun fallsBackToTheLargestSuccessfulAttemptWhenGpuMemoryIsBelowTheDimensionLimit() {
        val attempts = mutableListOf<CanvasTextureSize>()
        val result =
            CanvasTextureUpload.upload(8192, 2048, 4096) { size ->
                attempts += size
                size.width.toLong() * size.height <= 1024 * 256
            }
        assertEquals(listOf(CanvasTextureSize(4096, 1024), CanvasTextureSize(2048, 512), CanvasTextureSize(1024, 256)), attempts)
        assertEquals(CanvasTextureSize(1024, 256), result)
    }

    @Test
    fun stopsAtFirstSuccessAndExhaustedUploadsHaveAnExplicitFailure() {
        var successfulAttempts = 0
        assertEquals(
            CanvasTextureSize(7, 3),
            CanvasTextureUpload.upload(7, 3, 1024) {
                successfulAttempts++
                true
            },
        )
        assertEquals(1, successfulAttempts)

        val rejected = mutableListOf<CanvasTextureSize>()
        assertNull(
            CanvasTextureUpload.upload(7, 3, 1024) {
                rejected += it
                false
            },
        )
        assertEquals(listOf(CanvasTextureSize(7, 3), CanvasTextureSize(3, 1), CanvasTextureSize(1, 1)), rejected)
    }

    @Test
    fun invalidCapabilitiesAndDimensionsNeverAttemptAnUpload() {
        val invalid = listOf(Triple(10, 10, 0), Triple(10, 10, -1), Triple(0, 10, 64), Triple(10, -1, 64))
        invalid.forEach { (width, height, limit) ->
            var attempted = false
            assertNull(
                CanvasTextureUpload.upload(width, height, limit) {
                    attempted = true
                    true
                },
            )
            assertTrue("An invalid size/capability must not reach GL", !attempted)
        }
    }
}
