package com.artflow.studio.domain.repository.settings

import com.artflow.studio.core.color.Palette
import com.artflow.studio.domain.model.settings.AccentChoice
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.GallerySort
import com.artflow.studio.domain.model.settings.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * User preferences.
 *
 * Preferences are tiny and read on every screen, so the current value is cached in memory and
 * exposed as a [Flow]; `update` writes through to storage and never blocks the UI.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    /** The current snapshot without subscribing. */
    fun current(): AppSettings

    suspend fun update(transform: (AppSettings) -> AppSettings)

    // Convenience setters for the settings screen.
    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setAccent(accent: AccentChoice)

    suspend fun setHighContrast(enabled: Boolean)

    suspend fun setReduceMotion(enabled: Boolean)

    suspend fun setUiScale(scale: Float)

    suspend fun setLargeTouchTargets(enabled: Boolean)

    suspend fun setCheckerboard(enabled: Boolean)

    suspend fun setOnionSkin(enabled: Boolean)

    suspend fun setSymmetryGuides(enabled: Boolean)

    suspend fun setPerspectiveGuides(enabled: Boolean)

    suspend fun setSnapToGuides(enabled: Boolean)

    suspend fun setStylusOnly(enabled: Boolean)

    suspend fun setBrushCursor(enabled: Boolean)

    suspend fun setHaptics(enabled: Boolean)

    suspend fun setAutosave(
        enabled: Boolean,
        intervalMs: Long = current().autosaveIntervalMs,
    )

    suspend fun setDefaultPreset(name: String)

    suspend fun setGallerySort(sort: GallerySort)

    suspend fun setOnboardingSeen(seen: Boolean)

    suspend fun dismissTip(id: String)

    // Colour state
    suspend fun pushRecentColor(color: Int)

    suspend fun clearRecentColors()

    suspend fun addPalette(palette: Palette)

    suspend fun removePalette(paletteId: Long)

    suspend fun renamePalette(
        paletteId: Long,
        name: String,
    )
}
