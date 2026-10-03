package com.artflow.studio.data

import com.artflow.studio.data.export.TimelapseRecorder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TimelapseSamplingTest {
    @Test fun thirtySecondExportSpreadsFramesFromFirstToLast() {
        val files = List(10) { File("$it.jpg") }
        val picked = TimelapseRecorder.sampled(files, 4)
        assertEquals(listOf("0.jpg", "3.jpg", "6.jpg", "9.jpg"), picked.map { it.name })
        assertEquals(files, TimelapseRecorder.sampled(files, 20))
    }
}
