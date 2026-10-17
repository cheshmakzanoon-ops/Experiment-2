package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable

/**
 * How overlapping dabs inside one stroke combine. Glazing takes the strongest dab at each pixel,
 * so a stroke never gets denser than its dabs; blending layers the dabs like paint, so a stroke
 * builds up where it overlaps. [strength] scales every dab's coverage before that combination.
 *
 * These curves are this engine's own definitions of the names Procreate uses; they have not been
 * matched against Procreate's output. Uniform is the default for each family and reproduces the
 * previous behaviour exactly: glaze for brushes without build-up, and blending for build-up brushes.
 */
@Serializable
enum class RenderingMode(
    /** True when overlapping dabs add up (blending); false when the strongest dab wins (glazing). */
    val blends: Boolean,
    /** Multiplier on each dab's coverage. */
    val strength: Float,
) {
    /** Glaze, half-strength dabs: a light, transparent wash that never builds up. */
    GLAZE_LIGHT(blends = false, strength = 0.5f),

    /** Glaze, the default: each pixel keeps the strength of the strongest dab over it. */
    GLAZE_UNIFORM(blends = false, strength = 1f),

    /** Glaze, stronger dabs: still no build-up within a stroke, but a denser mark. */
    GLAZE_INTENSE(blends = false, strength = 1.5f),

    /** Glaze, full-strength dabs at double weight: the densest single-layer mark. */
    GLAZE_HEAVY(blends = false, strength = 2f),

    /** Blending, the default for build-up brushes: each overlapping dab layers over the last. */
    BLEND_UNIFORM(blends = true, strength = 1f),

    /** Blending with stronger dabs, so the stroke builds up faster where it overlaps. */
    BLEND_INTENSE(blends = true, strength = 1.5f),

    /** Blending with double-weight dabs: the stroke builds up to full density in a few overlaps. */
    BLEND_HEAVY(blends = true, strength = 2f),
}
