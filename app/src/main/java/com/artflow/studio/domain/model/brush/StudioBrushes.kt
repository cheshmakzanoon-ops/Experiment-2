package com.artflow.studio.domain.model.brush

/** Original ArtFlow starting points; these are not copies of another application's brush assets. */
object StudioBrushes {
    data class Preset(
        val id: String,
        val name: String,
        val category: String,
        val description: String,
        val parameters: BrushParams,
    )

    val presets: List<Preset> =
        listOf(
            Preset(
                "graphite-point",
                "Graphite point",
                "Sketch",
                "Fine paper grain with pressure-controlled weight.",
                BrushParams(
                    size = 6f,
                    opacity = 0.85f,
                    smoothing = 0.15f,
                    pressureToSize = 0.7f,
                    pressureToOpacity = 0.45f,
                    textureId = "paper",
                    blendTexture = true,
                ),
            ),
            Preset(
                "loose-pencil",
                "Loose pencil",
                "Sketch",
                "A broader, scattered mark for exploratory drawing.",
                BrushParams(
                    size = 14f,
                    opacity = 0.7f,
                    scatter = 0.12f,
                    smoothing = 0.1f,
                    sizeJitter = 0.15f,
                    textureId = "paper",
                    textureScale = 1.5f,
                    blendTexture = true,
                ),
            ),
            Preset(
                "fine-liner",
                "Fine liner",
                "Ink",
                "An even-width line with steady opacity.",
                BrushParams(
                    size = 4f,
                    spacing = 0.05f,
                    smoothing = 0.6f,
                    pressureToSize = 0f,
                    pressureToOpacity = 0f,
                ),
            ),
            Preset(
                "tapered-ink",
                "Tapered ink",
                "Ink",
                "Expressive pressure variation and tapered ends.",
                BrushParams(
                    size = 18f,
                    spacing = 0.06f,
                    smoothing = 0.65f,
                    pressureToSize = 0.95f,
                    pressureToOpacity = 0f,
                    taperStart = 0.2f,
                    taperEnd = 0.4f,
                ),
            ),
            Preset(
                "dry-charcoal",
                "Dry charcoal",
                "Texture",
                "Broken grain for rough shading and dark accents.",
                BrushParams(
                    size = 32f,
                    opacity = 0.9f,
                    scatter = 0.25f,
                    opacityJitter = 0.2f,
                    smoothing = 0.1f,
                    textureId = "charcoal",
                    blendTexture = true,
                ),
            ),
            Preset(
                "woven-paint",
                "Woven paint",
                "Texture",
                "Visible canvas weave beneath a broad paint mark.",
                BrushParams(
                    size = 36f,
                    flow = 0.65f,
                    textureId = "canvas",
                    textureScale = 1.2f,
                    textureRotation = 20f,
                    blendTexture = true,
                ),
            ),
            Preset(
                "round-glaze",
                "Round glaze",
                "Paint",
                "Low-opacity, low-flow colour for layered glazing.",
                BrushParams(
                    size = 48f,
                    opacity = 0.24f,
                    flow = 0.3f,
                    wetMix = 0.15f,
                    pressureToSize = 0.25f,
                    pressureToOpacity = 0.25f,
                ),
            ),
            Preset(
                "scattered-dots",
                "Scattered dots",
                "Texture",
                "Spaced dabs for stippling and granular accents.",
                BrushParams(
                    size = 5f,
                    spacing = 0.75f,
                    scatter = 1.2f,
                    count = 3,
                    sizeJitter = 0.5f,
                    smoothing = 0.05f,
                    pressureToSize = 0.2f,
                ),
            ),
        )

    val categories: List<String> = listOf("All") + presets.map { it.category }.distinct()

    fun search(
        query: String,
        category: String = "All",
    ): List<Preset> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return presets.filter { preset ->
            val description = "${preset.name} ${preset.category} ${preset.description}"
            (category == "All" || preset.category == category) && words.all { description.contains(it, ignoreCase = true) }
        }
    }
}
