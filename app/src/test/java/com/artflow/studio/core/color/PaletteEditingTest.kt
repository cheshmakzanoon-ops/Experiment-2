package com.artflow.studio.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PaletteEditingTest {
    private val palette = Palette(id = 5, name = "Warm", colors = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), category = "Custom")

    @Test
    fun aSwatchCanBeReplacedWithoutTouchingTheOthers() {
        val edited = palette.withColorAt(1, 0xFF0000FF.toInt())
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), edited.colors)
        assertEquals(palette.copy(colors = edited.colors), edited)
    }

    @Test
    fun anIndexOutsideThePaletteChangesNothing() {
        assertSame(palette, palette.withColorAt(2, 1))
        assertSame(palette, palette.withColorAt(-1, 1))
    }

    @Test
    fun appendingKeepsDuplicatesUnlikeWithColor() {
        val again = palette.withColorAppended(0xFFFF0000.toInt())
        assertEquals(3, again.colors.size)
        // withColor ignores a near-identical colour, which is right for history but wrong for a swatch the user chose to add.
        assertEquals(2, palette.withColor(0xFFFF0000.toInt()).colors.size)
    }

    @Test
    fun removingASwatchShiftsTheRest() {
        assertEquals(listOf(0xFF00FF00.toInt()), palette.withoutColor(0).colors)
        assertSame(palette, palette.withoutColor(7))
    }

    @Test
    fun aDuplicateStartsFromTheOriginalWithANewIdentity() {
        val copy = palette.renamed("Warm copy")
        assertEquals(0L, copy.id)
        assertEquals("Warm copy", copy.name)
        assertEquals(palette.colors, copy.colors)
    }
}
