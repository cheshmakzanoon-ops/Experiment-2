package com.artflow.studio.data.local

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.model.layer.LayerBlendContract
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class NativeLayerBlendDocumentTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun signedPublishedVersionFourStillVerifiesAndRendersItsOriginalPixels() {
        val bytes = fixture()
        assertEquals(1990, bytes.size)
        assertEquals("3814c6bd8a3b22dd733ecdbdbce285b8481220906264abbeb201db60715372a6", sha(bytes))
        assertFalse(bytes.decodeToString().contains("blendContract"))
        val original = json.decodeFromString(CanvasDocument.serializer(), bytes.decodeToString())
        assertEquals(4, original.version)
        // EncodeDefault.NEVER must retain the complete historical signed representation.
        assertArrayEquals(bytes, CanvasDocumentCodec.encode(original))
        val migrated = CanvasDocumentCodec.decode(bytes.decodeToString())
        assertEquals(CanvasDocument.CURRENT_VERSION, migrated.version)
        assertEquals(original.layers, migrated.layers)
        assertTrue(migrated.layers.all { it.blendContract == LayerBlendContract.STANDARD })
        assertEquals("d4c364947957eb1d28c19bfd7f69b4e7d63106ea8235654cac56d535f0170374", pixelSha(render(migrated)))
        val saved = CanvasDocumentCodec.encode(migrated)
        assertFalse(saved.decodeToString().contains("blendContract"))
        assertArrayEquals(saved, CanvasDocumentCodec.encode(CanvasDocumentCodec.decode(saved.decodeToString())))
    }

    @Test
    fun aCorruptHistoricalChecksumIsRejectedBeforeAnyMigration() {
        val text = fixture().decodeToString().replace("\"blendMode\":\"DARKEN\"", "\"blendMode\":\"NORMAL\"")
        val error = assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.decode(text) }
        assertTrue(error.message.orEmpty().contains("checksum"))
    }

    @Test
    fun versionFiveRetainsNativeContractsAcrossRootLayersAndAnimationFrames() {
        val input = document().copy(layers = document().layers.map { native(it) })
        val second = input.layers.map { it.copy(id = it.id + 2) }
        val animated =
            input.copy(
                nextLayerId = 5,
                frames = listOf(AnimationFrame(1, "First", input.layers), AnimationFrame(2, "Second", second)),
            )
        val bytes = CanvasDocumentCodec.encode(animated)
        assertTrue(bytes.decodeToString().contains("\"blendContract\":\"PROCREATE_DARKEN_19\""))
        val reopened = CanvasDocumentCodec.decode(bytes.decodeToString())
        CanvasDocumentCodec.requireValidHeader(reopened)
        CanvasDocumentCodec.requireValidStructure(reopened)
        assertEquals(animated, reopened)
        assertEquals(CanvasDocument.CURRENT_VERSION, reopened.version)
        assertEquals("cdd509411a1ba3ceb86b652d1f91e4122ad9833a3bf0ad6e0db6bf5e2eb4b359", pixelSha(render(reopened)))
        assertArrayEquals(bytes, CanvasDocumentCodec.encode(reopened))
    }

    @Test
    fun everyOlderVersionKeepsStandardDarkenInBothStoredLayerLists() {
        for (version in 1..4) {
            val input = document().copy(version = version)
            val animated = input.copy(frames = listOf(AnimationFrame(1, "Historical", input.layers)))
            val output = CanvasDocumentCodec.decode(CanvasDocumentCodec.encode(animated).decodeToString())
            assertEquals(animated.copy(version = CanvasDocument.CURRENT_VERSION), output)
            assertTrue(output.layers.all { it.blendContract == LayerBlendContract.STANDARD })
            assertTrue(
                output.frames
                    .single()
                    .layers
                    .all { it.blendContract == LayerBlendContract.STANDARD },
            )
        }
    }

    @Test
    fun nativeContractCannotBeWrittenOrHiddenInsideAnOlderDocumentVersion() {
        val current = document().copy(layers = document().layers.map { native(it) })
        for (version in 1..4) {
            val invalid = current.copy(version = version)
            assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.encode(invalid) }
            val unsignedText = json.encodeToString(CanvasDocument.serializer(), invalid)
            assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.decode(unsignedText) }
        }
        val future = current.copy(version = CanvasDocument.CURRENT_VERSION + 1)
        assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.requireValidHeader(future) }
    }

    @Test
    fun onlyPixelDarkenLayersCanCarryTheNativeContract() {
        val layer = native(document().layers.last())
        for (invalid in listOf(layer.copy(blendMode = BlendMode.NORMAL), layer.copy(isGroup = true))) {
            val input = document().copy(layers = listOf(invalid))
            assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.encode(input) }
            assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.requireValidStructure(input) }
        }
    }

    @Test
    fun domainCopiesPreserveNativeContractUnlessTheirBlendModeChanges() {
        val layer = native(document().layers.last())
        assertEquals(LayerBlendContract.PROCREATE_DARKEN_19, layer.copy(name = "Copy").blendContract)
        assertEquals(LayerBlendContract.PROCREATE_DARKEN_19, layer.copyWith(name = "Copy", opacity = 0.5f).blendContract)
        assertEquals(LayerBlendContract.STANDARD, layer.copyWith(blendMode = BlendMode.MULTIPLY).blendContract)
    }

    private fun native(layer: Layer): Layer =
        if (layer.blendMode == BlendMode.DARKEN) layer.copy(blendContract = LayerBlendContract.PROCREATE_DARKEN_19) else layer

    private fun document(): CanvasDocument =
        json
            .decodeFromString(
                CanvasDocument.serializer(),
                fixture().decodeToString(),
            ).copy(version = CanvasDocument.CURRENT_VERSION, checksum = null)

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/documents/v4-standard-darken.json")).use {
            it.readBytes()
        }

    private fun render(document: CanvasDocument): PixelBuffer {
        val base = intArrayOf(0, 0xAA963CB4.toInt(), 0xFFD25A96.toInt(), 0x551ED25A)
        val inputs =
            document.layers.map { layer ->
                val pixels = IntArray(32 * 16) { if (layer.name == "Base") base[(it % 32) / 8] else 0x553CB45A }
                Compositor.LayerInput(layer, PixelBuffer(32, 16, pixels))
            }
        return Compositor().composite(inputs, 32, 16)
    }

    private fun pixelSha(pixels: PixelBuffer): String =
        sha(ByteBuffer.allocate(pixels.pixels.size * 4).apply { pixels.pixels.forEach(::putInt) }.array())

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(
                it.toInt() and 255,
            )
        }
}
