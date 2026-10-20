package com.artflow.studio.data.local

import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * A damaged `.artflow` document must be refused before anything replaces the open artwork. Every
 * refusal is an IllegalArgumentException: kotlinx' SerializationException and each `require` in the
 * codec are one, so callers handle damage the same way whatever caused it.
 */
class CanvasDocumentCorruptionTest {
    private val valid: ByteArray = CanvasDocumentCodec.encode(CanvasDocumentFixtures.richDocument())

    /** Parses and validates a document the way ProjectStorage does, minus the file-system checks. */
    private fun open(bytes: ByteArray): CanvasDocument {
        val document = CanvasDocumentCodec.decode(bytes.decodeToString())
        CanvasDocumentCodec.requireValidHeader(document)
        CanvasDocumentCodec.requireValidStructure(document)
        return document
    }

    @Test
    fun everyTruncationOfAValidDocumentIsRejected() {
        var checked = 0
        for (length in 0 until valid.size) {
            val error =
                checkNotNull(runCatching { open(valid.copyOf(length)) }.exceptionOrNull()) {
                    "A document truncated to $length of ${valid.size} bytes was accepted"
                }
            if (error !is IllegalArgumentException) {
                fail("Truncation to $length bytes threw ${error::class.java.name}, not IllegalArgumentException: ${error.message}")
            }
            checked++
        }
        assertEquals(valid.size, checked)
    }

    /**
     * Each of 3,000 seeded single-bit flips is either refused with IllegalArgumentException, or loads a
     * document equal to the original. The second case happens when a key that was damaged is ignored
     * and its default value matches the original (every field is written, so many are defaults), or
     * when the checksum key is damaged. Such a load leaves the artwork unchanged. Any other load fails.
     */
    @Test
    fun seededBitFlipsNeverLoadAnAlteredDocument() {
        val original = open(valid)
        val random = Random(20260918)
        var refused = 0
        repeat(3_000) {
            val damaged = valid.copyOf()
            val index = random.nextInt(damaged.size)
            damaged[index] = (damaged[index].toInt() xor (1 shl random.nextInt(8))).toByte()
            val error = runCatching { open(damaged) }.exceptionOrNull()
            if (error == null) {
                assertEquals("flip of byte $index loaded a different document", original, open(damaged))
            } else if (error is IllegalArgumentException) {
                refused++
            } else {
                fail("Flip of byte $index threw ${error::class.java.name}: ${error.message}")
            }
        }
        assertTrue("no flip was refused", refused > 0)
    }

    @Test
    fun aTamperedChecksumIsRefused() {
        val text = valid.decodeToString()
        val checksum = requireNotNull(Regex("\"checksum\":\"([0-9a-f]{64})\"").find(text)).groupValues[1]
        val replacement = (if (checksum[0] == '0') "1" else "0") + checksum.substring(1)
        val tampered = text.replace(checksum, replacement)

        val error = runCatching { open(tampered.toByteArray()) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("checksum") == true)
    }

    @Test
    fun documentsWrittenBeforeTheChecksumStillLoad() {
        val text = valid.decodeToString()
        val withoutChecksum = text.replace(Regex(",\"checksum\":\"[0-9a-f]{64}\""), "")
        assertTrue(withoutChecksum != text)

        val document = open(withoutChecksum.toByteArray())

        assertEquals(CanvasDocumentFixtures.richDocument(), document)
    }

    private fun withEdit(edit: CanvasDocument.() -> CanvasDocument): ByteArray =
        CanvasDocumentCodec.encode(CanvasDocumentFixtures.richDocument().edit())

    /** A document whose only frame holds [layers]. */
    private fun withLayers(layers: List<Layer>): ByteArray = withEdit { copy(layers = layers, frames = emptyList()) }

    private fun layer(id: Long) = Layer(id = id, name = "L$id", index = id.toInt())

    private fun frame(id: Int) = AnimationFrame(id = id.toLong(), name = "F$id", layers = listOf(layer(id.toLong())))

    @Test
    fun eachDocumentedValidationFailureIsRejectedWithItsMessage() {
        val layers = CanvasDocumentFixtures.richDocument().layers
        val cases =
            listOf(
                "Unsupported project version" to withEdit { copy(version = 3) },
                "Unsupported project version" to withEdit { copy(version = 0) },
                "Invalid project dimensions" to withEdit { copy(width = 0) },
                "Invalid project dimensions" to withEdit { copy(width = 8192, height = 8192) },
                "Invalid project DPI" to withEdit { copy(dpi = 5) },
                "Invalid layer identifier" to withLayers(layers + layer(0L)),
                "Duplicate layer identifiers" to withLayers(layers + layers.first()),
                "Duplicate frame identifiers" to withEdit { copy(frames = frames + frames.first()) },
                "A frame must contain a layer" to withLayers(emptyList()),
                "Invalid animation frame duration" to withEdit { copy(frames = frames.map { it.copy(durationMs = 5) }) },
                "Invalid layer opacity" to withLayers(layers.map { it.copy(opacity = 1.5f) }),
                "Invalid layer opacity" to withLayers(layers.map { it.copy(opacity = -0.1f) }),
                "Project has too many layers" to withLayers((1..1025).map { layer(it.toLong()) }),
                "Project has too many animation frames" to withEdit { copy(frames = (1..241).map { frame(it) }) },
            )
        for ((message, bytes) in cases) {
            val error = runCatching { open(bytes) }.exceptionOrNull()
            assertTrue("expected a refusal containing '$message'", error is IllegalArgumentException)
            assertTrue("'${error?.message}' should mention '$message'", error?.message?.contains(message) == true)
        }
    }

    @Test
    fun aNaNOpacityIsRefusedBeforeItReachesTheLayer() {
        val text = CanvasDocumentCodec.encode(CanvasDocumentFixtures.richDocument()).decodeToString()
        val damaged = text.replaceFirst("\"opacity\":${0.123456789f}", "\"opacity\":NaN")
        assertTrue(damaged != text)

        val error = runCatching { open(damaged.toByteArray()) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
