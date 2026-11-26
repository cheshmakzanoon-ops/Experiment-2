package com.artflow.studio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.local.ProjectStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Exercises profile conversion, alpha matting and odd-size padding through the real export. */
@RunWith(AndroidJUnit4::class)
class Mp4ColorProfileDeviceTest {
    private lateinit var storage: ProjectStorage
    private lateinit var exporter: ArtworkExporter
    private var projectId = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        storage = ProjectStorage(context)
        exporter = ArtworkExporter(context, storage)
        projectId = System.nanoTime().coerceAtLeast(1)
    }

    @After
    fun tearDown() {
        storage.deleteProjectFiles(projectId)
    }

    @Test
    fun displayP3MatteMatchesPreparedPixelsAcrossAlphaAndPadding() =
        runBlocking {
            // These P3 channels clip to sRGB green, making the unconverted matte clearly different.
            val background = 0xFF50FF40.toInt()
            val frame =
                PixelBuffer(
                    65,
                    33,
                    IntArray(65 * 33) { index ->
                        val alpha =
                            when (index % 65) {
                                in 0..21 -> 0
                                in 22..43 -> 128
                                else -> 255
                            }
                        (background and 0x00FFFFFF) or (alpha shl 24)
                    },
                )
            val result =
                exporter
                    .exportAnimation(
                        projectId,
                        "P3 matte",
                        listOf(frame, frame),
                        listOf(120, 240),
                        ExportOptions(
                            format = ExportFormat.MP4,
                            colorProfile = ColorProfile.DISPLAY_P3,
                            backgroundColor = background,
                        ),
                    ).getOrThrow()
            File(result.filePath).copyTo(File(TestEvidence.directory(), "mp4-display-p3-matte.mp4"), overwrite = true)
            assertEquals(66, result.width)
            assertEquals(34, result.height)

            // Android's colour connector is independent of the export's ColorProfiles conversion.
            val channels =
                ColorSpace
                    .connect(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), ColorSpace.get(ColorSpace.Named.SRGB))
                    .transform(Color.red(background) / 255f, Color.green(background) / 255f, Color.blue(background) / 255f)
            val expected = Color.rgb((channels[0] * 255).roundToInt(), (channels[1] * 255).roundToInt(), (channels[2] * 255).roundToInt())
            val decoder = MediaMetadataRetriever()
            try {
                decoder.setDataSource(result.filePath)
                val bitmap = requireNotNull(decoder.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST))
                try {
                    assertEquals(66, bitmap.width)
                    assertEquals(34, bitmap.height)
                    assertColour(bitmap, 8, 16, expected)
                    assertColour(bitmap, 32, 16, expected)
                    assertColour(bitmap, 56, 16, expected)
                    assertColour(bitmap, 65, 16, expected)
                    assertColour(bitmap, 32, 33, expected)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                decoder.release()
            }
        }

    private fun assertColour(
        bitmap: Bitmap,
        x: Int,
        y: Int,
        expected: Int,
    ) {
        val actual = bitmap.getPixel(x, y)
        for (shift in listOf(16, 8, 0)) {
            val difference = abs((expected shr shift and 255) - (actual shr shift and 255))
            assertTrue("MP4 colour at ($x, $y), channel $shift: expected $expected, got $actual", difference <= 20)
        }
    }
}
