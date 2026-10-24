package com.artflow.studio.data

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.PixelBufferPool
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.domain.model.layer.Layer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Exact Android-runtime coverage and paired elapsed calls for one isolated pooled raster transfer.
 * Both paths include pool obtain/clear. Validation, dirtying and release happen outside the clock.
 * This is not CPU time, total preview time, input/GL/display latency or native-Procreate performance.
 */
@RunWith(AndroidJUnit4::class)
class CompositorRasterTransferDeviceTest {
    @Test
    fun pooledRasterTransferKeepsEveryByteAndRecordsPairedElapsedCalls() {
        val source = sourcePixels()
        val original = source.pixels.copyOf()
        // Straight nonzero-alpha bytes survive; fully transparent hidden RGB becomes canonical zero.
        val expected = IntArray(original.size) { index -> if (original[index] ushr 24 == 0) 0 else original[index] }
        val input = Compositor.LayerInput(Layer(1L, "Alpha and channel fixture", 0), source)
        val pool = PixelBufferPool(1)
        val compositor = Compositor()
        var retained: IntArray? = null

        fun transfer(
            current: Boolean,
            retain: Boolean,
        ): Long {
            val operation: () -> PixelBuffer? =
                if (current) {
                    { compositor.renderLayerContent(input, SIZE, SIZE, pool) }
                } else {
                    { previousPooledTransfer(pool, source) }
                }
            val start = System.nanoTime()
            val returned = operation()
            val end = System.nanoTime()
            val rendered = requireNotNull(returned)
            assertArrayEquals("Every output alpha and RGB byte", expected, rendered.pixels)
            assertArrayEquals("Source raster is immutable", original, source.pixels)
            if (retain) retained = rendered.pixels.copyOf()
            rendered.pixels.fill(DIRTY)
            assertTrue("The one-buffer pool must accept its returned buffer", pool.release(rendered))
            return end - start
        }

        fun pair(
            index: Int,
            retainCurrent: Boolean = false,
        ): Sample {
            val currentFirst = index % 2 == 0
            val first = transfer(currentFirst, retainCurrent && currentFirst)
            val second = transfer(!currentFirst, retainCurrent && !currentFirst)
            return Sample(index, currentFirst, if (currentFirst) first else second, if (currentFirst) second else first)
        }

        try {
            // Every timed obtain sees this same previously dirty buffer, including the first call.
            val seed = pool.obtain(SIZE, SIZE)
            seed.pixels.fill(DIRTY)
            assertTrue(pool.release(seed))
            val warmups = (0 until WARMUP_PAIRS).map { pair(it) }
            val measured = (0 until MEASURED_PAIRS).map { pair(it, retainCurrent = it == MEASURED_PAIRS - 1) }
            val output = argbBytes(requireNotNull(retained))
            File(TestEvidence.directory(), OUTPUT_FILE).writeBytes(output)
            File(TestEvidence.directory(), TIMING_FILE).writeText(report(warmups, measured, original, output).toString(2) + "\n")
        } finally {
            compositor.release()
            pool.clear()
        }
    }

    /** Each alpha row visits every possible value of each independent RGB channel. */
    private fun sourcePixels(): PixelBuffer =
        PixelBuffer(
            SIZE,
            SIZE,
            IntArray(SIZE * SIZE) { index ->
                val alpha = index / SIZE
                val channel = index % SIZE
                (alpha shl 24) or (channel shl 16) or ((255 - channel) shl 8) or ((channel * 73) and 255)
            },
        )

    /** The pinned prior equal-size placeOnClear loop, with the same pool obtain/clear scope. */
    private fun previousPooledTransfer(
        pool: PixelBufferPool,
        source: PixelBuffer,
    ): PixelBuffer {
        val canvas = pool.obtain(SIZE, SIZE)
        if (source.width != canvas.width || source.height != canvas.height) {
            canvas.drawInto(source, 0, 0)
            return canvas
        }
        val pixels = source.pixels
        val target = canvas.pixels
        for (index in pixels.indices) {
            val pixel = pixels[index]
            when (pixel ushr 24) {
                0 -> Unit
                255 -> target[index] = pixel
                else -> target[index] = BlendModes.sourceOver(0, pixel)
            }
        }
        return canvas
    }

    private fun report(
        warmups: List<Sample>,
        measured: List<Sample>,
        source: IntArray,
        output: ByteArray,
    ): JSONObject =
        JSONObject()
            .put("schemaVersion", 1)
            .put("clock", "System.nanoTime")
            .put("unit", "nanoseconds")
            .put("measurement", "isolated elapsed pooled raster-transfer calls, including scheduling and GC waits; not CPU time")
            .put("currentCall", "Compositor.renderLayerContent with raster only; no strokes, filter, mask or effects")
            .put("referenceCall", "PixelBufferPool.obtain plus the pinned prior placeOnClear loop")
            .put("bothInclude", JSONArray(listOf("pool obtain", "transparent-black pool clear", "raster transfer")))
            .put("outsideTiming", JSONArray(listOf("exact output validation", "source validation", "buffer dirtying", "pool release")))
            .put(
                "excluded",
                JSONArray(listOf("total preview rendering", "device input delivery", "GL upload/draw", "display presentation")),
            ).put("performanceThreshold", JSONObject.NULL)
            .put("nativeProcreateComparison", false)
            .put("device", device())
            .put("reference", reference())
            .put(
                "fixture",
                JSONObject()
                    .put("width", SIZE)
                    .put("height", SIZE)
                    .put("sourceFormula", "a=y, r=x, g=255-x, b=(73*x)%256")
                    .put("expectedFormula", "alpha==0 ? 0x00000000 : source")
                    .put("sourceArgbBigEndianSHA256", sha256(argbBytes(source)))
                    .put("poolCapacity", 1)
                    .put("samePoolForBothPaths", true)
                    .put("dirtyBeforeEveryCallARGB", "a55aa53c")
                    .put("exactlyValidatedCalls", (WARMUP_PAIRS + MEASURED_PAIRS) * 2)
                    .put("sourceImmutabilityCheckedAfterEveryCall", true),
            ).put("warmupPairs", WARMUP_PAIRS)
            .put("measuredPairs", MEASURED_PAIRS)
            .put("order", "even pair: current then reference; odd pair: reference then current; restarts for measured pairs")
            .put("warmups", JSONArray(warmups.map(Sample::json)))
            .put("samples", JSONArray(measured.map(Sample::json)))
            .put(
                "summary",
                JSONObject()
                    .put("sampleCount", MEASURED_PAIRS)
                    .put("medianDefinition", "arithmetic mean of the two middle measured values; warmups excluded")
                    .put("currentMedianNanos", median(measured.map(Sample::currentNanos)))
                    .put("referenceMedianNanos", median(measured.map(Sample::referenceNanos))),
            ).put(
                "output",
                JSONObject()
                    .put("file", OUTPUT_FILE)
                    .put("encoding", "row-major uint32 ARGB, big-endian, no header")
                    .put("bytes", output.size)
                    .put("sha256", sha256(output))
                    .put("source", "current method result from the final measured pair, copied after timing and validation"),
            )

    private fun device(): JSONObject =
        JSONObject()
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("hardware", Build.HARDWARE)
            .put("fingerprint", Build.FINGERPRINT)
            .put("availableProcessors", Runtime.getRuntime().availableProcessors())
            .put("maxHeapBytes", Runtime.getRuntime().maxMemory())
            .put("thread", Thread.currentThread().name)

    private fun reference(): JSONObject =
        JSONObject()
            .put("baseCommit", "e2aa900b91298fe08eca8529d0345220faa9e2eb")
            .put("sourcePath", "app/src/main/java/com/artflow/studio/core/render/Compositor.kt")
            .put("sourceSHA256", "e9a352a39907cec9198f2411103371a3cf05c851870745278ce38752d0a2f78c")
            .put("blendModesSourcePath", "app/src/main/java/com/artflow/studio/core/pixels/BlendModes.kt")
            .put("blendModesSourceSHA256", "75a7501cca8788cac6b043b7a6df53750c070e0dc709160b10a2a8a8f47d0aa0")
            .put("poolSourcePath", "app/src/main/java/com/artflow/studio/core/pixels/PixelBuffer.kt")
            .put("poolSourceSHA256", "d40d8ea49a353f083947f4ececb3d9d9e2acd77d2fe70b1514d7675a4e6304a3")

    private fun median(values: List<Long>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return (sorted[middle - 1].toDouble() + sorted[middle].toDouble()) / 2.0
    }

    private fun argbBytes(pixels: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        pixels.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private class Sample(
        val index: Int,
        val currentFirst: Boolean,
        val currentNanos: Long,
        val referenceNanos: Long,
    ) {
        fun json(): JSONObject =
            JSONObject()
                .put("index", index)
                .put("order", JSONArray(if (currentFirst) listOf("current", "reference") else listOf("reference", "current")))
                .put("currentNanos", currentNanos)
                .put("referenceNanos", referenceNanos)
    }

    private companion object {
        const val SIZE = 256
        const val WARMUP_PAIRS = 6
        const val MEASURED_PAIRS = 20
        const val DIRTY = 0xA55AA53C.toInt()
        const val OUTPUT_FILE = "compositor-raster-transfer.argb"
        const val TIMING_FILE = "compositor-raster-transfer-timing.json"
    }
}
