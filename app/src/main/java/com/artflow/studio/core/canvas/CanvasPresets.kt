package com.artflow.studio.core.canvas

import com.artflow.studio.core.canvas.CanvasOperations.Preset

/**
 * The canvas sizes a person saved from the new-canvas dialog, as Procreate keeps its custom canvases
 * next to the built-in ones. They persist as one line each: `name TAB width TAB height TAB dpi`.
 */
object CanvasPresets {
    /** Names are cut to this when typed; a built-in clash may add a short suffix on top. */
    const val MAX_NAME = 32
    const val MAX_SAVED = 24
    private const val STORED_NAME_LIMIT = 48
    private const val FIELDS = 4
    private const val CLASH_SUFFIX = " (custom)"

    fun encode(presets: List<Preset>): String = presets.joinToString("\n") { "${it.name}\t${it.width}\t${it.height}\t${it.dpi}" }

    /** Reads saved presets back, skipping every line that is damaged or describes a canvas that could not be allocated. */
    fun decode(text: String?): List<Preset> =
        text
            .orEmpty()
            .lineSequence()
            .mapNotNull(::parseLine)
            .distinctBy { it.name.lowercase() }
            .take(MAX_SAVED)
            .toList()

    private fun parseLine(line: String): Preset? {
        val fields = line.split('\t')
        if (fields.size != FIELDS) return null
        val name = cleanName(fields[0], STORED_NAME_LIMIT).ifEmpty { return null }
        val width = fields[1].toIntOrNull() ?: return null
        val height = fields[2].toIntOrNull() ?: return null
        val dpi = fields[3].toIntOrNull() ?: return null
        return if (usable(width, height, dpi)) Preset(name, width, height, dpi) else null
    }

    /** [raw] without control characters (a tab or line break would split a stored line), trimmed and cut to [limit]. */
    private fun cleanName(
        raw: String,
        limit: Int,
    ): String = raw.filterNot { it.isISOControl() }.trim().take(limit)

    private fun usable(
        width: Int,
        height: Int,
        dpi: Int,
    ): Boolean = CanvasOperations.isSizeSafe(width, height) && dpi in CanvasOperations.MIN_DPI..CanvasOperations.MAX_DPI

    /**
     * [saved] with [preset] first. A preset of the same name replaces the old one; a name a built-in size
     * already uses gets a suffix so the two chips can be told apart, and a blank name becomes the size.
     * A size that could not be allocated is not saved.
     */
    fun add(
        saved: List<Preset>,
        preset: Preset,
    ): List<Preset> {
        if (!usable(preset.width, preset.height, preset.dpi)) return saved
        val base = cleanName(preset.name, MAX_NAME).ifEmpty { "${preset.width}×${preset.height}" }
        val name = if (isBuiltIn(base)) base + CLASH_SUFFIX else base
        return (listOf(preset.copy(name = name)) + saved.filterNot { it.name.equals(name, ignoreCase = true) }).take(MAX_SAVED)
    }

    fun remove(
        saved: List<Preset>,
        name: String,
    ): List<Preset> = saved.filterNot { it.name == name }

    private fun isBuiltIn(name: String): Boolean =
        name.equals(CanvasOperations.SCREEN_SIZE, ignoreCase = true) ||
            CanvasOperations.PRESETS.any { it.name.equals(name, ignoreCase = true) }
}
