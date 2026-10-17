package com.artflow.studio.core.canvas

import com.artflow.studio.core.canvas.CanvasOperations.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasPresetsTest {
    private val poster = Preset("Poster", 3000, 4000, 200)
    private val banner = Preset("Banner", 4000, 1000, 150)

    @Test
    fun savedPresetsSurviveAnEncodeDecodeRoundTrip() {
        val saved = listOf(poster, banner, Preset("Bar | ünï ×", 100, 100, 72))
        assertEquals(saved, CanvasPresets.decode(CanvasPresets.encode(saved)))
    }

    @Test
    fun noStoredTextMeansNoPresets() {
        assertTrue(CanvasPresets.decode(null).isEmpty())
        assertTrue(CanvasPresets.decode("").isEmpty())
    }

    @Test
    fun decodeSkipsEveryDamagedLineAndKeepsTheRest() {
        val text =
            listOf(
                "Poster\t3000\t4000\t200",
                "only\tthree\tfields",
                "NotANumber\tx\t100\t72",
                "Huge\t9000\t9000\t72",
                "Many pixels\t8000\t8000\t72",
                "Low dpi\t100\t100\t5",
                "High dpi\t100\t100\t5000",
                "\t100\t100\t72",
                "poster\t10\t10\t72",
                "Banner\t4000\t1000\t150",
            ).joinToString("\n")
        assertEquals(listOf(poster, banner), CanvasPresets.decode(text))
    }

    @Test
    fun decodeKeepsAtMostTheLimit() {
        val text = (1..CanvasPresets.MAX_SAVED + 5).joinToString("\n") { "Size $it\t100\t100\t72" }
        assertEquals(CanvasPresets.MAX_SAVED, CanvasPresets.decode(text).size)
    }

    @Test
    fun addPutsTheNewestFirstAndReplacesTheSameName() {
        val added = CanvasPresets.add(listOf(banner), poster)
        assertEquals(listOf(poster, banner), added)
        val replaced = CanvasPresets.add(added, Preset("POSTER", 500, 500, 72))
        assertEquals(listOf(Preset("POSTER", 500, 500, 72), banner), replaced)
    }

    @Test
    fun addNeverReusesABuiltInName() {
        val first = CanvasOperations.PRESETS.first()
        val added = CanvasPresets.add(emptyList(), Preset(first.name.lowercase(), 640, 480, 72)).single()
        assertEquals(first.name.lowercase() + " (custom)", added.name)
        assertNull(CanvasOperations.presetByName(added.name))
        assertEquals("Screen size (custom)", CanvasPresets.add(emptyList(), Preset("Screen size", 640, 480, 72)).single().name)
    }

    @Test
    fun addNamesABlankPresetAfterItsSizeAndCutsLongNames() {
        assertEquals("640×480", CanvasPresets.add(emptyList(), Preset("   ", 640, 480, 72)).single().name)
        val long = CanvasPresets.add(emptyList(), Preset("x".repeat(100), 640, 480, 72)).single().name
        assertEquals(CanvasPresets.MAX_NAME, long.length)
    }

    @Test
    fun addRefusesASizeThatCouldNotBeAllocated() {
        assertEquals(listOf(banner), CanvasPresets.add(listOf(banner), Preset("Too big", 9000, 9000, 72)))
        assertEquals(listOf(banner), CanvasPresets.add(listOf(banner), Preset("Odd dpi", 100, 100, 5)))
    }

    @Test
    fun addStripsControlCharactersThatWouldSplitAStoredLine() {
        val added = CanvasPresets.add(emptyList(), Preset("A\tB\nC", 640, 480, 72))
        assertEquals(listOf(Preset("ABC", 640, 480, 72)), added)
        assertEquals(added, CanvasPresets.decode(CanvasPresets.encode(added)))
    }

    @Test
    fun addKeepsTheLimit() {
        var saved = emptyList<Preset>()
        for (i in 1..CanvasPresets.MAX_SAVED + 3) saved = CanvasPresets.add(saved, Preset("Size $i", 100, 100, 72))
        assertEquals(CanvasPresets.MAX_SAVED, saved.size)
        assertEquals("Size ${CanvasPresets.MAX_SAVED + 3}", saved.first().name)
    }

    @Test
    fun removeDropsOnlyThatName() {
        assertEquals(listOf(banner), CanvasPresets.remove(listOf(poster, banner), "Poster"))
        assertEquals(listOf(poster, banner), CanvasPresets.remove(listOf(poster, banner), "Missing"))
    }

    @Test
    fun screenPresetFollowsTheDisplay() {
        val screen = CanvasOperations.screenPreset(2800, 1752, 320)
        assertNotNull(screen)
        assertEquals(Preset(CanvasOperations.SCREEN_SIZE, 2800, 1752, 320), screen)
        assertEquals(CanvasOperations.MAX_DPI, CanvasOperations.screenPreset(100, 100, 5000)!!.dpi)
        assertEquals(CanvasOperations.MIN_DPI, CanvasOperations.screenPreset(100, 100, 10)!!.dpi)
        assertNull(CanvasOperations.screenPreset(9000, 9000, 320))
    }

    @Test
    fun builtInPresetsAreUniqueAndAllocatable() {
        val names = CanvasOperations.PRESETS.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue(CanvasOperations.PRESETS.all { CanvasOperations.isSizeSafe(it.width, it.height) })
        assertEquals(Preset("4×6 Photo", 1200, 1800, 300), CanvasOperations.presetByName("4×6 Photo"))
    }
}
