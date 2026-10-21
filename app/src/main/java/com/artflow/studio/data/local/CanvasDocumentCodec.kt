package com.artflow.studio.data.local

import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.animation.TimelapseRecording
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The on-disk representation of a project.
 *
 * Version history:
 * - **v1** stored a single layer stack with no pixel data (strokes only).
 * - **v2** adds per-layer pixel files ([Layer.rasterFile]), layer masks, adjustment/filter layer
 *   parameters, animation frames and the timelapse recording. v1 documents still load: their
 *   single layer list becomes frame 0.
 *
 * Kept in the data layer because it is purely a storage concern.
 */
@Serializable
data class CanvasDocument(
    val version: Int = CURRENT_VERSION,
    val width: Int,
    val height: Int,
    val dpi: Int,
    /** A [com.artflow.studio.core.color.ColorProfile] name; documents before profiles are sRGB. */
    val colorProfile: String = "SRGB",
    /** Time spent changing the artwork, in milliseconds. */
    val trackedMs: Long = 0L,
    val backgroundColor: Int,
    val activeLayerId: Long,
    val nextLayerId: Long,
    /** Layer stack of the first frame (v1 compatibility and the non-animated case). */
    val layers: List<Layer>,
    val frames: List<AnimationFrame> = emptyList(),
    val activeFrameIndex: Int = 0,
    val animation: AnimationSettings = AnimationSettings(),
    val timelapse: TimelapseRecording = TimelapseRecording(),
    val savedAt: Long = System.currentTimeMillis(),
    /**
     * SHA-256 (lowercase hex) of the encoded document with this field null. [CanvasDocumentCodec.encode]
     * writes it and [CanvasDocumentCodec.decode] verifies it, so a damaged field cannot load silently.
     * Null in documents written before the checksum existed; decoded documents always have it null.
     */
    val checksum: String? = null,
) {
    /**
     * Frames in a canonical form. Documents written before animation existed have no [frames], so
     * their [layers] become frame 0 — this is what makes old projects load without a migration.
     */
    fun resolvedFrames(): List<AnimationFrame> {
        if (frames.isNotEmpty()) return frames
        return listOf(
            AnimationFrame(
                id = 1L,
                name = "Frame 1",
                layers = layers,
                durationMs = animation.frameDurationMs,
            ),
        )
    }

    fun resolvedActiveFrameIndex(): Int = activeFrameIndex.coerceIn(0, (resolvedFrames().size - 1).coerceAtLeast(0))

    companion object {
        /** Bump when the JSON shape changes in a way that needs migration code. */
        const val CURRENT_VERSION = 2
    }
}

/**
 * Android-free encoding and validation of `canvas.artflow` documents.
 *
 * [ProjectStorage] adds the file-size limit and the project-path checks around these calls. Parsing
 * ignores unknown fields (a newer app's additions are dropped on load, never rejected), and every
 * rejection is an [IllegalArgumentException]: `require` failures and kotlinx' `SerializationException`
 * both are, so callers can treat any bad document the same way.
 */
object CanvasDocumentCodec {
    /** Animation frames a document may hold. */
    const val MAX_FRAMES = 240

    /** Layers across all frames. */
    const val MAX_LAYERS = 1024

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = false
        }

    /** Encodes [document] with a fresh [CanvasDocument.checksum]. */
    fun encode(document: CanvasDocument): ByteArray {
        val unsigned = document.copy(checksum = null)
        val signed = unsigned.copy(checksum = checksumOf(unsigned))
        return json.encodeToString(CanvasDocument.serializer(), signed).toByteArray()
    }

    /**
     * Parses and verifies the checksum, returning the document with [CanvasDocument.checksum] cleared.
     * Documents without a checksum (written by earlier builds) are accepted unverified. Call
     * [requireValidHeader] and [requireValidStructure] on the result.
     */
    fun decode(text: String): CanvasDocument {
        val document = json.decodeFromString(CanvasDocument.serializer(), text)
        val stored = document.checksum ?: return document
        val unsigned = document.copy(checksum = null)
        require(checksumOf(unsigned) == stored) { "The project data does not match its checksum" }
        return unsigned
    }

    private fun checksumOf(document: CanvasDocument): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(json.encodeToString(CanvasDocument.serializer(), document).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Version, canvas size and DPI. These are the only checks a packaged manifest receives before import. */
    fun requireValidHeader(document: CanvasDocument) {
        require(document.version in 1..CanvasDocument.CURRENT_VERSION) { "Unsupported project version ${document.version}" }
        require(CanvasOperations.isSizeSafe(document.width, document.height)) { "Invalid project dimensions" }
        require(document.dpi in CanvasOperations.MIN_DPI..CanvasOperations.MAX_DPI) { "Invalid project DPI" }
    }

    /** Frames, layer identifiers, durations and opacity. Pixel-file paths are checked by the storage layer. */
    fun requireValidStructure(document: CanvasDocument) {
        val frames = document.resolvedFrames()
        require(frames.size in 1..MAX_FRAMES) { "Project has too many animation frames" }
        require(frames.map { it.id }.distinct().size == frames.size) { "Duplicate frame identifiers" }
        val layers = frames.flatMap { it.layers }
        require(layers.size <= MAX_LAYERS) { "Project has too many layers" }
        require(layers.all { it.id > 0 && it.id < Long.MAX_VALUE }) { "Invalid layer identifier" }
        require(layers.map { it.id }.distinct().size == layers.size) { "Duplicate layer identifiers" }
        require(frames.all { it.layers.isNotEmpty() }) { "A frame must contain a layer" }
        require(frames.all { it.durationMs in AnimationFrame.MIN_DURATION_MS..AnimationFrame.MAX_DURATION_MS }) {
            "Invalid animation frame duration"
        }
        val frameIds = frames.map { it.id }.toSet()
        val animation = document.animation
        require((animation.backgroundFrameIds + animation.foregroundFrameIds + animation.hiddenFrameIds).all { it in frameIds }) {
            "Animation settings refer to missing frames"
        }
        require(animation.backgroundFrameIds.intersect(animation.foregroundFrameIds).isEmpty()) {
            "An animation frame cannot be both background and foreground"
        }
        layers.forEach { layer ->
            require(layer.opacity.isFinite() && layer.opacity in 0f..1f) { "Invalid layer opacity" }
        }
    }
}
