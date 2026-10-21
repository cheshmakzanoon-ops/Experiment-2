package com.artflow.studio.data.local

import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationSettingsPersistenceTest {
    private fun document(settings: AnimationSettings = AnimationSettings()): CanvasDocument {
        val frames =
            (1L..4L).map { id ->
                AnimationFrame(id, "Frame $id", listOf(Layer(id, "Layer $id", 0)))
            }
        return CanvasDocument(
            width = 8,
            height = 6,
            dpi = 72,
            backgroundColor = 0,
            activeLayerId = 1,
            nextLayerId = 5,
            layers = frames.first().layers,
            frames = frames,
            animation = settings,
            savedAt = 0,
        )
    }

    @Test
    fun defaultVisibilityAndPinsDoNotChangeTheHistoricalChecksummedShape() {
        val original = document()
        val encoded = CanvasDocumentCodec.encode(original).decodeToString()
        val animationJson =
            Json
                .parseToJsonElement(encoded)
                .jsonObject
                .getValue("animation")
                .jsonObject
        assertFalse("backgroundFrameIds" in animationJson)
        assertFalse("foregroundFrameIds" in animationJson)
        assertFalse("hiddenFrameIds" in animationJson)
        assertEquals(original, CanvasDocumentCodec.decode(encoded))
    }

    @Test
    fun multiplePinsHiddenFramesRangeAndCustomOnionColorsSurviveChecksummedRoundTrip() {
        val settings =
            AnimationSettings(
                backgroundFrameIds = setOf(1, 2),
                foregroundFrameIds = setOf(4),
                hiddenFrameIds = setOf(2),
                playbackRangeStart = 1,
                playbackRangeEnd = 3,
                pingPong = true,
                onionSkinFrames = 12,
                onionSkinOpacity = 0f,
                onionSkinPreviousColor = 0xFF112233.toInt(),
                onionSkinNextColor = 0xFFABCDEF.toInt(),
                onionSkinTinted = true,
                blendPrimaryFrame = true,
            )
        val original = document(settings)
        CanvasDocumentCodec.requireValidStructure(original)
        val decoded = CanvasDocumentCodec.decode(CanvasDocumentCodec.encode(original).decodeToString())
        assertEquals(settings, decoded.animation)
        assertEquals(original, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun contradictoryRolesAreRejectedOnImport() {
        CanvasDocumentCodec.requireValidStructure(document(AnimationSettings(backgroundFrameIds = setOf(1), foregroundFrameIds = setOf(1))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingFrameReferencesAreRejectedOnImport() {
        CanvasDocumentCodec.requireValidStructure(document(AnimationSettings(hiddenFrameIds = setOf(500))))
    }

    @Test
    fun checksumDetectsEditsToHiddenFrameMembership() {
        val encoded = CanvasDocumentCodec.encode(document(AnimationSettings(hiddenFrameIds = setOf(2)))).decodeToString()
        val changed = encoded.replace("\"hiddenFrameIds\":[2]", "\"hiddenFrameIds\":[3]")
        assertTrue(changed != encoded)
        assertTrue(runCatching { CanvasDocumentCodec.decode(changed) }.isFailure)
    }
}
