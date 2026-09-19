package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable

/** A complete brush snapshot; its stable identity is independent of its editable display name. */
@Serializable
data class SavedBrush(
    val id: String,
    val name: String,
    val parameters: BrushParams,
)
