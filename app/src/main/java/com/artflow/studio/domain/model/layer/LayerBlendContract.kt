package com.artflow.studio.domain.model.layer

import kotlinx.serialization.Serializable

/** A persisted layer rendering contract; ordinary ArtFlow blend modes keep [STANDARD]. */
@Serializable
enum class LayerBlendContract {
    STANDARD,

    /** Native archive mode19 takes a minimum of premultiplied source-over and destination RGBA. */
    PROCREATE_DARKEN_19,
}
