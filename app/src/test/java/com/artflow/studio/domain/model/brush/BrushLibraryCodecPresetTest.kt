package com.artflow.studio.domain.model.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushLibraryCodecPresetTest {
    @Test
    fun everyBuiltInPresetCanBeSavedAndLoadedAgain() {
        // Saving a preset as a copy is a normal workflow; the codec must accept what the presets contain.
        // Each preset is saved alone, because a library holds at most BrushLibraryCodec.MAX_BRUSHES brushes.
        assertTrue(StudioBrushes.presets.isNotEmpty())
        StudioBrushes.presets.forEachIndexed { index, preset ->
            val saved = SavedBrush(id = "preset-$index", name = preset.name, parameters = preset.parameters)
            val decoded = BrushLibraryCodec.decode(BrushLibraryCodec.encode(listOf(saved)))
            assertEquals(listOf(saved), decoded)
        }
    }

    @Test
    fun rendererLimitsRemainBoundedWhenSparseStampPresetsAreSaved() {
        val limits = BrushParams(spacing = 4f, scatter = 4f, count = 32, grainBrightness = -1f, grainContrast = 1f)
        val saved = SavedBrush("limits", "Stamp limits", limits)
        assertEquals(listOf(saved), BrushLibraryCodec.decode(BrushLibraryCodec.encode(listOf(saved))))
        listOf(
            limits.copy(spacing = 4.01f),
            limits.copy(spacing = Float.NaN),
            limits.copy(scatter = 4.01f),
            limits.copy(count = 33),
            limits.copy(grainBrightness = -1.01f),
            limits.copy(grainContrast = Float.POSITIVE_INFINITY),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                BrushLibraryCodec.encode(listOf(saved.copy(parameters = invalid)))
            }
        }
    }
}
