package com.artflow.studio

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.color.ColorHarmony
import com.artflow.studio.core.color.Palette
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.presentation.ui.components.color.ColorPanel
import com.artflow.studio.presentation.ui.components.color.PaletteEdits
import com.artflow.studio.presentation.ui.viewmodel.PaletteActions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual panel gestures, CanvasScreen callback binding, and Room-backed settings. */
@RunWith(AndroidJUnit4::class)
class ColorPanelPaletteUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun stalePanelGesturesPreserveNewColorsAndShowSelectionFailures() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val database = Room.inMemoryDatabaseBuilder(context, ArtFlowDatabase::class.java).build()
            val repository = SettingsRepositoryImpl(database.settingsDao())
            val editorJob = SupervisorJob()
            val scope = CoroutineScope(editorJob + Dispatchers.Main.immediate)
            try {
                val initial = Palette(id = 21, name = "Working", colors = listOf(RED, BLUE))
                repository.addPalette(initial)
                // Keep the displayed snapshot stale across writes, as can happen before a flow recomposes.
                val displayed = mutableStateOf(initial)
                val currentColor = mutableIntStateOf(GREEN)
                val snackbar = SnackbarHostState()
                val errors =
                    CoroutineExceptionHandler { _, error ->
                        scope.launch { snackbar.showSnackbar(error.message.orEmpty(), duration = SnackbarDuration.Indefinite) }
                    }
                val actions = PaletteActions(repository, scope, errors) {}
                compose.setContent {
                    MaterialTheme {
                        Column {
                            SnackbarHost(snackbar)
                            ColorPanel(
                                color = currentColor.intValue,
                                recentColors = emptyList(),
                                palettes = listOf(displayed.value),
                                onColorSelected = { currentColor.intValue = it },
                                onClearRecents = {},
                                onSavePalette = actions::addFromColors,
                                palettesEdit = PaletteEdits.from(actions),
                            )
                        }
                    }
                }
                compose.onNodeWithText("Palettes").performScrollTo().performClick()
                compose.onNodeWithContentDescription(ADD_COLOR).performScrollTo().performClick()
                awaitColors(repository, listOf(RED, BLUE, GREEN))
                compose.runOnIdle { currentColor.intValue = BLACK }
                compose.onNodeWithContentDescription(ADD_COLOR).performScrollTo().performClick()
                val latestColors = listOf(RED, BLUE, GREEN, BLACK)
                awaitColors(repository, latestColors)
                assertEquals(
                    latestColors,
                    SettingsRepositoryImpl(database.settingsDao())
                        .settings
                        .first()
                        .customPalettes
                        .single()
                        .colors,
                )

                val redSwatch = context.getString(R.string.palettes_swatch, ColorHarmony.toHex(RED))
                compose.onNodeWithContentDescription(redSwatch).performScrollTo().performTouchInput { longClick() }
                compose.onNodeWithText("Remove swatch").performClick()
                compose.waitUntil(5_000) { snackbar.currentSnackbarData?.visuals?.message == RETRY_MESSAGE }
                compose.onNodeWithText(RETRY_MESSAGE).assertIsDisplayed()
                assertEquals(
                    latestColors,
                    repository
                        .current()
                        .customPalettes
                        .single()
                        .colors,
                )

                compose.runOnIdle {
                    snackbar.currentSnackbarData?.dismiss()
                    displayed.value = repository.current().customPalettes.single()
                }
                compose.onNodeWithContentDescription(redSwatch).performScrollTo().performTouchInput { longClick() }
                compose.onNodeWithText("Remove swatch").performClick()
                awaitColors(repository, listOf(BLUE, GREEN, BLACK))
                repository.removePalette(initial.id)
                compose.onNodeWithContentDescription(ADD_COLOR).performScrollTo().performClick()
                compose.waitUntil(5_000) { snackbar.currentSnackbarData?.visuals?.message == MISSING_MESSAGE }
                compose.onNodeWithText(MISSING_MESSAGE).assertIsDisplayed()
                assertTrue(
                    SettingsRepositoryImpl(database.settingsDao())
                        .settings
                        .first()
                        .customPalettes
                        .isEmpty(),
                )
            } finally {
                editorJob.cancelAndJoin()
                database.close()
            }
        }

    @Test
    fun paletteManagementCommandsPersistThroughTheActualPanel() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val database = Room.inMemoryDatabaseBuilder(context, ArtFlowDatabase::class.java).build()
            val repository = SettingsRepositoryImpl(database.settingsDao())
            val editorJob = SupervisorJob()
            val scope = CoroutineScope(editorJob + Dispatchers.Main.immediate)
            val failures = mutableListOf<Throwable>()
            try {
                val initial = Palette(id = 31, name = "Working", colors = listOf(RED, BLUE))
                repository.addPalette(initial)
                val currentColor = mutableIntStateOf(GREEN)
                val actions = PaletteActions(repository, scope, CoroutineExceptionHandler { _, error -> failures += error }) {}
                compose.setContent {
                    val preferences by repository.settings.collectAsState(initial = repository.current())
                    MaterialTheme {
                        ColorPanel(
                            color = currentColor.intValue,
                            recentColors = emptyList(),
                            palettes = preferences.customPalettes,
                            onColorSelected = { currentColor.intValue = it },
                            onClearRecents = {},
                            onSavePalette = actions::addFromColors,
                            palettesEdit = PaletteEdits.from(actions, defaultName = preferences.defaultPaletteName),
                        )
                    }
                }
                compose.onNodeWithText("Palettes").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Set Working as default").performScrollTo().performClick()
                compose.waitUntil(5_000) { repository.current().defaultPaletteName == "Working" }
                paletteCommand("Working", "Rename")
                enterPaletteName("Study")
                compose.waitUntil(5_000) {
                    repository
                        .current()
                        .customPalettes
                        .single()
                        .name == "Study"
                }
                assertEquals("Study", repository.current().defaultPaletteName)

                val redSwatch = context.getString(R.string.palettes_swatch, ColorHarmony.toHex(RED))
                compose.onNodeWithContentDescription(redSwatch).performScrollTo().performTouchInput { longClick() }
                compose.onNodeWithText("Replace with the current colour").performClick()
                awaitColors(repository, listOf(GREEN, BLUE))
                paletteCommand("Study", "Duplicate")
                compose.waitUntil(5_000) { repository.current().customPalettes.size == 2 }
                val copied = repository.current().customPalettes.first()
                assertEquals("Study copy", copied.name)
                assertEquals(listOf(GREEN, BLUE), copied.colors)
                assertTrue(copied.id != initial.id)
                assertEquals("Study copy", repository.current().defaultPaletteName)

                paletteCommand("Study copy", "Delete")
                compose.waitUntil(5_000) { repository.current().customPalettes.size == 1 }
                assertEquals("", repository.current().defaultPaletteName)
                compose.onNodeWithText("New palette").performScrollTo().performClick()
                enterPaletteName("Sky")
                compose.waitUntil(5_000) { repository.current().customPalettes.size == 2 }
                val reopened = SettingsRepositoryImpl(database.settingsDao()).settings.first()
                assertEquals("Sky", reopened.defaultPaletteName)
                assertEquals(listOf("Sky", "Study"), reopened.customPalettes.map { it.name })
                assertEquals(listOf(emptyList<Int>(), listOf(GREEN, BLUE)), reopened.customPalettes.map { it.colors })
                assertTrue(failures.toString(), failures.isEmpty())
            } finally {
                editorJob.cancelAndJoin()
                database.close()
            }
        }

    private fun paletteCommand(
        name: String,
        command: String,
    ) {
        compose.onNodeWithContentDescription("Palette actions for $name").performScrollTo().performClick()
        compose.onNodeWithText(command).performClick()
    }

    private fun enterPaletteName(name: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        compose.onNodeWithText("OK").performClick()
    }

    private fun awaitColors(
        repository: SettingsRepositoryImpl,
        expected: List<Int>,
    ) {
        compose.waitUntil(5_000) {
            repository
                .current()
                .customPalettes
                .single()
                .colors == expected
        }
    }

    private companion object {
        const val ADD_COLOR = "Add the current colour to Working"
        const val RETRY_MESSAGE = "This palette changed. Please select the swatch again."
        const val MISSING_MESSAGE = "This palette is no longer available. Please choose a palette again."
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
        val GREEN = 0xFF00FF00.toInt()
        val BLACK = 0xFF000000.toInt()
    }
}
