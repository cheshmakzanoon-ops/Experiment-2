package com.artflow.studio.core.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * Opens documents saved by Procreate itself. They are not committed: CI downloads them and points
 * PROCREATE_SAMPLES at their folder; outside CI the test is skipped when no samples are available. Each document must
 * calibrate against its own thumbnail and decode every layer and mask; how closely ArtFlow's
 * composite of the imported layers matches the flattened image Procreate saved is reported to
 * build/procreate-samples.txt.
 */
class RealProcreateFilesTest {
    @Test
    fun `documents saved by Procreate import`() {
        val folder = System.getenv("PROCREATE_SAMPLES")?.let(::File)
        requireSamples("No Procreate samples were downloaded", folder?.isDirectory == true)
        val samples = folder!!.listFiles { file -> file.name.endsWith(".procreate") }.orEmpty().sortedBy { it.name }
        requireSamples("The samples folder holds no documents", samples.isNotEmpty())
        requireSamples("A pinned Procreate sample is missing", samples.map { it.name }.containsAll(NATIVE_FILE_HASHES.keys))
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        samples.forEach { sample ->
            runCatching { check(sample, report) }.onFailure { failures += "${sample.name}: $it" }
        }
        File("build").mkdirs()
        File("build/procreate-samples.txt").writeText(report.toString() + failures.joinToString("\n", prefix = "\n"))
        println(report)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * In CI a missing sample fails the test rather than skipping it, because android-ci.yml already
     * fails the job when the samples cannot be fetched. Elsewhere the checks are skipped as before.
     */
    private fun requireSamples(
        message: String,
        condition: Boolean,
    ) {
        if (System.getenv("CI") == "true") assertTrue(message, condition) else assumeTrue(message, condition)
    }

    private fun check(
        sample: File,
        report: StringBuilder,
    ) {
        NATIVE_FILE_HASHES[sample.name]?.let { assertEquals("${sample.name} input changed", it, sha256(sample.readBytes())) }
        ZipFile(sample).use { zip ->
            val files =
                object : ProcreateReader.Files {
                    override val names = zip.entries().toList().map { it.name }

                    override fun read(name: String) = zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
                }
            val document = ProcreateReader.open(files, TestPng::decodeSrgb)
            val layers = document.layers()
            NATIVE_COUNTS[sample.name]?.let { (expectedLayers, expectedGroups) ->
                assertEquals(expectedLayers, layers.size)
                assertEquals(expectedGroups, groupCount(document.nodes))
                assertEquals(ColorProfile.DISPLAY_P3, document.colorProfile)
                assertFalse(document.hasUnsupportedColorProfile)
                val archive = KeyedArchive(BinaryPlist.read(requireNotNull(files.read("Document.archive"))))
                val descriptor = archive.map(requireNotNull(archive.root)["colorProfile"])
                val icc = requireNotNull(archive.data(descriptor?.get("SiColorProfileArchiveICCDataKey")))
                assertEquals(536, icc.size)
                val expectedIcc = if (sample.name in DERIVED_PROFILES) DERIVED_ICC_SHA else APPLE_ICC_SHA
                assertEquals(expectedIcc, sha256(icc))
            }
            val masks = layers.count { document.mask(it) != null }
            // Blending is per pixel, so compositing layers sampled on a grid gives the full composite's pixels on that grid.
            val step = maxOf(1, maxOf(document.width, document.height) / GRID)
            val recomposited = recomposite(document, step)
            if (sample.name == "Mask_Test_File.procreate") checkNativeMask(document, recomposited, step)
            val difference = document.composite()?.let { meanDifference(subsample(it, step), recomposited, document.background ?: WHITE) }
            report.appendLine(
                "${sample.name}: ${document.width}x${document.height} ${document.dpi} dpi, " +
                    "${layers.size} layers, ${groupCount(document.nodes)} groups, $masks masks, " +
                    "profile ${document.colorProfile}, unsupported profile ${document.hasUnsupportedColorProfile}, " +
                    "blend modes ${layers.map { it.blendMode }.distinct()}, calibration error " +
                    "%.2f, composite difference %s".format(document.calibrationError, difference?.let { "%.2f".format(it) } ?: "n/a"),
            )
            assertTrue("${sample.name} did not match its thumbnail", document.calibrationError < MAX_CALIBRATION_ERROR)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun groupCount(nodes: List<ProcreateReader.Node>): Int =
        nodes.sumOf { if (it is ProcreateReader.Group) 1 + groupCount(it.children) else 0 }

    /** Fixed native pixels distinguish genuine one-byte mask coverage from a discarded all-white mask. */
    private fun checkNativeMask(
        document: ProcreateReader.Document,
        recomposited: PixelBuffer,
        step: Int,
    ) {
        val layer = document.layers().single()
        val mask = requireNotNull(document.mask(layer))
        val composite = requireNotNull(document.composite())
        val expected = listOf(Triple(1244, 616, 128), Triple(1280, 640, 128), Triple(1024, 1024, 255))
        expected.forEach { (x, y, coverage) ->
            assertEquals(coverage, mask.coverage[y * document.width + x].toInt() and 255)
            val pixel = if (coverage == 128) 0xFF7F7F7F.toInt() else 0xFF000000.toInt()
            assertEquals(pixel, composite.pixels[y * document.width + x])
            assertEquals(pixel, recomposited.pixels[(y / step) * recomposited.width + x / step])
        }
    }

    /** Composites the document the way the import lays it out: bottom first, groups as headers above their members. */
    private fun recomposite(
        document: ProcreateReader.Document,
        step: Int,
    ): PixelBuffer {
        val inputs = mutableListOf<Compositor.LayerInput>()
        var next = 0L

        fun add(
            node: ProcreateReader.Node,
            parent: Long?,
        ) {
            val id = ++next
            when (node) {
                is ProcreateReader.Group -> {
                    node.children.asReversed().forEach { add(it, id) }
                    val header =
                        Layer(
                            id,
                            node.name,
                            inputs.size,
                            node.visible,
                            node.opacity,
                            blendMode = BlendMode.PASS_THROUGH,
                            parentGroupId = parent,
                            isGroup = true,
                        )
                    inputs += Compositor.LayerInput(header)
                }

                is ProcreateReader.Layer -> {
                    val layer =
                        Layer(
                            id,
                            node.name,
                            inputs.size,
                            node.visible,
                            node.opacity,
                            blendMode = node.blendMode,
                            isClippingMask = node.clipped,
                            parentGroupId = parent,
                        )
                    val mask =
                        document.mask(node)?.let { coverage ->
                            val grey = PixelBuffer(coverage.width, coverage.height)
                            coverage.coverage.forEachIndexed { index, value ->
                                grey.pixels[index] =
                                    OPAQUE or ((value.toInt() and 0xFF) * GREY)
                            }
                            subsample(grey, step)
                        }
                    inputs += Compositor.LayerInput(layer, subsample(document.pixels(node), step), mask = mask)
                }
            }
        }
        document.nodes.asReversed().forEach { add(it, null) }
        val first = inputs.firstNotNullOfOrNull { it.raster } ?: subsample(PixelBuffer(document.width, document.height), step)
        return Compositor().composite(inputs, first.width, first.height, document.background ?: 0)
    }

    /** Every [step]th pixel of every [step]th row. */
    private fun subsample(
        buffer: PixelBuffer,
        step: Int,
    ): PixelBuffer {
        val out = PixelBuffer((buffer.width + step - 1) / step, (buffer.height + step - 1) / step)
        for (y in 0 until out.height) {
            for (x in 0 until out.width) out.pixels[y * out.width + x] = buffer.pixels[y * step * buffer.width + x * step]
        }
        return out
    }

    /** Mean difference per channel (0-255) of the two images over [background]. */
    private fun meanDifference(
        expected: PixelBuffer,
        actual: PixelBuffer,
        background: Int,
    ): Double {
        var total = 0L
        var count = 0
        for (y in 0 until minOf(expected.height, actual.height)) {
            for (x in 0 until minOf(expected.width, actual.width)) {
                val a = over(expected.pixels[y * expected.width + x], background)
                val b = over(actual.pixels[y * actual.width + x], background)
                for (shift in intArrayOf(16, 8, 0)) total += abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                count += 3
            }
        }
        return if (count == 0) 0.0 else total.toDouble() / count
    }

    private fun over(
        pixel: Int,
        background: Int,
    ): Int {
        val alpha = pixel ushr 24
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val value = (((pixel shr shift) and 0xFF) * alpha + ((background shr shift) and 0xFF) * (255 - alpha)) / 255
            out = out or (value shl shift)
        }
        return out
    }

    private companion object {
        val NATIVE_FILE_HASHES =
            mapOf(
                "Mask_Test_File.procreate" to "17cd313a1b56950738de392595d33045c423469ff9385b5a63efb6e3f37aa83c",
                "Reference_Blend_File 2.procreate" to "097c338d054452887e2f4835d26a58a41ca854d4777223512334d3895b90daf0",
                "Reference_Blend_File 3.procreate" to "8b288e0886b3cc68030dcfdeedd7ceeb37bb1e41cc3d29ac5061d968821d59db",
                "Reference_Blend_File.procreate" to "4dca07aea1389ed521eb24f43e1a4e03dcd32d723c657d38f3e44ee69ced02f4",
                "reference.procreate" to "8b288e0886b3cc68030dcfdeedd7ceeb37bb1e41cc3d29ac5061d968821d59db",
            )
        val DERIVED_PROFILES = setOf("Reference_Blend_File.procreate", "Reference_Blend_File 2.procreate")
        const val APPLE_ICC_SHA = "0ff6958f98684c61f6bbdce1368ddeaf3873baf84545baba482e920d92a914c0"
        const val DERIVED_ICC_SHA = "20789fdbea9835251a4f0796c8bf45cbd964896044886540da21ffc7457af0ab"
        val NATIVE_COUNTS =
            mapOf(
                "Mask_Test_File.procreate" to (1 to 0),
                "Reference_Blend_File 2.procreate" to (53 to 26),
                "Reference_Blend_File 3.procreate" to (55 to 27),
                "Reference_Blend_File.procreate" to (53 to 26),
                "reference.procreate" to (55 to 27),
            )
        const val MAX_CALIBRATION_ERROR = 25.0
        const val GRID = 512
        const val WHITE = 0xFFFFFFFF.toInt()
        const val OPAQUE = 0xFF shl 24
        const val GREY = 0x010101
    }
}
