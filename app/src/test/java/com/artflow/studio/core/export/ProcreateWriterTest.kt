package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.zip.ZipInputStream

/**
 * Round trips through [ProcreateReader]: what [ProcreateWriter] writes must read back with the same tree,
 * names, properties and pixels. Pixels are stored premultiplied, so partially transparent pixels come back as
 * the premultiplied value, which [storedForm] predicts; opaque and transparent pixels come back exactly.
 */
class ProcreateWriterTest {
    private fun filesOf(bytes: ByteArray): ProcreateReader.Files {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        return object : ProcreateReader.Files {
            override val names = entries.keys

            override fun read(name: String) = entries[name]
        }
    }

    private fun reopen(bytes: ByteArray): ProcreateReader.Document = ProcreateReader.open(filesOf(bytes)) { null }

    /** The straight ARGB a pixel reads back as after premultiplied 8-bit storage. */
    private fun storedForm(argb: Int): Int {
        val alpha = argb ushr 24
        if (alpha == 0) return 0
        if (alpha == 255) return argb

        fun channel(shift: Int): Int {
            val premultiplied = (((argb shr shift) and 0xFF) * alpha + 127) / 255
            return minOf(255, (premultiplied * 255 + alpha / 2) / alpha)
        }
        return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** A layer's test pixels: a mix of opaque, transparent and partly transparent pixels, varying with [salt]. */
    private fun painted(
        width: Int,
        height: Int,
        salt: Int,
    ): PixelBuffer {
        val buffer = PixelBuffer(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val alpha =
                    when ((x + 3 * y + salt) % 5) {
                        0 -> 0
                        1 -> 255
                        else -> (x * 17 + y * 29 + salt * 7) and 0xFF
                    }
                val red = (x * 31 + salt) and 0xFF
                val green = (y * 47 + salt * 3) and 0xFF
                val blue = ((x + y) * 5 + salt) and 0xFF
                buffer.pixels[y * width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return buffer
    }

    private fun assertPixels(
        name: String,
        expected: PixelBuffer,
        actual: PixelBuffer,
    ) {
        assertEquals("$name width", expected.width, actual.width)
        assertEquals("$name height", expected.height, actual.height)
        for (index in expected.pixels.indices) {
            val want = storedForm(expected.pixels[index])
            val got = actual.pixels[index]
            if (want != got) {
                val at = "(${index % expected.width}, ${index / expected.width})"
                fail("$name at $at: ${Integer.toHexString(got)} instead of ${Integer.toHexString(want)}")
            }
        }
    }

    private fun layer(
        name: String,
        pixels: PixelBuffer,
        opacity: Float = 1f,
        visible: Boolean = true,
        mode: BlendMode = BlendMode.NORMAL,
        clipped: Boolean = false,
        alphaLocked: Boolean = false,
        mask: SelectionMask? = null,
    ) = ProcreateWriter.Layer(name, pixels, opacity, visible, mode, clipped, alphaLocked, mask)

    /** The layers of [nodes] bottom first, as they are stacked. */
    private fun stacked(nodes: List<ProcreateWriter.Node>): List<ProcreateWriter.Layer> =
        nodes.asReversed().flatMap { node ->
            when (node) {
                is ProcreateWriter.Layer -> listOf(node)
                is ProcreateWriter.Group -> stacked(node.children)
            }
        }

    /** A stand-in composite: the visible layers stacked normally, bottom first (blend modes are ignored). */
    private fun compositeOf(
        width: Int,
        height: Int,
        nodes: List<ProcreateWriter.Node>,
    ): PixelBuffer {
        val composite = PixelBuffer(width, height)
        for (item in stacked(nodes)) {
            if (!item.visible) continue
            for (index in composite.pixels.indices) {
                val top = item.pixels.pixels[index]
                val alpha = ((top ushr 24) * item.opacity).toInt()
                if (alpha == 0) continue
                composite.pixels[index] = over(composite.pixels[index], (alpha shl 24) or (top and 0xFFFFFF))
            }
        }
        return composite
    }

    private fun over(
        below: Int,
        above: Int,
    ): Int {
        val a = above ushr 24
        val b = below ushr 24
        val outAlpha = a + b * (255 - a) / 255
        if (outAlpha == 0) return 0

        fun channel(shift: Int) = (((above shr shift) and 0xFF) * a + ((below shr shift) and 0xFF) * b * (255 - a) / 255) / outAlpha

        return (outAlpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Writes [nodes] with a composite computed from them, as the exporter does. */
    private fun write(
        width: Int,
        height: Int,
        nodes: List<ProcreateWriter.Node>,
        background: Int? = null,
        dpi: Int = 264,
        name: String = "Fixture",
    ): ByteArray = ProcreateWriter.write(width, height, nodes, compositeOf(width, height, nodes), background, dpi, name)

    @Test
    fun theTreeNamesPropertiesCanvasAndBackgroundReadBack() {
        val width = 301
        val height = 157
        val sky = painted(width, height, salt = 1)
        val color = painted(width, height, salt = 2)
        val line = painted(width, height, salt = 3)
        val masked = painted(width, height, salt = 4)
        val mask = SelectionMask(width, height, ByteArray(width * height) { (it % 256).toByte() })
        val nodes =
            listOf(
                layer("Sky", sky, opacity = 0.6f, mode = BlendMode.VIVID_LIGHT, visible = false),
                ProcreateWriter.Group(
                    "Inks",
                    opacity = 0.5f,
                    visible = true,
                    children =
                        listOf(
                            layer("Color", color, opacity = 0.75f, mode = BlendMode.MULTIPLY, clipped = true, alphaLocked = true),
                            layer("Line", line),
                        ),
                ),
                layer("Masked", masked, mask = mask),
            )
        val background = 0xFFE6D9CC.toInt()
        val document = reopen(write(width, height, nodes, background = background, dpi = 264, name = "Odd canvas"))
        assertEquals(width, document.width)
        assertEquals(height, document.height)
        assertEquals(264, document.dpi)
        assertEquals("Odd canvas", document.name)
        assertEquals(background, document.background)
        assertEquals(listOf("Sky", "Inks", "Masked"), document.nodes.map { it.name })

        val readSky = document.nodes[0] as ProcreateReader.Layer
        assertEquals(0.6f, readSky.opacity)
        assertEquals(false, readSky.visible)
        assertEquals(BlendMode.VIVID_LIGHT, readSky.blendMode)
        assertEquals(false, readSky.clipped)
        assertEquals(false, readSky.alphaLocked)

        val inks = document.nodes[1] as ProcreateReader.Group
        assertEquals(0.5f, inks.opacity)
        assertEquals(true, inks.visible)
        val readColor = inks.children[0] as ProcreateReader.Layer
        val readLine = inks.children[1] as ProcreateReader.Layer
        assertEquals("Color", readColor.name)
        assertEquals(0.75f, readColor.opacity)
        assertEquals(BlendMode.MULTIPLY, readColor.blendMode)
        assertTrue(readColor.clipped)
        assertTrue(readColor.alphaLocked)
        assertEquals("Line", readLine.name)

        assertEquals(listOf("Sky", "Color", "Line", "Masked"), document.layers().map { it.name })
        assertPixels("Sky", sky, document.pixels(readSky))
        assertPixels("Color", color, document.pixels(readColor))
        assertPixels("Line", line, document.pixels(readLine))
        val readMasked = document.nodes[2] as ProcreateReader.Layer
        assertPixels("Masked", masked, document.pixels(readMasked))
        assertArrayEquals(mask.coverage, requireNotNull(document.mask(readMasked)).coverage)
        assertNull(document.mask(readSky))
    }

    @Test
    fun layerPixelsRoundTripAtOddAndMultiTileSizes() {
        for ((width, height) in listOf(1 to 1, 255 to 257, 301 to 157, 601 to 333)) {
            val top = painted(width, height, salt = 5)
            val bottom = painted(width, height, salt = 9)
            val nodes = listOf(layer("Top", top, opacity = 0.9f), layer("Bottom", bottom))
            val document = reopen(write(width, height, nodes))
            assertEquals(width, document.width)
            assertEquals(height, document.height)
            val layers = document.layers()
            assertPixels("${width}x$height top", top, document.pixels(layers[0]))
            assertPixels("${width}x$height bottom", bottom, document.pixels(layers[1]))
        }
    }

    @Test
    fun anEmptyLayerReadsBackEmptyAndAHiddenBackgroundReadsAsNull() {
        val document = reopen(write(40, 30, listOf(layer("Empty", PixelBuffer(40, 30))), background = null))
        assertNull(document.background)
        assertTrue(document.pixels(document.layers().single()).pixels.all { it == 0 })
    }

    @Test
    fun theCompositeReadsBackAsWritten() {
        val width = 200
        val height = 130
        val upper = layer("Upper", painted(width, height, salt = 7), opacity = 0.5f)
        val nodes = listOf(upper, layer("Lower", painted(width, height, salt = 6)))
        val composite = compositeOf(width, height, nodes)
        val bytes = ProcreateWriter.write(width, height, nodes, composite, background = 0xFFFFFFFF.toInt())
        assertPixels("composite", composite, requireNotNull(reopen(bytes).composite()))
    }

    @Test
    fun manyLayersKeepTheirOrderPropertiesAndPixels() {
        val layers = (0 until 120).map { index -> layer("Layer $index", painted(37, 29, salt = index), visible = index % 3 != 0) }
        val document = reopen(write(37, 29, layers))
        val readBack = document.layers()
        assertEquals(120, readBack.size)
        assertEquals(layers.map { it.name }, readBack.map { it.name })
        assertEquals(layers.map { it.visible }, readBack.map { it.visible })
        readBack.forEachIndexed { index, item -> assertPixels(item.name, layers[index].pixels, document.pixels(item)) }
    }

    @Test
    fun everyBlendModeRoundTrips() {
        val modes = BlendMode.getLayerBlendModes()
        val nodes =
            modes.map { mode ->
                layer(mode.name, PixelBuffer(4, 4).also { it.pixels.fill(0xFF102030.toInt()) }, mode = mode)
            }
        val document = reopen(write(4, 4, nodes))
        assertEquals(modes, document.layers().map { it.blendMode })
    }

    @Test
    fun groupsNestAndKeepTheirOrderTopFirst() {
        var inner: ProcreateWriter.Node = layer("Deepest", PixelBuffer(9, 9))
        for (level in 1..31) {
            val side = layer("Side $level", PixelBuffer(9, 9))
            inner = ProcreateWriter.Group("Group $level", opacity = 1f, visible = level % 2 == 0, children = listOf(inner, side))
        }
        val document = reopen(write(9, 9, listOf(inner)))
        var node: ProcreateReader.Node = document.nodes.single()
        for (level in 31 downTo 1) {
            val group = node as ProcreateReader.Group
            assertEquals("Group $level", group.name)
            assertEquals(level % 2 == 0, group.visible)
            assertEquals("Side $level", group.children.last().name)
            node = group.children.first()
        }
        assertEquals("Deepest", node.name)
        assertEquals(32, document.layers().size)
    }

    @Test
    fun groupsDeeperThanProcreateAllowsAreRefused() {
        var inner: ProcreateWriter.Node = layer("Leaf", PixelBuffer(4, 4))
        repeat(ProcreateReader.MAX_NESTING + 1) { inner = ProcreateWriter.Group("G", children = listOf(inner)) }
        val failure = runCatching { write(4, 4, listOf(inner)) }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalArgumentException)
    }

    @Test
    fun aLayerOfAnotherSizeIsRefused() {
        val nodes = listOf(layer("Small", PixelBuffer(5, 5)))
        val failure = runCatching { ProcreateWriter.write(10, 10, nodes, PixelBuffer(10, 10)) }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalArgumentException)
    }

    @Test
    fun aCanvasOutsideProcreatesLimitsIsRefused() {
        val empty = PixelBuffer(1, 1)
        val zero = runCatching { ProcreateWriter.write(0, 5, emptyList(), empty) }.exceptionOrNull()
        assertTrue("$zero", zero is IllegalArgumentException)
        val tooWide = runCatching { ProcreateWriter.write(ProcreateReader.MAX_SIDE + 1, 5, emptyList(), empty) }.exceptionOrNull()
        assertTrue("$tooWide", tooWide is IllegalArgumentException)
    }

    @Test
    fun theWrittenFileHasTheArchiveAndLz4TilesAndNoThumbnail() {
        val bytes = write(300, 200, listOf(layer("One", painted(300, 200, salt = 8))))
        val names = ZipInputStream(bytes.inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }
        assertTrue("Document.archive" in names)
        assertTrue(names.none { it.endsWith(".chunk") })
        assertTrue(names.count { it.endsWith(".lz4") } >= 2)
        assertTrue(names.none { it.startsWith("QuickLook") })
    }

    @Test
    fun nestingFollowsTheStackAndKeepsEachGroupContiguous() {
        val groupOne = Layer(id = 10, name = "One", index = 0, isGroup = true)
        val groupTwo = Layer(id = 11, name = "Two", index = 0, isGroup = true, parentGroupId = 10)
        val bottom = Layer(id = 1, name = "a", index = 0)
        val inOne = Layer(id = 2, name = "b", index = 1, parentGroupId = 10)
        val inTwo = Layer(id = 3, name = "d", index = 2, parentGroupId = 11)
        val again = Layer(id = 4, name = "e", index = 3, parentGroupId = 10)
        val nodes = nestStack(listOf(bottom, inOne, inTwo, again), listOf(groupOne, groupTwo))
        assertEquals(listOf("One", "a"), nodes.map { nameOf(it) })
        val one = nodes[0] as ProcreateWriter.Group
        assertEquals(listOf("e", "Two", "b"), one.children.map { nameOf(it) })
        val two = one.children[1] as ProcreateWriter.Group
        assertEquals(listOf("d"), two.children.map { nameOf(it) })
    }

    @Test
    fun aGroupSplitByAnotherLayerBecomesTwoFoldersAsPsdFoldersDo() {
        val group = Layer(id = 10, name = "G", index = 0, isGroup = true)
        val first = Layer(id = 1, name = "x", index = 0, parentGroupId = 10)
        val middle = Layer(id = 2, name = "m", index = 1)
        val last = Layer(id = 3, name = "y", index = 2, parentGroupId = 10)
        val nodes = nestStack(listOf(first, middle, last), listOf(group))
        assertEquals(3, nodes.size)
        assertEquals(listOf("y"), (nodes[0] as ProcreateWriter.Group).children.map { nameOf(it) })
        assertEquals("m", nameOf(nodes[1]))
        assertEquals(listOf("x"), (nodes[2] as ProcreateWriter.Group).children.map { nameOf(it) })
    }

    private fun nestStack(
        items: List<Layer>,
        groups: List<Layer>,
    ): List<ProcreateWriter.Node> =
        ProcreateWriter.nest(
            items = items,
            layerOf = { it },
            groups = groups,
            group = { group, children -> ProcreateWriter.Group(group.name, children = children) },
            leaf = { ProcreateWriter.Layer(it.name, PixelBuffer(2, 2)) },
        )

    private fun nameOf(node: ProcreateWriter.Node): String =
        when (node) {
            is ProcreateWriter.Layer -> node.name
            is ProcreateWriter.Group -> node.name
        }
}
