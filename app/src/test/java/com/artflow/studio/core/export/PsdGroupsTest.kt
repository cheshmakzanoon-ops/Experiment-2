package com.artflow.studio.core.export

import com.artflow.studio.core.export.PsdCodec.PsdLayer
import com.artflow.studio.core.export.PsdCodec.Section
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.LayerGroups
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

    @Test
    fun normalAndPassThroughGroupsKeepTheirDistinctScopesAcrossAFileRoundTrip() {
        val plain = Layer(10, "Plain", 0, isGroup = true)
        val multiply = Layer(11, "Multiply", 0, isGroup = true, blendMode = BlendMode.MULTIPLY)
        val faded = Layer(12, "Faded", 0, isGroup = true, opacity = 0.5f)
        val passing = Layer(13, "Pass Through", 0, isGroup = true, blendMode = BlendMode.PASS_THROUGH)
        assertEquals(BlendMode.NORMAL, PsdFolders.headerBlend(plain))
        assertEquals(BlendMode.MULTIPLY, PsdFolders.headerBlend(multiply))
        assertEquals(BlendMode.NORMAL, PsdFolders.headerBlend(faded))
        assertEquals(BlendMode.PASS_THROUGH, PsdFolders.headerBlend(passing))

        // Each folder holds one layer: records are bottom first, so the divider comes before its header.
        val layers =
            listOf(
                PsdLayer.groupEnd(),
                PsdLayer("PlainInk", solid(0xFF000000.toInt())),
                PsdLayer.groupHeader("Plain", blendMode = PsdFolders.headerBlend(plain)),
                PsdLayer.groupEnd(),
                PsdLayer("MultiplyInk", solid(0xFF000000.toInt())),
                PsdLayer.groupHeader("Multiply", blendMode = PsdFolders.headerBlend(multiply)),
                PsdLayer.groupEnd(),
                PsdLayer("FadedInk", solid(0xFF000000.toInt())),
                PsdLayer.groupHeader("Faded", opacity = 128, blendMode = PsdFolders.headerBlend(faded)),
                PsdLayer.groupEnd(),
                PsdLayer("PassingInk", solid(0xFF000000.toInt())),
                PsdLayer.groupHeader("Pass Through", blendMode = PsdFolders.headerBlend(passing)),
            )
        val read = requireNotNull(PsdCodec.read(PsdCodec.write(4, 4, layers, solid(0xFFFFFFFF.toInt()))))
        val folders = read.layers.filter { it.section.isGroupHeader }
        assertEquals(listOf("Plain", "Multiply", "Faded", "Pass Through"), folders.map { it.name })
        assertEquals(listOf(BlendMode.NORMAL, BlendMode.MULTIPLY, BlendMode.NORMAL, BlendMode.PASS_THROUGH), folders.map { it.blendMode })
        // Read back the way the importer does, each folder isolates exactly when it did before the export.
        listOf(plain, multiply, faded, passing).zip(folders).forEach { (original, folder) ->
            val reimported =
                Layer(
                    original.id,
                    folder.name,
                    0,
                    isGroup = true,
                    opacity = folder.opacity / 255f,
                    blendMode = PsdFolders.importedBlend(folder.blendMode),
                )
            assertEquals(folder.name, LayerGroups.isolates(original), LayerGroups.isolates(reimported))
        }
    }
}
