package com.artflow.studio.domain.model.brush

import com.artflow.studio.domain.model.layer.BlendMode

/**
 * The third part of ArtFlow's original brush library. Each family helper below describes one kind of
 * mark (a lead, a liner, a bristle, a wash...) and every preset is an original variation of it built
 * from ArtFlow's own engine settings.
 */
internal object StudioBrushesExtra {
    private fun p(
        id: String,
        name: String,
        category: String,
        description: String,
        params: BrushParams,
    ) = StudioBrushes.Preset(id, name, category, description, params)

    private fun lead(
        size: Float,
        opacity: Float,
        texture: String,
        scale: Float = 1f,
        toSize: Float = 0.4f,
    ) = BrushParams(
        size = size,
        opacity = opacity,
        spacing = 0.06f,
        pressureToSize = toSize,
        pressureToOpacity = 0.75f,
        textureId = texture,
        textureScale = scale,
        blendTexture = true,
        smoothing = 0.2f,
    )

    private fun liner(
        size: Float,
        taper: Float,
        toSize: Float,
        roundness: Float = 1f,
        rotation: Float = 0f,
    ) = BrushParams(
        size = size,
        opacity = 1f,
        spacing = 0.04f,
        taperStart = taper,
        taperEnd = taper,
        pressureToSize = toSize,
        pressureToOpacity = 0.1f,
        smoothing = 0.6f,
        roundness = roundness,
        rotation = rotation,
    )

    private fun bristle(
        size: Float,
        opacity: Float,
        wet: Float,
        texture: String = "bristle",
        roundness: Float = 0.6f,
    ) = BrushParams(
        size = size,
        opacity = opacity,
        spacing = 0.05f,
        pressureToSize = 0.35f,
        pressureToOpacity = 0.5f,
        wetMix = wet,
        flow = 0.8f,
        textureId = texture,
        blendTexture = true,
        roundness = roundness,
        tiltToRotation = true,
    )

    private fun wash(
        size: Float,
        opacity: Float,
        edges: Float,
        texture: String = "blotch",
        scale: Float = 1f,
    ) = BrushParams(
        size = size,
        opacity = opacity,
        spacing = 0.08f,
        pressureToSize = 0.25f,
        pressureToOpacity = 0.6f,
        wetMix = 0.5f,
        flow = 0.45f,
        textureId = texture,
        textureScale = scale,
        wetEdges = edges,
        blendMode = BlendMode.MULTIPLY,
    )

    private fun air(
        size: Float,
        opacity: Float,
        flow: Float,
        mode: BlendMode = BlendMode.NORMAL,
    ) = BrushParams(
        size = size,
        opacity = opacity,
        spacing = 0.05f,
        pressureToSize = 0.1f,
        pressureToOpacity = 0.9f,
        flow = flow,
        falloff = 0f,
        blendMode = mode,
        buildUp = true,
    )

    private fun scatter(
        size: Float,
        scatter: Float,
        count: Int,
        jitter: Float,
        texture: String? = null,
        mode: BlendMode = BlendMode.NORMAL,
    ) = BrushParams(
        size = size,
        opacity = 0.9f,
        spacing = 0.3f,
        scatter = scatter,
        count = count,
        sizeJitter = jitter,
        opacityJitter = jitter * 0.6f,
        pressureToSize = 0.3f,
        textureId = texture,
        blendTexture = texture != null,
        blendMode = mode,
        tipRandomized = true,
    )

    private fun grain(
        size: Float,
        opacity: Float,
        texture: String,
        scale: Float,
        roundness: Float = 1f,
    ) = BrushParams(
        size = size,
        opacity = opacity,
        spacing = 0.07f,
        pressureToSize = 0.2f,
        pressureToOpacity = 0.6f,
        textureId = texture,
        textureScale = scale,
        blendTexture = true,
        roundness = roundness,
        grainMoving = false,
    )

    private fun light(
        size: Float,
        opacity: Float,
        hue: Float,
        mode: BlendMode = BlendMode.SCREEN,
    ) = BrushParams(
        size = size,
        opacity = opacity,
        spacing = 0.06f,
        pressureToSize = 0.5f,
        pressureToOpacity = 0.4f,
        hueJitter = hue,
        taperStart = 0.2f,
        taperEnd = 0.4f,
        taperOpacity = 0.6f,
        blendMode = mode,
    )

    private const val SKETCH = "Sketching"
    private const val DRAW = "Drawing"
    private const val PAINT = "Painting"
    private const val ART = "Artistic"
    private const val CALLI = "Calligraphy"
    private const val AIR = "Airbrushing"
    private const val TEXTURE = "Textures"
    private const val CHARCOAL = "Charcoals"
    private const val SPRAY = "Spraypaints"
    private const val ELEMENTS = "Elements"
    private const val TOUCH = "Touch-ups"
    private const val RETRO = "Retro"
    private const val GLOW = "Luminance"
    private const val INDUSTRY = "Industrial"
    private const val ORGANIC = "Organic"
    private const val WATER = "Water"
    private const val ABSTRACT = "Abstract"

    val presets: List<StudioBrushes.Preset> =
        listOf(
            // Sketching
            p("2h-lead", "2H lead", SKETCH, "Pale, hard lead for faint guide lines.", lead(2f, 0.35f, "paper", 0.5f)),
            p("4b-lead", "4B lead", SKETCH, "Rich, soft lead with a visible paper tooth.", lead(8f, 0.85f, "paper", 1.6f)),
            p("mech-pencil", "Mechanical pencil", SKETCH, "An even, unvarying line for tidy sketches.", lead(3f, 0.7f, "fine", 0.7f, 0f)),
            p("carpenter", "Carpenter pencil", SKETCH, "A flat lead that swells with pressure.", lead(14f, 0.75f, "paper", 1.2f, 0.8f)),
            p("sketch-blue", "Sketch blue", SKETCH, "Light, grainy construction strokes.", lead(6f, 0.3f, "fine", 1.4f)),
            p("graphite-stick", "Graphite stick", SKETCH, "Broad graphite for quick tonal blocking.", lead(24f, 0.6f, "charcoal", 1.1f)),
            p("tooth-pencil", "Tooth pencil", SKETCH, "Catches only the peaks of a rough paper.", lead(10f, 0.55f, "canvas", 0.9f)),
            p("thumbnail", "Thumbnail", SKETCH, "Tiny, crisp marks for small composition studies.", lead(1.5f, 0.9f, "fine", 0.4f, 0.2f)),
            // Drawing
            p("gel-pen", "Gel pen", DRAW, "Smooth, glossy line with a slight pressure swell.", liner(4f, 0.05f, 0.25f)),
            p("fountain", "Fountain pen", DRAW, "A nib that thickens on the downstroke.", liner(6f, 0.2f, 0.7f, 0.5f, 30f)),
            p("map-pen", "Map pen", DRAW, "Very fine, springy line for detail work.", liner(1.5f, 0.3f, 0.9f)),
            p("rollerball", "Rollerball", DRAW, "Steady, even ink with clean ends.", liner(3f, 0f, 0.1f)),
            p("quill", "Quill", DRAW, "Sharp tapers at both ends of every stroke.", liner(7f, 0.6f, 0.85f)),
            p("bamboo-pen", "Bamboo pen", DRAW, "A blunt, slightly flattened reed line.", liner(9f, 0.25f, 0.5f, 0.7f, 15f)),
            p("felt-tip", "Felt tip", DRAW, "Round, soft-edged marker line.", liner(10f, 0f, 0.05f)),
            p("needle-liner", "Needle liner", DRAW, "The thinnest line the engine draws.", liner(1f, 0.1f, 0.4f)),
            // Painting
            p("filbert", "Filbert", PAINT, "An oval brush that blends edges as it goes.", bristle(28f, 0.85f, 0.35f, roundness = 0.75f)),
            p("bright", "Bright", PAINT, "Short, stiff flat bristles for crisp edges.", bristle(32f, 0.95f, 0.15f, roundness = 0.3f)),
            p("rigger", "Rigger", PAINT, "Long, thin strokes for branches and rigging.", bristle(5f, 1f, 0.1f, "fine", 0.9f)),
            p("fan-brush", "Fan brush", PAINT, "Splayed bristles for foliage and texture.", bristle(40f, 0.7f, 0.25f, "hatch", 0.2f)),
            p("mop", "Mop", PAINT, "A big, soft, loaded brush for washes of colour.", bristle(48f, 0.6f, 0.6f, "blotch", 0.9f)),
            p("sable-round", "Sable round", PAINT, "A fine point that blooms into a full body.", bristle(18f, 0.9f, 0.4f, "fine", 1f)),
            p("oil-wet", "Wet oil", PAINT, "Picks up and drags the colour underneath.", bristle(36f, 0.95f, 0.8f, "canvas", 0.65f)),
            p("tempera", "Tempera", PAINT, "Fast-drying, matte, layered strokes.", bristle(16f, 0.65f, 0.05f, "paper", 0.55f)),
            p("scumble", "Scumble", PAINT, "Broken, dry colour scrubbed over the surface.", bristle(44f, 0.5f, 0.2f, "charcoal", 0.5f)),
            // Artistic
            p("sgraffito", "Sgraffito", ART, "Scratched-through lines into thick colour.", grain(6f, 1f, "hatch", 0.5f, 0.2f)),
            p("encaustic", "Encaustic", ART, "Waxy, translucent layers with soft edges.", grain(30f, 0.55f, "blotch", 1.6f)),
            p("conte", "Conté", ART, "A square stick with a dense, chalky line.", grain(12f, 0.85f, "paper", 1.3f, 0.4f)),
            p("pan-pastel", "Pan pastel", ART, "Soft, powdery colour for smooth gradients.", grain(46f, 0.4f, "fine", 2f)),
            p("sumi", "Sumi", ART, "Expressive ink-wash strokes with dry breaks.", bristle(22f, 0.9f, 0.3f, "bristle", 1f)),
            // Calligraphy
            p("italic-nib", "Italic nib", CALLI, "A flat nib held at a steady angle.", liner(12f, 0.1f, 0.3f, 0.15f, 40f)),
            p("pointed-nib", "Pointed nib", CALLI, "Hairlines that swell under pressure.", liner(9f, 0.4f, 1f, 1f)),
            p("parallel-pen", "Parallel pen", CALLI, "A wide, crisp edge for bold letters.", liner(20f, 0f, 0.15f, 0.1f, 35f)),
            p("brush-letter", "Brush lettering", CALLI, "Thin upstrokes, heavy downstrokes.", liner(16f, 0.5f, 0.95f, 0.8f)),
            p("monoline", "Monoline", CALLI, "A constant-width script line.", liner(8f, 0f, 0f)),
            p("gothic", "Blackletter", CALLI, "A broad edge for angular, heavy letters.", liner(24f, 0.05f, 0.2f, 0.08f, 45f)),
            // Airbrushing
            p("soft-fade", "Soft fade", AIR, "A very gentle, wide build-up of colour.", air(48f, 0.2f, 0.3f)),
            p("detail-air", "Detail air", AIR, "A small nozzle for fine soft shading.", air(8f, 0.5f, 0.6f)),
            p("shade-air", "Shade air", AIR, "Multiplies soft shadow over the layer.", air(36f, 0.35f, 0.4f, BlendMode.MULTIPLY)),
            p("light-air", "Light air", AIR, "Screens soft light over the layer.", air(36f, 0.4f, 0.4f, BlendMode.SCREEN)),
            p("tint-air", "Tint air", AIR, "A soft colour wash that keeps the tones beneath.", air(42f, 0.3f, 0.5f, BlendMode.SOFT_LIGHT)),
            p("solid-air", "Solid air", AIR, "A dense airbrush that covers quickly.", air(24f, 0.9f, 0.9f)),
            // Textures
            p("linen", "Linen", TEXTURE, "Fine woven texture for backgrounds.", grain(48f, 0.5f, "canvas", 0.6f)),
            p("pebble", "Pebble", TEXTURE, "Rounded, mottled stone grain.", grain(44f, 0.6f, "blotch", 0.7f)),
            p("sandpaper", "Sandpaper", TEXTURE, "Dense, sharp abrasive grain.", grain(40f, 0.7f, "speckle", 0.5f)),
            p("crosshatch-wide", "Wide hatch", TEXTURE, "Broad, open hatching lines.", grain(48f, 0.55f, "hatch", 2f)),
            p("dot-grid", "Dot grid", TEXTURE, "Even halftone dots for shading.", grain(38f, 0.8f, "halftone", 1.5f)),
            p("newsprint", "Newsprint", TEXTURE, "Coarse, cheap paper fibres.", grain(34f, 0.45f, "paper", 2.5f)),
            // Charcoals
            p("willow", "Willow", CHARCOAL, "Light, dusty charcoal that lifts easily.", grain(14f, 0.45f, "charcoal", 1.4f, 0.7f)),
            p("char-pencil", "Charcoal pencil", CHARCOAL, "A controlled, dark charcoal line.", grain(6f, 0.85f, "charcoal", 0.8f)),
            p(
                "char-side",
                "Charcoal side",
                CHARCOAL,
                "The long side of a stick for broad tone.",
                grain(42f, 0.6f, "charcoal", 1.8f, 0.25f),
            ),
            p("char-dust", "Charcoal dust", CHARCOAL, "Scattered dust for soft atmospheric tone.", scatter(10f, 0.8f, 4, 0.7f, "charcoal")),
            // Spraypaints
            p("drip-can", "Drip can", SPRAY, "A heavy spray with wandering droplets.", scatter(22f, 0.4f, 3, 0.5f, "speckle")),
            p("stencil-mist", "Stencil mist", SPRAY, "A fine, even overspray.", scatter(16f, 0.25f, 6, 0.3f, "speckle")),
            p("flicker", "Flicker", SPRAY, "Sparse, flicked spatters.", scatter(6f, 1f, 2, 0.9f)),
            p("pressure-spray", "Pressure spray", SPRAY, "A tight, dense jet of paint.", scatter(12f, 0.15f, 8, 0.2f, "speckle")),
            // Elements
            p("stars", "Stars", ELEMENTS, "Twinkling points scattered across the sky.", scatter(4f, 1f, 3, 1f, null, BlendMode.SCREEN)),
            p(
                "dust-motes",
                "Dust motes",
                ELEMENTS,
                "Floating specks in a beam of light.",
                scatter(3f, 1f, 5, 0.8f, "fine", BlendMode.SCREEN),
            ),
            p("mist", "Mist", ELEMENTS, "Low, soft fog that builds gradually.", wash(48f, 0.2f, 0f, "blotch", 2.2f)),
            p("embers", "Embers", ELEMENTS, "Glowing sparks drifting upward.", scatter(5f, 0.9f, 2, 0.9f, null, BlendMode.LIGHTEN)),
            p(
                "water-spray",
                "Water spray",
                ELEMENTS,
                "Fine droplets thrown off a wave.",
                scatter(7f, 0.7f, 4, 0.6f, "speckle", BlendMode.SCREEN),
            ),
            // Touch-ups
            p(
                "soft-blend",
                "Soft blend",
                TOUCH,
                "Evens out skin tones with a light touch.",
                air(30f, 0.25f, 0.35f, BlendMode.SOFT_LIGHT).copy(wetMix = 0.6f),
            ),
            p("catchlight", "Catchlight", TOUCH, "A bright, tiny point for eyes.", light(4f, 0.95f, 0f, BlendMode.LIGHTEN)),
            p("lash", "Lash", TOUCH, "Tapered single hairs and lashes.", liner(2f, 0.8f, 0.9f)),
            p("blush", "Blush", TOUCH, "Warm, soft colour for cheeks.", air(40f, 0.15f, 0.25f, BlendMode.MULTIPLY)),
            // Retro
            p("halftone-coarse", "Coarse dots", RETRO, "Big, printed halftone dots.", grain(46f, 0.9f, "halftone", 3f)),
            p("misprint", "Misprint", RETRO, "Uneven ink with gaps, like an old print run.", grain(26f, 0.75f, "speckle", 1.8f)),
            p("linocut", "Linocut", RETRO, "Carved, chunky marks with rough edges.", grain(18f, 1f, "hatch", 1.2f, 0.5f)),
            p("pulp", "Pulp", RETRO, "Yellowed, grainy comic-book shading.", grain(32f, 0.6f, "halftone", 0.8f)),
            // Luminance
            p("lightsaber", "Light blade", GLOW, "A hot core line with a bright halo.", light(10f, 0.9f, 0f)),
            p("aurora", "Aurora", GLOW, "Shifting, colourful light ribbons.", light(40f, 0.45f, 0.3f)),
            p("flare", "Flare", GLOW, "Soft lens flare blooms.", light(48f, 0.3f, 0.05f, BlendMode.COLOR_DODGE)),
            p("firefly", "Firefly", GLOW, "Small glowing dots that drift.", scatter(8f, 0.9f, 2, 0.8f, null, BlendMode.COLOR_DODGE)),
            p("neon-tube", "Tube light", GLOW, "A thick, even glowing tube light.", light(16f, 0.85f, 0f, BlendMode.LIGHTEN)),
            // Industrial
            p("weld", "Weld", INDUSTRY, "Rippled beads of molten metal.", scatter(9f, 0.15f, 3, 0.4f, "blotch")),
            p("peeling", "Peeling paint", INDUSTRY, "Flaking, broken coats of paint.", grain(36f, 0.8f, "blotch", 0.5f, 0.6f)),
            p("oil-stain", "Oil stain", INDUSTRY, "Dark, soaked-in blotches.", wash(40f, 0.5f, 0.6f, "blotch", 0.8f)),
            p("corrugated", "Corrugated", INDUSTRY, "Repeating ridges of sheet metal.", grain(44f, 0.65f, "hatch", 0.7f, 0.15f)),
            // Organic
            p("pine", "Pine needles", ORGANIC, "Clusters of short, sharp strokes.", scatter(12f, 0.3f, 5, 0.5f, "hatch")),
            p("lichen", "Lichen", ORGANIC, "Crusty, spotted growth on rock.", scatter(14f, 0.5f, 4, 0.7f, "blotch")),
            p("feather", "Feather", ORGANIC, "Soft, fanned barbs.", bristle(20f, 0.6f, 0.1f, "hatch", 0.35f)),
            p("wood-grain", "Wood grain", ORGANIC, "Long, flowing grain lines.", grain(40f, 0.55f, "bristle", 1.5f, 0.3f)),
            p("petal", "Petal", ORGANIC, "Soft, tapered petal shapes.", liner(18f, 0.7f, 0.9f, 0.6f)),
            // Water
            p("wet-on-wet", "Wet on wet", WATER, "Colour that blooms into a wet surface.", wash(44f, 0.4f, 0.2f, "blotch", 1.4f)),
            p("dry-wash", "Dry wash", WATER, "A thin wash that dries with hard edges.", wash(36f, 0.35f, 0.9f, "paper", 1f)),
            p("granulate", "Granulating", WATER, "Pigment that settles into the grain.", wash(32f, 0.45f, 0.4f, "speckle", 1.2f)),
            p("glaze-wash", "Glazing wash", WATER, "Transparent layers that deepen tone.", wash(48f, 0.25f, 0.5f, "fine", 1f)),
            p("bloom", "Bloom", WATER, "Backruns and cauliflower edges.", wash(28f, 0.55f, 1f, "blotch", 0.6f)),
            // Abstract
            p("shard", "Shard", ABSTRACT, "Sharp, angular fragments.", scatter(18f, 0.5f, 2, 0.8f, "hatch", BlendMode.DIFFERENCE)),
            p(
                "pulse",
                "Pulse",
                ABSTRACT,
                "Rhythmic, colour-cycling dabs.",
                scatter(14f, 0.1f, 1, 0.3f).copy(hueJitter = 0.4f, spacing = 0.9f),
            ),
            p(
                "static",
                "Static",
                ABSTRACT,
                "Noisy, flickering interference.",
                grain(30f, 0.7f, "speckle", 0.3f).copy(opacityJitter = 0.8f),
            ),
            p("smear", "Smear", ABSTRACT, "Drags existing colour into streaks.", bristle(38f, 0.8f, 0.95f, "canvas", 0.2f)),
        )
}
