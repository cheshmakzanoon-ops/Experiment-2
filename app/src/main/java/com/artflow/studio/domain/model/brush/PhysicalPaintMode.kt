package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable

/**
 * Physical traditional-media simulation mode for a brush.
 * Standard digital brushes use [NONE] and allocate zero physical simulation memory.
 */
@Serializable
enum class PhysicalPaintMode(
    val displayName: String,
) {
    NONE("Digital"),
    WATERCOLOR("Watercolor"),
    GOUACHE("Gouache"),
    IMPASTO_OIL("Impasto Oil"),
}
