package com.artflow.studio.domain.repository.canvas

/** Immutable metadata for one committed document and its matching flattened image/thumbnail. */
data class SavedCanvas(
    val projectId: Long,
    /** In-memory revision captured with the document, not the possibly newer editable state. */
    val revision: Long,
    val thumbnailPath: String,
    val size: CanvasSize,
    val activeFrameIndex: Int,
    val layerCount: Int,
    /** The exact savedAt value stored in the committed document. */
    val savedAt: Long,
)
