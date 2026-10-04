package com.artflow.studio.core.export

import com.artflow.studio.core.export.PsdCodec.PsdLayer
import com.artflow.studio.core.export.PsdCodec.Section
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PsdGroupsTest {
    private fun solid(colour: Int) = PixelBuffer.filled(4, 4, colour)

    @Test
    fun nestedFoldersSurviveARoundTrip() {
        // Bottom first: Paper, then Outer { Sky, Inner { Sun } }.
        val layers =
            listOf(
                PsdLayer("Paper", solid(0xFFFFFFFF.toInt())),
                PsdLayer.groupEnd(),
                PsdLayer("Sky", solid(0xFF3366CC.toInt())),
                PsdLayer.groupEnd(),
                PsdLayer("Sun", solid(0xFFFFCC00.toInt()), isClippingMask = true),
                PsdLayer.groupHeader("Inner", opacity = 128, isVisible = false, blendMode = BlendMode.MULTIPLY),
                PsdLayer.groupHeader("Outer"),
            )
        for (rle in listOf(true, false)) {
            val bytes = PsdCodec.write(4, 4, layers, solid(0xFFFFFFFF.toInt()), useRle = rle)
            val read = requireNotNull(PsdCodec.read(bytes))
            assertEquals(
                listOf(
                    Section.NONE,
                    Section.GROUP_END,
                    Section.NONE,
                    Section.GROUP_END,
                    Section.NONE,
                    Section.GROUP_OPEN,
                    Section.GROUP_OPEN,
                ),
                read.layers.map { it.section },
            )
            assertEquals(
                listOf("Paper", PsdCodec.GROUP_END_NAME, "Sky", PsdCodec.GROUP_END_NAME, "Sun", "Inner", "Outer"),
                read.layers.map { it.name },
            )
            val inner = read.layers[5]
            assertEquals(128, inner.opacity)
            assertFalse(inner.isVisible)
            assertEquals(BlendMode.MULTIPLY, inner.blendMode)
            assertEquals(BlendMode.PASS_THROUGH, read.layers[6].blendMode)
            assertEquals(0xFFFFCC00.toInt(), read.layers[4].pixels.pixels[0])
            assertEquals(true, read.layers[4].isClippingMask)
        }
    }

    @Test
    fun groupsBecomeNestedFoldersAndHiddenGroupsHideTheirLayers() {
        val outer = Layer(10, "Outer", 0, isGroup = true)
        val inner = Layer(11, "Inner", 0, isGroup = true, parentGroupId = 10, isVisible = false)
        val stack =
            listOf(
                Layer(1, "Paper", 0),
                Layer(2, "Sky", 1, parentGroupId = 10),
                Layer(3, "Sun", 2, parentGroupId = 11),
                Layer(4, "Ink", 3),
            )

        fun records(includeHidden: Boolean) =
            PsdFolders.records(stack, { it }, listOf(outer, inner), includeHidden, { PsdLayer.groupHeader(it.name) }) {
                PsdLayer(it.name, solid(0))
            }
        assertEquals(
            listOf("Paper", PsdCodec.GROUP_END_NAME, "Sky", PsdCodec.GROUP_END_NAME, "Sun", "Inner", "Outer", "Ink"),
            records(includeHidden = true).map { it.name },
        )
        // Without hidden layers the hidden Inner group and its Sun go; Outer keeps Sky.
        assertEquals(listOf("Paper", PsdCodec.GROUP_END_NAME, "Sky", "Outer", "Ink"), records(includeHidden = false).map { it.name })
    }
}
