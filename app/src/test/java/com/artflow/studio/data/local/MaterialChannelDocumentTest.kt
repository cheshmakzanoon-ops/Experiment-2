package com.artflow.studio.data.local

import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialChannelDocumentTest {
    @Test fun versionSixPreservesBothMaterialChannelsAcrossFramesAndSignedRoundTrips() {
        val layers =
            MaterialChannel.entries.mapIndexed {
                    index,
                    channel,
                ->
                Layer(index + 1L, channel.name, index, materialChannel = channel)
            }
        val document =
            document(layers).copy(
                frames =
                    listOf(
                        AnimationFrame(1, "One", layers),
                        AnimationFrame(2, "Two", layers.map { it.copy(id = it.id + 3) }),
                    ),
                nextLayerId = 7,
            )
        val encoded = CanvasDocumentCodec.encode(document).decodeToString()
        val decoded = CanvasDocumentCodec.decode(encoded)
        CanvasDocumentCodec.requireValidHeader(decoded)
        CanvasDocumentCodec.requireValidStructure(decoded)
        assertEquals(document, decoded)
        assertTrue(encoded.contains("\"version\":6"))
        assertEquals(encoded, CanvasDocumentCodec.encode(decoded).decodeToString())
        assertFalse(encoded.contains("\"materialChannel\":\"COLOR\""))
        assertEquals(layers, layers.map { it.copyWith() })
    }

    @Test fun legacyDocumentsDefaultToColorButCannotHideMaterialLayersFromOldReaders() {
        for (version in 1..5) {
            val legacy = document(listOf(Layer(1, "Paint", 0))).copy(version = version)
            val encoded = CanvasDocumentCodec.encode(legacy).decodeToString()
            assertFalse(encoded.contains("materialChannel"))
            assertEquals(
                MaterialChannel.COLOR,
                CanvasDocumentCodec
                    .decode(encoded)
                    .layers
                    .single()
                    .materialChannel,
            )
            val forged = legacy.copy(layers = listOf(legacy.layers.single().copy(materialChannel = MaterialChannel.METALLIC)))
            assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.encode(forged) }
            val unsigned = Json { encodeDefaults = true }.encodeToString(CanvasDocument.serializer(), forged)
            assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.decode(unsigned) }
        }
    }

    @Test fun editedChannelChecksumFailsBeforeMigrationAndGroupsCannotPretendToBeMaps() {
        val layer = Layer(1, "Metal", 0, materialChannel = MaterialChannel.METALLIC)
        val bytes = CanvasDocumentCodec.encode(document(listOf(layer))).decodeToString()
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                CanvasDocumentCodec.decode(bytes.replace("\"METALLIC\"", "\"ROUGHNESS\""))
            }
        assertTrue(error.message.orEmpty().contains("checksum"))
        assertThrows(IllegalArgumentException::class.java) {
            CanvasDocumentCodec.encode(document(listOf(layer.copy(isGroup = true))))
        }
    }

    private fun document(layers: List<Layer>) =
        CanvasDocument(
            width = 8,
            height = 8,
            dpi = 72,
            backgroundColor = 0,
            activeLayerId = layers.first().id,
            nextLayerId = 4,
            layers = layers,
            savedAt = 1L,
        )
}
