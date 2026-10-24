package com.artflow.studio.data

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.Channels
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

/**
 * Exact Android pixels and paired elapsed loops for normal source-over over two authored backdrops.
 * Reset, validation and evidence serialization are outside timing. This is not CPU time, total
 * preview rendering, device-input/GL/display latency, or native-Procreate performance.
 */
@RunWith(AndroidJUnit4::class)
class OpaqueSourceOverDeviceTest {
    @Test
    fun actualSourceOverKeepsEveryPixelAndRecordsPairedElapsedCalls() {
        val results = JSONArray()
        fixtures().forEach { fixture -> results.put(measure(fixture)) }
        val report =
            JSONObject()
                .put("schemaVersion", 1)
                .put("complete", true)
                .put("clock", "System.nanoTime")
                .put("unit", "nanoseconds")
                .put("measurement", "isolated elapsed source-over loops, including scheduling and GC waits; not CPU time")
                .put("currentCall", "65,536 actual BlendModes.sourceOver calls and destination writes")
                .put("referenceCall", "65,536 pinned prior sourceOver scalar calls and identical destination writes")
                .put(
                    "outsideTiming",
                    JSONArray(listOf("destination reset", "exact pixel validation", "caller ownership validation", "output copy")),
                ).put(
                    "excluded",
                    JSONArray(listOf("total preview rendering", "device input delivery", "GL upload/draw", "display presentation")),
                ).put("performanceThreshold", JSONObject.NULL)
                .put("nativeProcreateComparison", false)
                .put("device", device())
                .put("reference", reference())
                .put("fixtureOrder", JSONArray(listOf("opaque", "mixed")))
                .put("warmupPairsPerFixture", WARMUP_PAIRS)
                .put("measuredPairsPerFixture", MEASURED_PAIRS)
                .put("order", "even pair: current then reference; odd pair: reference then current; restarts for each phase and fixture")
                .put("fixtures", results)
        File(TestEvidence.directory(), REPORT_FILE).writeText(report.toString(2) + "\n")
    }

    private fun measure(fixture: Fixture): JSONObject {
        val source = sourcePixels()
        val backdrop = backdropPixels(source, fixture.opaque)
        val sourceBefore = source.copyOf()
        val backdropBefore = backdrop.copyOf()
        val destination = backdrop.copyOf()
        assertEquals(SOURCE_SHA, sha256(argbBytes(source)))
        assertEquals(fixture.backdropSha, sha256(argbBytes(backdrop)))

        // This scalar copy must first reproduce the independently captured unchanged 8d8d output.
        // The candidate's output never defines its own expected pixels.
        val expected = backdrop.copyOf()
        previousLoop(expected, source)
        assertEquals(fixture.outputSha, sha256(argbBytes(expected)))
        assertEquals(fixture.probe257, expected[257])
        assertEquals(fixture.probe32896, expected[32896])
        assertEquals(if (fixture.opaque) 0xFFFFFFFF.toInt() else 0x00FFFFFF, expected[0])
        var retained: IntArray? = null

        fun transfer(
            current: Boolean,
            retain: Boolean,
        ): Long {
            backdrop.copyInto(destination)
            val operation: () -> Unit =
                if (current) {
                    { currentLoop(destination, source) }
                } else {
                    { previousLoop(destination, source) }
                }
            val start = System.nanoTime()
            operation()
            val end = System.nanoTime()
            assertTrue("Elapsed source-over duration must be nonnegative", end >= start)
            assertArrayEquals("Every output pixel for ${fixture.name}", expected, destination)
            assertArrayEquals("Caller-owned source is immutable", sourceBefore, source)
            assertArrayEquals("Caller-owned backdrop is immutable", backdropBefore, backdrop)
            if (retain) retained = destination.copyOf()
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

        val warmups = (0 until WARMUP_PAIRS).map { pair(it) }
        val measured = (0 until MEASURED_PAIRS).map { pair(it, retainCurrent = it == MEASURED_PAIRS - 1) }
        val output = argbBytes(requireNotNull(retained))
        assertEquals(fixture.outputSha, sha256(output))
        val filename = "opaque-source-over-${fixture.name}.argb"
        File(TestEvidence.directory(), filename).writeBytes(output)
        return fixtureReport(fixture, warmups, measured, filename, output)
    }

    /** Every source alpha visits all 256 values of each independent source color channel. */
    private fun sourcePixels(): IntArray =
        IntArray(SIZE * SIZE) { index ->
            val x = index and 255
            val y = index ushr 8
            (x shl 24) or (y shl 16) or ((x xor y) shl 8) or ((73 * x + 151 * y) and 255)
        }

    private fun backdropPixels(
        source: IntArray,
        opaque: Boolean,
    ): IntArray =
        IntArray(source.size) { index ->
            val alpha = if (opaque) 255 else index ushr 8
            (alpha shl 24) or (source[index].inv() and 0x00FFFFFF)
        }

    private fun currentLoop(
        destination: IntArray,
        source: IntArray,
    ) {
        for (index in source.indices) destination[index] = BlendModes.sourceOver(destination[index], source[index])
    }

    private fun previousLoop(
        destination: IntArray,
        source: IntArray,
    ) {
        for (index in source.indices) destination[index] = previousSourceOver(destination[index], source[index])
    }

    /** Exact original scalar body at 8d8d0e6, including early returns and binary32 operation order. */
    private fun previousSourceOver(
        backdrop: Int,
        source: Int,
    ): Int {
        val asource = Channels.alpha(source) / 255f
        if (asource >= 1f) return source
        if (asource <= 0f) return backdrop

        val abackdrop = Channels.alpha(backdrop) / 255f
        val outAlpha = asource + abackdrop * (1f - asource)
        if (outAlpha <= 1e-6f) return 0

        val invOutAlpha = 1f / outAlpha
        val r = (Channels.red(source) * asource + Channels.red(backdrop) * abackdrop * (1f - asource)) * invOutAlpha
        val g = (Channels.green(source) * asource + Channels.green(backdrop) * abackdrop * (1f - asource)) * invOutAlpha
        val b = (Channels.blue(source) * asource + Channels.blue(backdrop) * abackdrop * (1f - asource)) * invOutAlpha
        return Channels.fromFloats(outAlpha * 255f, r, g, b)
    }

    private fun fixtureReport(
        fixture: Fixture,
        warmups: List<Sample>,
        measured: List<Sample>,
        filename: String,
        output: ByteArray,
    ): JSONObject =
        JSONObject()
            .put("name", fixture.name)
            .put("width", SIZE)
            .put("height", SIZE)
            .put("sourceFormula", "a=x, r=y, g=x xor y, b=(73*x+151*y)%256; index=256*y+x")
            .put("backdropFormula", "a=${if (fixture.opaque) "255" else "y"}, r=255-source.r, g=255-source.g, b=255-source.b")
            .put("sourceArgbBigEndianSHA256", SOURCE_SHA)
            .put("backdropArgbBigEndianSHA256", fixture.backdropSha)
            .put("referenceOutputArgbBigEndianSHA256", fixture.outputSha)
            .put("alphaPairCoverage", if (fixture.opaque) "all 256 source alphas over 255" else "all 65,536 source/backdrop alpha pairs")
            .put("sameDestinationForBothPaths", true)
            .put("destinationResetBeforeEveryCallOutsideTiming", true)
            .put("exactlyValidatedTimedCalls", (WARMUP_PAIRS + MEASURED_PAIRS) * 2)
            .put("sourceAndBackdropImmutabilityCheckedAfterEveryCall", true)
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
                    .put("file", filename)
                    .put("encoding", "row-major uint32 ARGB, big-endian, no header")
                    .put("bytes", output.size)
                    .put("sha256", sha256(output))
                    .put("at257ARGB", hex(fixture.probe257))
                    .put("at32896ARGB", hex(fixture.probe32896))
                    .put("source", "current method result from final measured pair, copied after timing and validation"),
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
            .put("baseCommit", "8d8d0e6885349a427b5ac26bea549b1b2e6868a2")
            .put("sourcePath", "app/src/main/java/com/artflow/studio/core/pixels/BlendModes.kt")
            .put("sourceSHA256", "75a7501cca8788cac6b043b7a6df53750c070e0dc709160b10a2a8a8f47d0aa0")
            .put("channelsSourcePath", "app/src/main/java/com/artflow/studio/core/pixels/PixelBuffer.kt")
            .put("channelsSourceSHA256", "d40d8ea49a353f083947f4ececb3d9d9e2acd77d2fe70b1514d7675a4e6304a3")
            .put("capturedOriginalClassSHA256", "4418fdcc72f8cb3de8137ea8388d3094964dcced9cbc7699aa68e8cb23fcb2f7")
            .put("oracleScope", "Exact unchanged compiled ArtFlow sourceOver output, captured before the candidate change")

    private fun fixtures(): List<Fixture> =
        listOf(
            Fixture(
                "opaque",
                true,
                "43b711f7b3ddcfe38a4cf84c98f887cd2ad6072ddd34b0f3305493f0922045b7",
                "30171c2b9b346e14fe571dc5f8db1dc63e78327dd8db4336963542a5fc349321",
                0xFFFDFE20.toInt(),
                0xFF807F7F.toInt(),
            ),
            Fixture(
                "mixed",
                false,
                "19fed44971def69329bf0ad9b481edc957b6caeda91812abefde64c0ec5a0977",
                "7ddcf8882882064a4968f500a4895c3277b7cf083c40b4a2bd71e3c1e7bf9d06",
                0x027F7F80,
                0xC0805555.toInt(),
            ),
        )

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
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun hex(argb: Int): String = "%08x".format(Locale.ROOT, argb)

    private data class Fixture(
        val name: String,
        val opaque: Boolean,
        val backdropSha: String,
        val outputSha: String,
        val probe257: Int,
        val probe32896: Int,
    )

    private data class Sample(
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
        const val SOURCE_SHA = "63d992573d13a9fbd417ab87118f5440c0968e364ee871a7f9518e68b86235da"
        const val REPORT_FILE = "opaque-source-over-timing.json"
    }
}
