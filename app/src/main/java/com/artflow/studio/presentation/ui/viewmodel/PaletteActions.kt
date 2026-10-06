package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.color.Palette
import com.artflow.studio.domain.repository.settings.SettingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The Palettes tab's edits: new palettes, changes, removal and the default palette, all saved in settings. */
class PaletteActions(
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val errors: CoroutineExceptionHandler,
    private val notify: (String) -> Unit,
) {
    fun addFromColors(
        name: String,
        colors: List<Int>,
    ) {
        scope.launch(errors) {
            settingsRepository.addPalette(Palette(id = System.currentTimeMillis(), name = name, colors = colors, category = "Custom"))
            notify("Palette saved")
        }
    }

    /** Shows [name]'s palette under the colour pickers, or none when it is already the default. */
    fun toggleDefault(name: String) {
        scope.launch(errors) {
            settingsRepository.update { it.copy(defaultPaletteName = if (it.defaultPaletteName == name) "" else name) }
        }
    }

    /** Replaces a custom palette with its edited version. */
    fun update(palette: Palette) {
        scope.launch(errors) {
            settingsRepository.update { settings ->
                settings.copy(customPalettes = settings.customPalettes.map { if (it.id == palette.id) palette else it })
            }
        }
    }

    fun remove(paletteId: Long) {
        scope.launch(errors) { settingsRepository.removePalette(paletteId) }
    }
}
