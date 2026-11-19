package com.artflow.studio.data

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.ModelPackage
import com.artflow.studio.core.three.PreparedModelImport
import com.artflow.studio.data.renderer.BitmapPixelBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

@RunWith(AndroidJUnit4::class)
class ModelTextureBudgetDeviceTest {
    @Test
    fun pngJpegAndWebpRemainReadableThroughTheMetadataFirstModelPath() {
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(RED)
            for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP)) {
                val output = ByteArrayOutputStream()
                assertTrue(bitmap.compress(format, 100, output))
                val bytes = output.toByteArray()
                var inspections = 0
                var decodes = 0
                val prepared =
                    PreparedModelImport.prepare(
                        ModelPackage.Contents(OBJ, bytes),
                        decode = {
                            assertEquals(1, inspections)
                            decodes++
                            BitmapPixelBridge.decodeModelTexture(it)
                        },
                        inspect = {
                            inspections++
                            BitmapPixelBridge.encodedDimensions(it)
                        },
                    )
                assertEquals(1, decodes)
                assertEquals(8, prepared.width)
                assertEquals(4, prepared.height)
                assertTrue(
                    requireNotNull(prepared.color).pixels.all { pixel ->
                        (pixel ushr 24) == 255 && (pixel ushr 16 and 255) >= 248 && (pixel ushr 8 and 255) <= 8 && (pixel and 255) <= 8
                    },
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun aHugeCompressedTextureHeaderIsRejectedBeforeThePlatformPixelDecoder() {
        val bytes = PngCodec.encode(PixelBuffer.filled(8, 4, RED))
        ByteBuffer.wrap(bytes).putInt(16, 6000).putInt(20, 6000)
        val checksum = CRC32().apply { update(bytes, 12, 17) }.value
        ByteBuffer.wrap(bytes).putInt(29, checksum.toInt())
        assertEquals(6000 to 6000, BitmapPixelBridge.encodedDimensions(bytes))
        var decodes = 0
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(
                    ModelPackage.Contents(OBJ, bytes),
                    decode = {
                        decodes++
                        BitmapPixelBridge.decodeModelTexture(it)
                    },
                    inspect = BitmapPixelBridge::encodedDimensions,
                    maxSize = 4,
                    memoryBudgetBytes = 8 * 1024 * 1024L,
                )
            }
        assertEquals(0, decodes)
        assertTrue(error.message.orEmpty().contains("memory"))
    }

    private companion object {
        const val OBJ = "v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nusemtl Paint\nf 1/1 2/2 3/3\n"
        val RED = 0xFFFF0000.toInt()
    }
}
