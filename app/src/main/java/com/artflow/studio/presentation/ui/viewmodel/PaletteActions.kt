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

    /** Adds to the latest stored swatches, even when the panel has not recomposed yet. */
    fun addColor(
        paletteId: Long,
        color: Int,
    ) {
        edit(paletteId) { it.withColor(color) }
    }

    /** A position is safe to remove only while it still identifies the displayed swatch. */
    fun removeColor(
        displayed: Palette,
        index: Int,
    ) {
        val expectedColors = displayed.colors.toList()
        edit(displayed.id) { latest ->
            check(latest.colors == expectedColors && index in expectedColors.indices) {
                "This palette changed. Please select the swatch again."
            }
            latest.withoutColor(index)
        }
    }

    private fun edit(
        paletteId: Long,
        transform: (Palette) -> Palette,
    ) {
        scope.launch(errors) {
            settingsRepository.update { settings ->
                val latest =
                    checkNotNull(settings.customPalettes.singleOrNull { it.id == paletteId && it.category == "Custom" }) {
                        "This palette is no longer available. Please choose a palette again."
                    }
                val edited = transform(latest)
                settings.copy(customPalettes = settings.customPalettes.map { if (it === latest) edited else it })
            }
        }
    }

    /** Explicit whole-palette replacement; incremental panel edits use [addColor] and [removeColor]. */
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
