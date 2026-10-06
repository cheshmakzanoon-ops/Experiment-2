package com.artflow.studio.domain.model.settings

import com.artflow.studio.core.color.Palette
import com.artflow.studio.domain.model.brush.PressureResponse

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
    /** Procreate's right-hand interface: the size/opacity sidebar sits on the right edge. */
    val rightHandedInterface: Boolean = false,
    /** Pressure response exponent: below 1 lays down more ink at light pressure, above 1 needs a firmer touch. */
    val pressureCurve: Float = 1f,
    /** Extra stroke steadying applied on top of every brush's own smoothing (0..1). */
    val stabilization: Float = 0f,
    /** Motion filtering: takes the jitter out of slow, careful movement (0..1). */
    val motionFiltering: Float = 0f,
    /** How much of the filtered-out movement comes back (0..1). */
    val motionExpression: Float = 0.5f,
    /** Evens out sudden pressure changes (0..1). */
    val pressureSmoothing: Float = 0f,
    /** Pulled string: the brush trails the pen on a string this long (0..1 of its longest reach). */
    val pulledString: Float = 0f,
    /** Pressure curve: what a light, medium and firm press give, applied after [pressureCurve]. */
    val pressureResponse: PressureResponse = PressureResponse(),
    /** Gesture controls: rubbing three fingers clears the layer. */
    val scrubToClear: Boolean = true,
    /** Gesture controls: swiping three fingers down opens Copy & Paste. */
    val swipeCopyPaste: Boolean = true,
    /** Gesture controls: tapping four fingers toggles full screen. */
    val fourFingerFullScreen: Boolean = true,
    /** Gesture controls: how long two or three fingers must rest before undo or redo starts repeating. */
    val rapidUndoDelayMs: Int = 650,
    /** Gesture controls: how long a touch must rest before the eyedropper appears. */
    val eyedropperDelayMs: Int = 500,
    /** Gesture controls: how long the pen must rest at the end of a stroke before QuickShape snaps. */
    val quickShapeDelayMs: Int = 650,
    /** Gesture controls: a finger held still selects the layer under it instead of sampling colour. */
    val holdSelectsLayer: Boolean = false,
    /** Longest side, in pixels, of recorded time-lapse frames (720p, 1080p or 1440p). */
    val timelapseMaxSide: Int = 1280,
    /** Brush size follows the zoom so the brush looks the same size on screen. */
    val dynamicBrushScaling: Boolean = false,
    /** Prefs > Selection mask visibility: how strongly the selected area is tinted (0..1). */
    val selectionMaskVisibility: Float = 0.28f,
    /** Prefs > Project canvas: a second screen shows the artwork alone, without the interface. */
    val projectCanvas: Boolean = false,
    /** The QuickMenu's six actions, by label, clockwise from the top; touch and hold a slot to change it. */
    val quickMenu: List<String> = listOf("New layer", "Merge down", "Flip horizontal", "Clear layer", "Copy", "Paste"),
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

/** Prefs > Pressure and Smoothing, as one value the panel edits and the settings store. */
data class PressureAndSmoothing(
    val pressureCurve: Float = 1f,
    val stabilization: Float = 0f,
    val motionFiltering: Float = 0f,
    val motionExpression: Float = 0.5f,
    val pressureSmoothing: Float = 0f,
    /** Pulled string: the brush trails the pen on a string this long (0..1 of its longest reach). */
    val pulledString: Float = 0f,
    /** Pressure curve: what a light, medium and firm press give, applied after [pressureCurve]. */
    val pressureResponse: PressureResponse = PressureResponse(),
)

val AppSettings.pressureAndSmoothing: PressureAndSmoothing
    get() =
        PressureAndSmoothing(
            pressureCurve,
            stabilization,
            motionFiltering,
            motionExpression,
            pressureSmoothing,
            pulledString,
            pressureResponse,
        )
