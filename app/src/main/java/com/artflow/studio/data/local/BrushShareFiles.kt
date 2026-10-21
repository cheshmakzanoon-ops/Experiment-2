package com.artflow.studio.data.local

import com.artflow.studio.core.export.ProcreateBrushWriter
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.SavedBrush

/** The file the artist explicitly selects in the brush or set Share dialog. */
enum class BrushShareFormat(
    val extension: String,
) {
    ARTFLOW(BrushFiles.EXTENSION),
    PROCREATE_BRUSH("brush"),
    PROCREATE_SET("brushset"),
}

/** Encoding shared by the single-brush and set controls, independent of Android's share chooser. */
object BrushShareFiles {
    private const val MAX_PROCREATE_BRUSHES = 100
    private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024

    class Encoded(
        val fileName: String,
        val bytes: ByteArray,
    )

    fun encode(
        name: String,
        brushes: List<SavedBrush>,
        format: BrushShareFormat = BrushShareFormat.ARTFLOW,
    ): Encoded {
        require(brushes.isNotEmpty()) { "Choose at least one brush to share" }
        val bytes =
            when (format) {
                BrushShareFormat.ARTFLOW -> BrushFiles.encode(brushes).toByteArray(Charsets.UTF_8)
                BrushShareFormat.PROCREATE_BRUSH -> {
                    require(brushes.size == 1) { "A .brush file contains one brush" }
                    BrushArchives.procreateBrush(brushes.single())
                }

                BrushShareFormat.PROCREATE_SET -> {
                    require(brushes.size <= MAX_PROCREATE_BRUSHES) { "Share at most 100 brushes in one Procreate set" }
                    ProcreateBrushWriter.brushset(
                        name,
                        brushes.map { brush ->
                            ProcreateBrushWriter.Brush(
                                brush.name,
                                brush.parameters,
                                CustomGrains.get(brush.parameters.shapeId),
                                CustomGrains.get(brush.parameters.textureId),
                            )
                        },
                    )
                }
            }
        val limit = if (format == BrushShareFormat.ARTFLOW) BrushFiles.MAX_BYTES else MAX_ARCHIVE_BYTES
        require(bytes.size <= limit) { "The shared brush file is too large to import" }
        return Encoded(BrushFiles.fileName(name, format.extension), bytes)
    }
}
