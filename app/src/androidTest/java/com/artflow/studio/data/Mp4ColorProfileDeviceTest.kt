package com.artflow.studio.data

import android.content.Context
import android.graphics.Color
import android.graphics.ColorSpace
import android.media.MediaExtractor
import android.media.MediaFormat
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    fun displayP3MatteMatchesPreparedPixelsAcrossAlphaAndPadding(): Unit =
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
            assertVideoTrack(File(result.filePath), 66, 34, listOf(0L, 120_000L), 360_000L)

            // Android's colour connector is independent of the export's ColorProfiles conversion.
            val channels =
                ColorSpace
                    .connect(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), ColorSpace.get(ColorSpace.Named.SRGB))
                    .transform(Color.red(background) / 255f, Color.green(background) / 255f, Color.blue(background) / 255f)
            val expected = Color.rgb((channels[0] * 255).roundToInt(), (channels[1] * 255).roundToInt(), (channels[2] * 255).roundToInt())
            val file = File(result.filePath)
            assertFirstSyncThumbnail(file, 66, 34)
            val points = listOf(8 to 16, 32 to 16, 56 to 16, 65 to 16, 32 to 33)
            val colors = Mp4FirstFrameProbe.colors(file, 66, 34, points)
            points.zip(colors).forEach { (point, actual) ->
                assertColour(actual, point.first, point.second, expected)
            }
        }

    @Test
    fun singleFrameVideoPreservesSrgbColoursAndFinalDuration(): Unit =
        runBlocking {
            val matte = Color.rgb(36, 104, 172)
            val colors =
                listOf(
                    Color.BLACK,
                    Color.WHITE,
                    Color.RED,
                    Color.GREEN,
                    Color.BLUE,
                    Color.rgb(128, 128, 128),
                    Color.rgb(30, 160, 220),
                    Color.rgb(213, 95, 31),
                    Color.argb(128, 240, 60, 120),
                    Color.TRANSPARENT,
                )
            val width = 16 * colors.size
            val frame = PixelBuffer(width, 32, IntArray(width * 32) { colors[(it % width) / 16] })
            val result =
                exporter
                    .exportAnimation(
                        projectId,
                        "One frame",
                        listOf(frame),
                        listOf(170),
                        ExportOptions(format = ExportFormat.MP4, backgroundColor = matte),
                    ).getOrThrow()
            val file = File(result.filePath)
            file.copyTo(File(TestEvidence.directory(), "mp4-single-frame-colour.mp4"), overwrite = true)
            assertVideoTrack(file, width, 32, listOf(0L), 170_000L)
            assertFirstSyncThumbnail(file, width, 32)
            val points = colors.indices.map { 16 * it + 8 to 16 }
            val decoded = Mp4FirstFrameProbe.colors(file, width, 32, points)
            colors.forEachIndexed { index, color ->
                val alpha = Color.alpha(color) / 255.0

                fun composite(shift: Int): Int {
                    val source = color shr shift and 255
                    val background = matte shr shift and 255
                    return (alpha * source + (1 - alpha) * background).roundToInt()
                }
                val expected = Color.rgb(composite(16), composite(8), composite(0))
                assertColour(decoded[index], points[index].first, points[index].second, expected)
            }
        }

    private fun assertFirstSyncThumbnail(
        file: File,
        width: Int,
        height: Int,
    ) {
        val decoder = MediaMetadataRetriever()
        try {
            decoder.setDataSource(file.absolutePath)
            // The assertion needs the first IDR. Android 8's arbitrary-frame path can reach
            // source EOS before draining a short clip; sync seeking flushes the first IDR.
            val bitmap = requireNotNull(decoder.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
            try {
                assertEquals(width, bitmap.width)
                assertEquals(height, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        } finally {
            decoder.release()
        }
    }

    private fun assertVideoTrack(
        file: File,
        width: Int,
        height: Int,
        timestampsUs: List<Long>,
        durationUs: Long,
    ) {
        // Inspect the bytes actually delivered to a reader, independently of MediaFormat and
        // Media3's ColorInfo constants: ISO BT.709 primaries/transfer/matrix 1, limited range.
        val colr = colorDescription(file.readBytes())
        assertArrayEquals(byteArrayOf(110, 99, 108, 120, 0, 1, 0, 1, 0, 1, 0), colr)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(width, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(height, format.getInteger(MediaFormat.KEY_HEIGHT))
            assertEquals(durationUs, format.getLong(MediaFormat.KEY_DURATION))
            extractor.selectTrack(0)
            assertTrue(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
            val actual = mutableListOf<Long>()
            val sample = ByteBuffer.allocate(512 * 1024)
            while (extractor.readSampleData(sample, 0) >= 0) {
                actual += extractor.sampleTime
                extractor.advance()
                sample.clear()
            }
            assertEquals(timestampsUs, actual)
        } finally {
            extractor.release()
        }
    }

    private fun colorDescription(bytes: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var parent = 0 until bytes.size
        listOf("moov", "trak", "mdia", "minf", "stbl", "stsd", "avc1", "colr").forEach { type ->
            parent = findChild(input, parent, type)
            if (type == "stsd") {
                assertEquals(1, input.getInt(parent.first + 4))
                parent = parent.first + 8..parent.last
            } else if (type == "avc1") {
                parent = parent.first + 78..parent.last
            }
        }
        return bytes.copyOfRange(parent.first, parent.last + 1)
    }

    private fun findChild(
        input: ByteBuffer,
        parent: IntRange,
        type: String,
    ): IntRange {
        var position = parent.first
        val matches = mutableListOf<IntRange>()
        while (position <= parent.last) {
            check(parent.last - position >= 7) { "Incomplete MP4 box header" }
            val declaredSize = input.getInt(position).toLong() and 0xFFFFFFFFL
            val header = if (declaredSize == 1L) 16 else 8
            val remaining = parent.last - position + 1
            check(remaining >= header) { "Incomplete extended MP4 box header" }
            val size =
                when (declaredSize) {
                    0L -> remaining.toLong()
                    1L -> input.getLong(position + 8)
                    else -> declaredSize
                }
            check(size >= header && size <= remaining) { "Invalid MP4 box size" }
            val name = CharArray(4) { input.get(position + 4 + it).toInt().toChar() }.concatToString()
            if (name == type) matches += position + header until position + size.toInt()
            position += size.toInt()
        }
        return matches.single()
    }

    private fun assertColour(
        actual: Int,
        x: Int,
        y: Int,
        expected: Int,
    ) {
        for (shift in listOf(16, 8, 0)) {
            val difference = abs((expected shr shift and 255) - (actual shr shift and 255))
            assertTrue("MP4 colour at ($x, $y), channel $shift: expected $expected, got $actual", difference <= 20)
        }
    }
}
