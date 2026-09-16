package com.artflow.studio.data.repository.settings

import com.artflow.studio.core.color.Palette
import com.artflow.studio.core.color.PaletteCodec
import com.artflow.studio.data.local.dao.SettingsDao
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.domain.model.settings.AccentChoice
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.GallerySort
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.domain.repository.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed preferences.
 *
 * The settings table is a plain key/value store, which means adding a preference never needs a
 * database migration: unknown keys are ignored and missing keys fall back to [AppSettings]
 * defaults. Lists (recent colours, custom palettes) are stored as JSON in a single row.
 */
@Singleton
class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dao: SettingsDao,
    ) : SettingsRepository {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val state = MutableStateFlow(AppSettings())
        private var loaded = false

        override val settings: StateFlow<AppSettings> = state.asStateFlow()

        override fun current(): AppSettings = state.value

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            ensureLoaded()
            val updated = transform(state.value)
            state.value = updated
            persist(updated)
        }

        override suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

        override suspend fun setAccent(accent: AccentChoice) = update { it.copy(accent = accent) }

        override suspend fun setHighContrast(enabled: Boolean) = update { it.copy(highContrast = enabled) }

        override suspend fun setReduceMotion(enabled: Boolean) = update { it.copy(reduceMotion = enabled) }

        override suspend fun setUiScale(scale: Float) = update { it.copy(uiScale = scale.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)) }

        override suspend fun setLargeTouchTargets(enabled: Boolean) = update { it.copy(largeTouchTargets = enabled) }

        override suspend fun setCheckerboard(enabled: Boolean) = update { it.copy(checkerboard = enabled) }

        override suspend fun setOnionSkin(enabled: Boolean) = update { it.copy(onionSkin = enabled) }

        override suspend fun setSymmetryGuides(enabled: Boolean) = update { it.copy(showSymmetryGuides = enabled) }

        override suspend fun setPerspectiveGuides(enabled: Boolean) = update { it.copy(showPerspectiveGuides = enabled) }

        override suspend fun setSnapToGuides(enabled: Boolean) = update { it.copy(snapToGuides = enabled) }

        override suspend fun setStylusOnly(enabled: Boolean) = update { it.copy(stylusOnly = enabled) }

        override suspend fun setBrushCursor(enabled: Boolean) = update { it.copy(brushCursor = enabled) }

        override suspend fun setHaptics(enabled: Boolean) = update { it.copy(haptics = enabled) }

        override suspend fun setAutosave(
            enabled: Boolean,
            intervalMs: Long,
        ) = update {
            it.copy(autosaveEnabled = enabled, autosaveIntervalMs = intervalMs.coerceIn(MIN_AUTOSAVE, MAX_AUTOSAVE))
        }

        override suspend fun setDefaultPreset(name: String) = update { it.copy(defaultPresetName = name) }

        override suspend fun setGallerySort(sort: GallerySort) = update { it.copy(gallerySort = sort) }

        override suspend fun setOnboardingSeen(seen: Boolean) = update { it.copy(seenOnboarding = seen) }

        override suspend fun dismissTip(id: String) = update { it.copy(dismissedTips = it.dismissedTips + id) }

        override suspend fun pushRecentColor(color: Int) =
            update { current ->
                val existing = current.recentColors.filter { it != color }
                current.copy(recentColors = (listOf(color) + existing).take(MAX_RECENT_COLORS))
            }

        override suspend fun clearRecentColors() = update { it.copy(recentColors = emptyList()) }

        override suspend fun addPalette(palette: Palette) =
            update { current ->
                val without = current.customPalettes.filterNot { it.id == palette.id && palette.id != 0L }
                current.copy(customPalettes = without + palette)
            }

        override suspend fun removePalette(paletteId: Long) =
            update { current ->
                current.copy(customPalettes = current.customPalettes.filterNot { it.id == paletteId })
            }

        override suspend fun renamePalette(
            paletteId: Long,
            name: String,
        ) = update { current ->
            current.copy(
                customPalettes =
                    current.customPalettes.map { palette ->
                        if (palette.id == paletteId) palette.copy(name = name) else palette
                    },
            )
        }

        // -----------------------------------------------------------------------------------------
        // Persistence
        // -----------------------------------------------------------------------------------------

        private suspend fun ensureLoaded() {
            if (loaded) return
            loaded = true
            try {
                // The DAO exposes a Flow; the first emission is the full table.
                val rows = dao.getAllSettings().first()
                state.value = decode(rows.associate { it.key to it.value })
            } catch (error: Throwable) {
                Timber.e(error, "Could not read settings; using defaults")
            }
        }

        private suspend fun persist(settings: AppSettings) {
            val rows = encode(settings)
            rows.forEach { (key, value) ->
                dao.insertSetting(SettingsEntity(key = key, value = value, category = categoryOf(key)))
            }
        }

        private fun encode(settings: AppSettings): Map<String, String> =
            mapOf(
                KEY_THEME to settings.themeMode.name,
                KEY_ACCENT to settings.accent.name,
                KEY_HIGH_CONTRAST to settings.highContrast.toString(),
                KEY_REDUCE_MOTION to settings.reduceMotion.toString(),
                KEY_UI_SCALE to settings.uiScale.toString(),
                KEY_LARGE_TOUCH to settings.largeTouchTargets.toString(),
                KEY_CHECKERBOARD to settings.checkerboard.toString(),
                KEY_ONION to settings.onionSkin.toString(),
                KEY_SYMMETRY_GUIDES to settings.showSymmetryGuides.toString(),
                KEY_PERSPECTIVE_GUIDES to settings.showPerspectiveGuides.toString(),
                KEY_SNAP to settings.snapToGuides.toString(),
                KEY_BRUSH_CURSOR to settings.brushCursor.toString(),
                KEY_STYLUS_ONLY to settings.stylusOnly.toString(),
                KEY_HAPTICS to settings.haptics.toString(),
                KEY_AUTOSAVE to settings.autosaveEnabled.toString(),
                KEY_AUTOSAVE_INTERVAL to settings.autosaveIntervalMs.toString(),
                KEY_DEFAULT_PRESET to settings.defaultPresetName,
                KEY_GALLERY_SORT to settings.gallerySort.name,
                KEY_ONBOARDING to settings.seenOnboarding.toString(),
                KEY_TIPS to settings.dismissedTips.joinToString("|"),
                KEY_RECENT_COLORS to settings.recentColors.joinToString(","),
                KEY_PALETTES to settings.customPalettes.joinToString(";;") { PaletteCodec.exportJson(it) },
            )

        private fun decode(stored: Map<String, String>): AppSettings {
            val defaults = AppSettings()
            val recentColors =
                stored[KEY_RECENT_COLORS]
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?: defaults.recentColors
            val palettes =
                stored[KEY_PALETTES]
                    ?.split(";;")
                    ?.mapNotNull { entry -> if (entry.isBlank()) null else PaletteCodec.importJson(entry) }
                    ?: defaults.customPalettes
            return AppSettings(
                themeMode =
                    stored[KEY_THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                        ?: defaults.themeMode,
                accent = AccentChoice.byName(stored[KEY_ACCENT]),
                highContrast = stored[KEY_HIGH_CONTRAST]?.toBooleanStrictOrNull() ?: defaults.highContrast,
                reduceMotion = stored[KEY_REDUCE_MOTION]?.toBooleanStrictOrNull() ?: defaults.reduceMotion,
                uiScale =
                    stored[KEY_UI_SCALE]?.toFloatOrNull()?.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
                        ?: defaults.uiScale,
                largeTouchTargets = stored[KEY_LARGE_TOUCH]?.toBooleanStrictOrNull() ?: defaults.largeTouchTargets,
                checkerboard = stored[KEY_CHECKERBOARD]?.toBooleanStrictOrNull() ?: defaults.checkerboard,
                onionSkin = stored[KEY_ONION]?.toBooleanStrictOrNull() ?: defaults.onionSkin,
                showSymmetryGuides =
                    stored[KEY_SYMMETRY_GUIDES]?.toBooleanStrictOrNull()
                        ?: defaults.showSymmetryGuides,
                showPerspectiveGuides =
                    stored[KEY_PERSPECTIVE_GUIDES]?.toBooleanStrictOrNull()
                        ?: defaults.showPerspectiveGuides,
                snapToGuides = stored[KEY_SNAP]?.toBooleanStrictOrNull() ?: defaults.snapToGuides,
                brushCursor = stored[KEY_BRUSH_CURSOR]?.toBooleanStrictOrNull() ?: defaults.brushCursor,
                stylusOnly = stored[KEY_STYLUS_ONLY]?.toBooleanStrictOrNull() ?: defaults.stylusOnly,
                haptics = stored[KEY_HAPTICS]?.toBooleanStrictOrNull() ?: defaults.haptics,
                autosaveEnabled = stored[KEY_AUTOSAVE]?.toBooleanStrictOrNull() ?: defaults.autosaveEnabled,
                autosaveIntervalMs =
                    stored[KEY_AUTOSAVE_INTERVAL]
                        ?.toLongOrNull()
                        ?.coerceIn(MIN_AUTOSAVE, MAX_AUTOSAVE) ?: defaults.autosaveIntervalMs,
                defaultPresetName = stored[KEY_DEFAULT_PRESET] ?: defaults.defaultPresetName,
                gallerySort =
                    stored[KEY_GALLERY_SORT]
                        ?.let { name -> GallerySort.entries.firstOrNull { it.name == name } }
                        ?: defaults.gallerySort,
                seenOnboarding = stored[KEY_ONBOARDING]?.toBooleanStrictOrNull() ?: defaults.seenOnboarding,
                dismissedTips =
                    stored[KEY_TIPS]?.split('|')?.filter { it.isNotBlank() }?.toSet()
                        ?: defaults.dismissedTips,
                recentColors = recentColors,
                customPalettes = palettes,
            )
        }

        private fun categoryOf(key: String): String =
            when {
                key.startsWith("theme.") || key.startsWith("access.") -> "appearance"
                key.startsWith("canvas.") -> "canvas"
                key.startsWith("input.") -> "input"
                else -> "general"
            }

        /** Warms the cache in the background; safe to call from `Application.onCreate`. */
        fun preload() {
            scope.launch { ensureLoaded() }
        }

        companion object {
            private const val MIN_UI_SCALE = 0.85f
            private const val MAX_UI_SCALE = 1.6f
            private const val MIN_AUTOSAVE = 5_000L
            private const val MAX_AUTOSAVE = 300_000L
            private const val MAX_RECENT_COLORS = 24

            private const val KEY_THEME = "theme.mode"
            private const val KEY_ACCENT = "theme.accent"
            private const val KEY_HIGH_CONTRAST = "access.highContrast"
            private const val KEY_REDUCE_MOTION = "access.reduceMotion"
            private const val KEY_UI_SCALE = "access.uiScale"
            private const val KEY_LARGE_TOUCH = "access.largeTouchTargets"
            private const val KEY_CHECKERBOARD = "canvas.checkerboard"
            private const val KEY_ONION = "canvas.onionSkin"
            private const val KEY_SYMMETRY_GUIDES = "canvas.symmetryGuides"
            private const val KEY_PERSPECTIVE_GUIDES = "canvas.perspectiveGuides"
            private const val KEY_SNAP = "canvas.snapToGuides"
            private const val KEY_BRUSH_CURSOR = "canvas.brushCursor"
            private const val KEY_STYLUS_ONLY = "input.stylusOnly"
            private const val KEY_HAPTICS = "input.haptics"
            private const val KEY_AUTOSAVE = "general.autosave"
            private const val KEY_AUTOSAVE_INTERVAL = "general.autosaveInterval"
            private const val KEY_DEFAULT_PRESET = "general.defaultPreset"
            private const val KEY_GALLERY_SORT = "gallery.sort"
            private const val KEY_ONBOARDING = "general.onboardingSeen"
            private const val KEY_TIPS = "general.dismissedTips"
            private const val KEY_RECENT_COLORS = "color.recent"
            private const val KEY_PALETTES = "color.palettes"
        }
    }
