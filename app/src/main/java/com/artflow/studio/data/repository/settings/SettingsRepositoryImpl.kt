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
        private var unreadablePalettes: String? = null

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
                check(unreadablePalettes == null || candidate.customPalettes == state.value.customPalettes) {
                    "Saved palettes need recovery. Open Settings to back up and reset them; the original data is preserved."
                }
                val normalized =
                    candidate.copy(
                        uiScale = candidate.uiScale.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE),
                        autosaveIntervalMs = candidate.autosaveIntervalMs.coerceIn(MIN_AUTOSAVE, MAX_AUTOSAVE),
                        recentColors = candidate.recentColors.distinct().take(MAX_RECENT_COLORS),
                        paletteRecoveryRequired = unreadablePalettes != null,
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

        override suspend fun backupAndResetUnreadablePalettes(writeBackup: suspend (String) -> Unit): Boolean {
            val original =
                mutex.withLock {
                    ensureLoaded()
                    unreadablePalettes
                } ?: return false
            // Do not hold the preference mutex while a document provider writes the backup.
            writeBackup(original)
            currentCoroutineContext().ensureActive()
            return mutex.withLock {
                check(unreadablePalettes == original) { "Palette recovery changed; no stored data was reset" }
                val updated = state.value.copy(customPalettes = emptyList(), paletteRecoveryRequired = false)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    persist(updated, preserveUnreadable = false)
                    unreadablePalettes = null
                    state.value = updated
                }
                true
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

        private suspend fun persist(
            settings: AppSettings,
            preserveUnreadable: Boolean = true,
        ) {
            val values = encode(settings)
            val protectPalettes = preserveUnreadable && unreadablePalettes != null
            dao.insertSettings(
                values.filterKeys { it != KEY_PALETTES || !protectPalettes }.map { (key, value) ->
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
                KEY_RIGHT_HANDED to settings.rightHandedInterface.toString(),
                KEY_PRESSURE_CURVE to settings.pressureCurve.toString(),
                KEY_STABILIZATION to settings.stabilization.toString(),
                KEY_MOTION_FILTERING to settings.motionFiltering.toString(),
                KEY_MOTION_EXPRESSION to settings.motionExpression.toString(),
                KEY_PRESSURE_SMOOTHING to settings.pressureSmoothing.toString(),
                KEY_SCRUB_CLEAR to settings.scrubToClear.toString(),
                KEY_SWIPE_PASTE to settings.swipeCopyPaste.toString(),
                KEY_FOUR_FINGER to settings.fourFingerFullScreen.toString(),
                KEY_TIMELAPSE_SIDE to settings.timelapseMaxSide.toString(),
                KEY_DYNAMIC_BRUSH to settings.dynamicBrushScaling.toString(),
                KEY_QUICK_MENU to settings.quickMenu.joinToString("|"),
                KEY_AUTOSAVE to settings.autosaveEnabled.toString(),
                KEY_AUTOSAVE_INTERVAL to settings.autosaveIntervalMs.toString(),
                KEY_DEFAULT_PRESET to settings.defaultPresetName,
                KEY_GALLERY_SORT to settings.gallerySort.name,
                KEY_ONBOARDING to settings.seenOnboarding.toString(),
                KEY_TIPS to settings.dismissedTips.joinToString("|"),
                KEY_RECENT_COLORS to settings.recentColors.joinToString(","),
                KEY_PALETTES to PaletteCodec.exportJson(settings.customPalettes),
            )

        /** A stored true/false, or [default] when it is missing or unreadable. */
        private fun Map<String, String>.unit(key: String): Float? = this[key]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)

        private fun Map<String, String>.flag(
            key: String,
            default: Boolean,
        ): Boolean = this[key]?.toBooleanStrictOrNull() ?: default

        /** A stored QuickMenu, kept only when it still has one label for every slot. */
        private fun Map<String, String>.slots(
            key: String,
            default: List<String>,
        ): List<String> = this[key]?.split('|')?.takeIf { slots -> slots.size == default.size && slots.none { it.isBlank() } } ?: default

        private fun decode(stored: Map<String, String>): AppSettings {
            val defaults = AppSettings()
            val recentColors =
                stored[KEY_RECENT_COLORS]
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.distinct()
                    ?.take(MAX_RECENT_COLORS)
                    ?: defaults.recentColors
            val palettes = readPalettes(stored[KEY_PALETTES])
            return AppSettings(
                themeMode =
                    stored[KEY_THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                        ?: defaults.themeMode,
                accent = AccentChoice.byName(stored[KEY_ACCENT]),
                highContrast = stored.flag(KEY_HIGH_CONTRAST, defaults.highContrast),
                reduceMotion = stored.flag(KEY_REDUCE_MOTION, defaults.reduceMotion),
                uiScale =
                    stored[KEY_UI_SCALE]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
                        ?: defaults.uiScale,
                largeTouchTargets = stored.flag(KEY_LARGE_TOUCH, defaults.largeTouchTargets),
                checkerboard = stored.flag(KEY_CHECKERBOARD, defaults.checkerboard),
                onionSkin = stored.flag(KEY_ONION, defaults.onionSkin),
                showSymmetryGuides =
                    stored.flag(KEY_SYMMETRY_GUIDES, defaults.showSymmetryGuides),
                showPerspectiveGuides =
                    stored.flag(KEY_PERSPECTIVE_GUIDES, defaults.showPerspectiveGuides),
                snapToGuides = stored.flag(KEY_SNAP, defaults.snapToGuides),
                brushCursor = stored.flag(KEY_BRUSH_CURSOR, defaults.brushCursor),
                stylusOnly = stored.flag(KEY_STYLUS_ONLY, defaults.stylusOnly),
                haptics = stored.flag(KEY_HAPTICS, defaults.haptics),
                rightHandedInterface = stored.flag(KEY_RIGHT_HANDED, defaults.rightHandedInterface),
                pressureCurve =
                    stored[KEY_PRESSURE_CURVE]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(MIN_CURVE, MAX_CURVE)
                        ?: defaults.pressureCurve,
                stabilization =
                    stored[KEY_STABILIZATION]?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                        ?: defaults.stabilization,
                motionFiltering = stored.unit(KEY_MOTION_FILTERING) ?: defaults.motionFiltering,
                motionExpression = stored.unit(KEY_MOTION_EXPRESSION) ?: defaults.motionExpression,
                pressureSmoothing = stored.unit(KEY_PRESSURE_SMOOTHING) ?: defaults.pressureSmoothing,
                scrubToClear = stored.flag(KEY_SCRUB_CLEAR, defaults.scrubToClear),
                swipeCopyPaste = stored.flag(KEY_SWIPE_PASTE, defaults.swipeCopyPaste),
                fourFingerFullScreen = stored.flag(KEY_FOUR_FINGER, defaults.fourFingerFullScreen),
                timelapseMaxSide =
                    stored[KEY_TIMELAPSE_SIDE]?.toIntOrNull()?.takeIf { it in TIMELAPSE_SIDES } ?: defaults.timelapseMaxSide,
                dynamicBrushScaling = stored.flag(KEY_DYNAMIC_BRUSH, defaults.dynamicBrushScaling),
                quickMenu = stored.slots(KEY_QUICK_MENU, defaults.quickMenu),
                autosaveEnabled = stored.flag(KEY_AUTOSAVE, defaults.autosaveEnabled),
                autosaveIntervalMs =
                    stored[KEY_AUTOSAVE_INTERVAL]
                        ?.toLongOrNull()
                        ?.coerceIn(MIN_AUTOSAVE, MAX_AUTOSAVE) ?: defaults.autosaveIntervalMs,
                defaultPresetName = stored[KEY_DEFAULT_PRESET] ?: defaults.defaultPresetName,
                gallerySort =
                    stored[KEY_GALLERY_SORT]
                        ?.let { name -> GallerySort.entries.firstOrNull { it.name == name } }
                        ?: defaults.gallerySort,
                seenOnboarding = stored.flag(KEY_ONBOARDING, defaults.seenOnboarding),
                dismissedTips =
                    stored[KEY_TIPS]?.split('|')?.filter { it.isNotBlank() }?.toSet()
                        ?: defaults.dismissedTips,
                recentColors = recentColors,
                customPalettes = palettes,
                paletteRecoveryRequired = unreadablePalettes != null,
            )
        }

        private fun readPalettes(stored: String?): List<Palette> {
            unreadablePalettes = null
            if (stored == null) return emptyList()
            return try {
                StoredPaletteCodec.decode(stored)
            } catch (invalid: IllegalArgumentException) {
                // One damaged optional row must not block the gallery or reset unrelated preferences.
                unreadablePalettes = stored
                emptyList()
            }
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
            private const val KEY_RIGHT_HANDED = "interface.rightHanded"
            private const val KEY_PRESSURE_CURVE = "input.pressureCurve"
            private const val KEY_STABILIZATION = "input.stabilization"
            private const val KEY_MOTION_FILTERING = "input.motionFiltering"
            private const val KEY_MOTION_EXPRESSION = "input.motionExpression"
            private const val KEY_PRESSURE_SMOOTHING = "input.pressureSmoothing"
            private const val KEY_SCRUB_CLEAR = "gesture.scrubToClear"
            private const val KEY_SWIPE_PASTE = "gesture.swipeCopyPaste"
            private const val KEY_FOUR_FINGER = "gesture.fourFingerFullScreen"
            private const val KEY_TIMELAPSE_SIDE = "timelapse.maxSide"
            private const val KEY_DYNAMIC_BRUSH = "brush.dynamicScaling"

            /** Time-lapse recording sizes offered in Prefs: 720p, 1080p and 1440p. */
            val TIMELAPSE_SIDES = listOf(1280, 1920, 2560)
            private const val MIN_CURVE = 0.3f
            private const val MAX_CURVE = 3f
            private const val KEY_AUTOSAVE = "general.autosave"
            private const val KEY_AUTOSAVE_INTERVAL = "general.autosaveInterval"
            private const val KEY_DEFAULT_PRESET = "general.defaultPreset"
            private const val KEY_QUICK_MENU = "studio.quickMenu"
            private const val KEY_GALLERY_SORT = "gallery.sort"
            private const val KEY_ONBOARDING = "general.onboardingSeen"
            private const val KEY_TIPS = "general.dismissedTips"
            private const val KEY_RECENT_COLORS = "color.recent"
            private const val KEY_PALETTES = "color.palettes"
        }
    }
