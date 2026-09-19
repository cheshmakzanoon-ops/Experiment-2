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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        private val mutex = Mutex()
        private val state = MutableStateFlow(AppSettings())
        private var loaded = false

        // Do not emit defaults before Room has been read. Every collector gets an owned snapshot.
        override val settings: Flow<AppSettings> =
            flow {
                mutex.withLock { ensureLoaded() }
                emitAll(state.map { it.ownedCopy() })
            }

        override fun current(): AppSettings = state.value.ownedCopy()

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            mutex.withLock {
                ensureLoaded()
                val candidate = transform(state.value.ownedCopy())
                require(candidate.uiScale.isFinite()) { "UI scale must be finite" }
                val normalized =
                    candidate.copy(
                        uiScale = candidate.uiScale.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE),
                        autosaveIntervalMs = candidate.autosaveIntervalMs.coerceIn(MIN_AUTOSAVE, MAX_AUTOSAVE),
                        recentColors = candidate.recentColors.distinct().take(MAX_RECENT_COLORS),
                    )
                val updated = normalized.ownedCopy()
                if (updated == state.value) return@withLock
                currentCoroutineContext().ensureActive()
                // Once this small transaction begins, finish both durability and publication.
                // Cancellation while waiting/loading remains cancellable; failed writes publish nothing.
                withContext(NonCancellable) {
                    persist(updated)
                    state.value = updated
                }
            }
        }

        private fun AppSettings.ownedCopy(): AppSettings =
            copy(
                dismissedTips = dismissedTips.toSet(),
                recentColors = recentColors.toList(),
                customPalettes = customPalettes.map { it.copy(colors = it.colors.toList()) },
            )

        override suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

        override suspend fun setAccent(accent: AccentChoice) = update { it.copy(accent = accent) }

        override suspend fun setHighContrast(enabled: Boolean) = update { it.copy(highContrast = enabled) }

        override suspend fun setReduceMotion(enabled: Boolean) = update { it.copy(reduceMotion = enabled) }

        override suspend fun setUiScale(scale: Float) = update { it.copy(uiScale = scale) }

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

        /** Called only with [mutex] held; failed or cancelled reads may be retried safely. */
        private suspend fun ensureLoaded() {
            if (loaded) return
            val rows = dao.getAllSettings().first()
            state.value = decode(rows.associate { it.key to it.value }).ownedCopy()
            loaded = true
        }

        private suspend fun persist(settings: AppSettings) {
            dao.insertSettings(
                encode(settings).map { (key, value) ->
                    SettingsEntity(key = key, value = value, category = categoryOf(key))
                },
            )
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
                KEY_PALETTES to PaletteCodec.exportJson(settings.customPalettes),
            )

        private fun decode(stored: Map<String, String>): AppSettings {
            val defaults = AppSettings()
            val recentColors =
                stored[KEY_RECENT_COLORS]
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.distinct()
                    ?.take(MAX_RECENT_COLORS)
                    ?: defaults.recentColors
            val palettes =
                stored[KEY_PALETTES]
                    ?.let(StoredPaletteCodec::decode)
                    ?: defaults.customPalettes
            return AppSettings(
                themeMode =
                    stored[KEY_THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                        ?: defaults.themeMode,
                accent = AccentChoice.byName(stored[KEY_ACCENT]),
                highContrast = stored[KEY_HIGH_CONTRAST]?.toBooleanStrictOrNull() ?: defaults.highContrast,
                reduceMotion = stored[KEY_REDUCE_MOTION]?.toBooleanStrictOrNull() ?: defaults.reduceMotion,
                uiScale =
                    stored[KEY_UI_SCALE]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
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
