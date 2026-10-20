package com.artflow.studio.domain.model.brush

import org.junit.Assert.assertEquals
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
}
