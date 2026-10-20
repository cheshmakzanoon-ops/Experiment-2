package com.artflow.studio.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProcreateSwatchesTest {
    private fun archive(
        json: String,
        entryName: String = "Swatches.json",
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(json.toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    @Test
    fun readsAListOfPalettesAndSkipsEmptySlots() {
        val json =
            """[{"name":"Warm","swatches":[
                {"hue":0,"saturation":1,"brightness":1,"alpha":1,"origin":2,"colorSpace":0},
                null,
                {"hue":0.3333333,"saturation":1,"brightness":1,"alpha":0.5}]}]"""
        val palettes = ProcreateSwatches.read(archive(json))
        assertEquals(1, palettes.size)
        assertEquals("Warm", palettes[0].name)
        assertEquals(listOf(0xFFFF0000.toInt(), 0x8000FF00.toInt()), palettes[0].colors)
        assertEquals("Procreate import", palettes[0].source)
    }

    @Test
    fun readsASinglePaletteObjectAndIgnoresUnknownKeys() {
        val json = """{"name":"Solo","extra":{"a":1},"swatches":[{"hue":0.6666667,"saturation":1,"brightness":1,"note":"x"}]}"""
        val palette = ProcreateSwatches.read(archive(json)).single()
        assertEquals("Solo", palette.name)
        assertEquals(listOf(0xFF0000FF.toInt()), palette.colors)
    }

    @Test
    fun hueWrapsAndOutOfRangeChannelsAreClamped() {
        val json = """[{"name":"Wrap","swatches":[
            {"hue":1.0,"saturation":2,"brightness":3},
            {"hue":-0.6666667,"saturation":1,"brightness":1},
            {"hue":1e300,"saturation":-5,"brightness":0}]}]"""
        val colors = ProcreateSwatches.read(archive(json)).single().colors
        assertEquals(0xFFFF0000.toInt(), colors[0])
        assertEquals(0xFF00FF00.toInt(), colors[1])
        assertEquals(0xFF000000.toInt(), colors[2])
    }

    @Test
    fun unreadableSwatchesAreSkippedAndAnEmptyPaletteIsDropped() {
        val json =
            """[{"name":"Bad","swatches":[{"hue":"red"},{"saturation":1},7,"x"]},""" +
                """{"name":"","swatches":[{"hue":0,"saturation":0,"brightness":1}]}]"""
        val palettes = ProcreateSwatches.read(archive(json), fallbackName = "Fallback")
        assertEquals(1, palettes.size)
        assertEquals("Fallback", palettes[0].name)
        assertEquals(listOf(0xFFFFFFFF.toInt()), palettes[0].colors)
    }

    @Test
    fun malformedInputYieldsNothing() {
        assertTrue(ProcreateSwatches.read(ByteArray(0)).isEmpty())
        assertTrue(ProcreateSwatches.read("not a zip".toByteArray()).isEmpty())
        assertTrue(ProcreateSwatches.read(archive("{not json")).isEmpty())
        assertTrue(ProcreateSwatches.read(archive("[1,2,3]")).isEmpty())
        assertTrue(ProcreateSwatches.read(archive("""[{"name":"x"}]""")).isEmpty())
        assertTrue(ProcreateSwatches.read(archive("[]", entryName = "Other.json")).isEmpty())
    }

    @Test
    fun theJsonEntryIsFoundInsideAFolder() {
        val json = """[{"name":"Nested","swatches":[{"hue":0,"saturation":0,"brightness":0}]}]"""
        assertEquals(listOf(0xFF000000.toInt()), ProcreateSwatches.read(archive(json, "Palette/Swatches.json")).single().colors)
    }

    @Test
    fun anInflatedEntryPastTheBudgetIsRefused() {
        val padding = " ".repeat(5 * 1024 * 1024)
        val bomb = archive("""[{"name":"Big","swatches":[{"hue":0,"saturation":0,"brightness":0}]$padding}]""")
        assertTrue("compressed form stays small", bomb.size < ProcreateSwatches.MAX_ARCHIVE_BYTES)
        assertTrue(ProcreateSwatches.read(bomb).isEmpty())
    }

    @Test
    fun writeThenReadRoundTripsEveryColourWithinTheHsbQuantisation() {
        val colors = listOf(0xFFFF0000.toInt(), 0xFF336699.toInt(), 0xFFFFD166.toInt(), 0x80123456.toInt(), 0xFF000000.toInt())
        val palette = Palette(name = "Round trip é", colors = colors)
        val back = ProcreateSwatches.read(ProcreateSwatches.write(palette)).single()
        assertEquals(palette.name, back.name)
        assertEquals(colors.size, back.colors.size)
        colors.zip(back.colors).forEach { (want, got) ->
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val delta = ((want shr shift) and 0xFF) - ((got shr shift) and 0xFF)
                assertTrue("channel at $shift differs by $delta for ${want.toUInt().toString(16)}", kotlin.math.abs(delta) <= 1)
            }
        }
    }

    @Test
    fun writtenFilesPadToThirtySlotsAndKeepLongerPalettesWhole() {
        fun slots(palette: Palette): Int {
            val bytes = ProcreateSwatches.write(palette)
            val text =
                java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                    zip.nextEntry
                    zip.readBytes().toString(Charsets.UTF_8)
                }
            return kotlinx.serialization.json.Json
                .parseToJsonElement(text)
                .let { it as kotlinx.serialization.json.JsonArray }[0]
                .let { (it as kotlinx.serialization.json.JsonObject)["swatches"] as kotlinx.serialization.json.JsonArray }
                .size
        }
        assertEquals(30, slots(Palette(name = "Short", colors = listOf(0xFF112233.toInt()))))
        assertEquals(45, slots(Palette(name = "Long", colors = List(45) { 0xFF000000.toInt() or it })))
    }

    @Test
    fun theCodecSniffsSwatchesFilesAndEncodesThem() {
        val palette = Palette(name = "Sniff", colors = listOf(0xFF00FF00.toInt()))
        val bytes = PaletteCodec.encode(palette, PaletteCodec.Format.SWATCHES)
        assertTrue(ProcreateSwatches.isArchive(bytes))
        val decoded = PaletteCodec.decode(bytes, "Fallback")
        assertEquals("Sniff", decoded?.name)
        assertEquals(listOf(0xFF00FF00.toInt()), decoded?.colors)
        assertNull(PaletteCodec.decode(archive("[]"), "Fallback"))
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("Warm Sunset.swatches", ProcreateSwatches.fileName("Warm/ Sunset?"))
        assertEquals("Palette.swatches", ProcreateSwatches.fileName("///"))
    }
}
