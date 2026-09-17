package com.artflow.studio.core.export

import com.artflow.studio.core.canvas.CanvasOperations
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Export formats supported by the app (Phases 38-44).
 */
enum class ExportFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    /** Formats that only make sense when the project has more than one frame. */
    val requiresAnimation: Boolean = false,
) {
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    WEBP("WebP", "webp", "image/webp"),
    PDF("PDF", "pdf", "application/pdf"),
    PSD("Photoshop (PSD)", "psd", "image/vnd.adobe.photoshop"),
    GIF("Animated GIF", "gif", "image/gif", requiresAnimation = true),
    MP4("MP4 video", "mp4", "video/mp4", requiresAnimation = true),
    FRAME_SEQUENCE("PNG frames (zip)", "zip", "application/zip", requiresAnimation = true),
    ;

    /** MediaStore accepts these image/video types; PSD/PDF/ZIP use the document picker. */
    val supportsGallery: Boolean get() = this in setOf(PNG, JPEG, WEBP, GIF, MP4)

    companion object {
        /** Formats offered for a still image. */
        fun stillFormats(): List<ExportFormat> = listOf(PNG, JPEG, WEBP, PDF, PSD)

        /** Formats offered for an animation. */
        fun animationFormats(): List<ExportFormat> = listOf(GIF, MP4, FRAME_SEQUENCE)

        fun byName(name: String): ExportFormat? = entries.firstOrNull { it.name == name }
    }
}

/** How much of the canvas an export covers. */
enum class ExportArea(
    val displayName: String,
) {
    FULL_CANVAS("Full canvas"),
    CURRENT_FRAME("Current frame"),
    ALL_FRAMES("All frames"),
    SELECTION("Selection"),
    CONTENT_BOUNDS("Trim to content"),
}

/**
 * Everything the export pipeline needs. Immutable so a queued export can never be mutated by later
 * UI interaction.
 */
data class ExportOptions(
    val format: ExportFormat = ExportFormat.PNG,
    val area: ExportArea = ExportArea.FULL_CANVAS,
    /** Output scale, `0.1..4`. `1` keeps the canvas pixel size. */
    val scale: Float = 1f,
    /** Target width in pixels; when set, overrides [scale]. */
    val targetWidth: Int? = null,
    /** JPEG/WebP quality, `1..100`. */
    val quality: Int = 92,
    /** Render hidden layers too. */
    val includeHiddenLayers: Boolean = false,
    /** Composite the canvas background colour instead of leaving transparency. */
    val flattenOntoBackground: Boolean = false,
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
    /** DPI recorded in PNG/JPEG/PDF metadata. */
    val dpi: Int = 72,
    /** Fill / fit / stretch when the export size differs from the canvas aspect ratio. */
    val fitMode: FitMode = FitMode.STRETCH,
    val fileName: String? = null,
    /** Animation options; ignored by still formats. */
    val gifLoop: Boolean = true,
    val animationFpsOverride: Int? = null,
    val keepGifTransparency: Boolean = true,
    val videoBitrate: Int = 8_000_000,
    /** PDF page setup. */
    val pdfPageSize: PdfPageSize = PdfPageSize.FIT_CANVAS,
    /** Rasterise at this multiple of the canvas resolution when writing the PDF page image. */
    val pdfOversample: Float = 1f,
    /** PSD options. */
    val psdUseRle: Boolean = true,
    val psdGroupLayers: Boolean = true,
)

/** How an export is fitted into the requested output size. */
enum class FitMode(
    val displayName: String,
) {
    STRETCH("Stretch"),
    FIT("Fit (letterbox)"),
    FILL("Fill (crop)"),
}

/** Common PDF page sizes, in PostScript points (1/72 inch). */
enum class PdfPageSize(
    val displayName: String,
    val widthPt: Float,
    val heightPt: Float,
) {
    FIT_CANVAS("Fit to canvas", 0f, 0f),
    A4_PORTRAIT("A4 portrait", 595f, 842f),
    A4_LANDSCAPE("A4 landscape", 842f, 595f),
    A3_PORTRAIT("A3 portrait", 842f, 1191f),
    A3_LANDSCAPE("A3 landscape", 1191f, 842f),
    US_LETTER("US Letter", 612f, 792f),
    US_LEGAL("US Legal", 612f, 1008f),
    SQUARE_2000("Square 2000pt", 2000f, 2000f),
}

/** Quick export presets shown in the export sheet. */
data class ExportPreset(
    val name: String,
    val description: String,
    val options: ExportOptions,
)

object ExportPresets {
    val ALL: List<ExportPreset> =
        listOf(
            ExportPreset(
                "Share PNG",
                "Full resolution, transparency preserved",
                ExportOptions(format = ExportFormat.PNG, scale = 1f, flattenOntoBackground = false),
            ),
            ExportPreset(
                "Web PNG",
                "Half size for quick sharing",
                ExportOptions(format = ExportFormat.PNG, scale = 0.5f),
            ),
            ExportPreset(
                "Social JPEG",
                "Half size, quality 90, white background",
                ExportOptions(
                    format = ExportFormat.JPEG,
                    scale = 0.5f,
                    quality = 90,
                    flattenOntoBackground = true,
                ),
            ),
            ExportPreset(
                "Print PDF",
                "A4 at 300 DPI with a high-resolution raster image",
                ExportOptions(
                    format = ExportFormat.PDF,
                    pdfPageSize = PdfPageSize.A4_PORTRAIT,
                    dpi = 300,
                    pdfOversample = 2f,
                ),
            ),
            ExportPreset(
                "Photoshop PSD",
                "Every layer as an editable PSD layer",
                ExportOptions(format = ExportFormat.PSD, includeHiddenLayers = false, psdUseRle = true),
            ),
            ExportPreset(
                "Animated GIF",
                "Loop forever at the timeline FPS",
                ExportOptions(format = ExportFormat.GIF, gifLoop = true, keepGifTransparency = true),
            ),
            ExportPreset(
                "Video MP4",
                "H.264 at the selected output size, 8 Mbps",
                ExportOptions(format = ExportFormat.MP4, videoBitrate = 8_000_000),
            ),
            ExportPreset(
                "Thumbnail",
                "512px preview image",
                ExportOptions(format = ExportFormat.PNG, targetWidth = 512),
            ),
        )
}

/** Result of an export, ready to be shown to the user or handed to a share intent. */
data class ExportResult(
    val format: ExportFormat,
    val filePath: String,
    val fileName: String,
    val byteCount: Long,
    val width: Int,
    val height: Int,
    val frameCount: Int = 1,
    /** Set when the export was published to the device gallery. */
    val mediaStoreUri: String? = null,
    val warning: String? = null,
) {
    val sizeLabel: String
        get() =
            when {
                byteCount < 1024 -> "$byteCount B"
                byteCount < 1024 * 1024 -> "%.1f KB".format(byteCount / 1024f)
                else -> "%.1f MB".format(byteCount / (1024f * 1024f))
            }
}

/** Failures the export pipeline can report; all user-readable. */
sealed class ExportError(
    val message: String,
) {
    class TooLarge(
        width: Int,
        height: Int,
    ) : ExportError("Export would be $width×${height}px, which exceeds the safe canvas limit")

    object NoFrames : ExportError("This project has no frames to export")

    object NoContent : ExportError("Nothing to export: the canvas is empty")

    class UnsupportedFormat(
        format: ExportFormat,
    ) : ExportError("${format.displayName} export is not available on this device")

    class EncodingFailed(
        cause: String,
    ) : ExportError("Encoding failed: $cause")

    class StorageFailed(
        cause: String,
    ) : ExportError("Could not write the file: $cause")

    object AnimationRequired : ExportError("This format needs an animation; add frames first")
}

/** Naming and sizing helpers shared by every exporter. */
object ExportNaming {
    /** `ProjectName-20260916-101500.png`, sanitised for the file system. */
    fun defaultFileName(
        projectName: String,
        format: ExportFormat,
        timestamp: Long = System.currentTimeMillis(),
    ): String {
        val safe = sanitize(projectName).ifEmpty { "ArtFlow" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(timestamp))
        return "$safe-$stamp.${format.extension}"
    }

    fun fileName(
        projectName: String,
        options: ExportOptions,
    ): String {
        val requested =
            options.fileName?.trim()?.takeIf { it.isNotEmpty() }
                ?: return defaultFileName(projectName, options.format)
        require(requested.length <= 160 && requested.none { it == '/' || it == '\\' || it.code < 32 }) {
            "Use a filename without folders and with at most 160 characters"
        }
        require(requested !in setOf(".", "..")) { "Invalid export filename" }
        val suffix = ".${options.format.extension}"
        return if (requested.endsWith(suffix, ignoreCase = true)) requested else requested + suffix
    }

    fun sanitize(name: String): String =
        name
            .trim()
            .replace(Regex("[^A-Za-z0-9-_ ]"), "")
            .replace(' ', '-')
            .take(48)

    /**
     * Output size for the requested options. Never returns a zero or unsafe dimension; the caller
     * checks [CanvasOperations.isSizeSafe] for the hard limit warning.
     */
    fun resolveSize(
        canvasWidth: Int,
        canvasHeight: Int,
        options: ExportOptions,
    ): Pair<Int, Int> {
        val targetWidth = options.targetWidth
        val base =
            if (targetWidth != null) {
                CanvasOperations.scaleToWidth(canvasWidth, canvasHeight, targetWidth)
            } else {
                val scale = options.scale.coerceIn(0.1f, 4f)
                max(1, (canvasWidth * scale).roundToInt()) to max(1, (canvasHeight * scale).roundToInt())
            }
        return base
    }

    /** Applies the fit mode, producing the final buffer size and any letterbox offsets. */
    fun applyFit(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        fitMode: FitMode,
    ): FitPlacement {
        if (fitMode == FitMode.STRETCH) {
            return FitPlacement(targetWidth, targetHeight, 0, 0, targetWidth, targetHeight)
        }
        val scaleX = targetWidth / sourceWidth.toFloat()
        val scaleY = targetHeight / sourceHeight.toFloat()
        val scale = if (fitMode == FitMode.FIT) minOf(scaleX, scaleY) else maxOf(scaleX, scaleY)
        val drawWidth = max(1, (sourceWidth * scale).roundToInt())
        val drawHeight = max(1, (sourceHeight * scale).roundToInt())
        val offsetX = (targetWidth - drawWidth) / 2
        val offsetY = (targetHeight - drawHeight) / 2
        return FitPlacement(targetWidth, targetHeight, offsetX, offsetY, drawWidth, drawHeight)
    }

    /** Where the artwork lands inside the output canvas. */
    data class FitPlacement(
        val outputWidth: Int,
        val outputHeight: Int,
        val offsetX: Int,
        val offsetY: Int,
        val drawWidth: Int,
        val drawHeight: Int,
    )
}
