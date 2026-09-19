package com.artflow.studio.domain.model.settings

import com.artflow.studio.core.color.Palette

/** Which theme the app follows. */
enum class ThemeMode(
    val displayName: String,
) {
    SYSTEM("Match system"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * Accent palettes offered by the theme picker. The names are deliberately concrete (ink, indigo,
 * terracotta...) so the app does not look like every other Material default.
 */
enum class AccentChoice(
    val displayName: String,
    val seed: Long,
) {
    INK("Ink", 0xFF2B3A55),
    INDIGO("Indigo", 0xFF4C5FD5),
    TERRACOTTA("Terracotta", 0xFFB4553D),
    FOREST("Forest", 0xFF2F6F4E),
    PLUM("Plum", 0xFF7A3E68),
    OCHRE("Ochre", 0xFFB07D2B),
    ;

    companion object {
        fun byName(name: String?): AccentChoice = entries.firstOrNull { it.name == name } ?: INK
    }
}

/** How the gallery orders projects. */
enum class GallerySort(
    val displayName: String,
) {
    RECENT("Recently edited"),
    CREATED("Recently created"),
    NAME("Name"),
    SIZE("Canvas size"),
}

/**
 * Every user preference, in one immutable value.
 *
 * The app reads this through `SettingsRepository.settings` and writes it through the typed setters,
 * so a new preference only ever needs one field plus one setter.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentChoice = AccentChoice.INK,
    // Accessibility
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    /** Extra UI text scale, 0.85 - 1.6. */
    val uiScale: Float = 1f,
    val largeTouchTargets: Boolean = false,
    // Canvas
    val checkerboard: Boolean = true,
    val onionSkin: Boolean = false,
    val showSymmetryGuides: Boolean = true,
    val showPerspectiveGuides: Boolean = true,
    val snapToGuides: Boolean = true,
    val brushCursor: Boolean = true,
    /** When true only a stylus paints; fingers are reserved for navigation (palm rejection). */
    val stylusOnly: Boolean = false,
    val haptics: Boolean = true,
    // Saving
    val autosaveIntervalMs: Long = 20_000L,
    val autosaveEnabled: Boolean = true,
    // New documents
    val defaultPresetName: String = "FHD 1080p",
    // Gallery
    val gallerySort: GallerySort = GallerySort.RECENT,
    // Content the user has already seen
    val seenOnboarding: Boolean = false,
    val dismissedTips: Set<String> = emptySet(),
    // Colour state that has to survive a restart
    val recentColors: List<Int> = emptyList(),
    val customPalettes: List<Palette> = emptyList(),
    /** Runtime diagnostic, not a preference: unreadable palette data is retained until backed up. */
    val paletteRecoveryRequired: Boolean = false,
) {
    /** True when a finger may paint. */
    val fingerPainting: Boolean get() = !stylusOnly
}
