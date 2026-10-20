@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.artflow.studio.R
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.domain.model.settings.AccentChoice
import com.artflow.studio.domain.model.settings.GallerySort
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel

/**
 * Settings and accessibility (Phases 47-48).
 *
 * Every preference here is persisted through `SettingsRepository`, so the app restores exactly the
 * state it was left in, including theme, guides, autosave cadence and input behaviour.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val recoveryRunning by viewModel.paletteRecoveryRunning.collectAsState()
    val resolver = LocalContext.current.contentResolver
    val paletteBackup =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { destination ->
            if (destination != null) viewModel.backUpAndResetPalettes(resolver, destination)
        }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messageFlow.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.helpscreen_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.paletteRecoveryRequired) {
                PaletteRecoveryNotice(recoveryRunning) { paletteBackup.launch("ArtFlow-unreadable-palettes.txt") }
            }
            SettingsSection("Appearance")
            Text(stringResource(R.string.settingsscreen_theme), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            Text(stringResource(R.string.settingsscreen_accent), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentChoice.entries.forEach { accent ->
                    FilterChip(
                        selected = settings.accent == accent,
                        onClick = { viewModel.setAccent(accent) },
                        label = { Text(accent.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            SettingsSection("Accessibility")
            SwitchRow(
                title = stringResource(R.string.settingsscreen_high_contrast),
                subtitle = stringResource(R.string.settingsscreen_stronger_outlines_and_clearer_text),
                checked = settings.highContrast,
                onChange = viewModel::setHighContrast,
            )
            SwitchRow(
                title = "Reduce motion",
                subtitle = "Fit to screen jumps instead of animating, and selection outlines stay still",
                checked = settings.reduceMotion,
                onChange = viewModel::setReduceMotion,
            )
            SwitchRow(
                title = stringResource(R.string.settingsscreen_larger_touch_targets),
                subtitle = stringResource(R.string.settingsscreen_bigger_toolbar_buttons_for_easier),
                checked = settings.largeTouchTargets,
                onChange = viewModel::setLargeTouchTargets,
            )
            Column {
                Text(
                    "Interface scale: ${(settings.uiScale * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = settings.uiScale,
                    onValueChange = viewModel::setUiScale,
                    valueRange = 0.85f..1.6f,
                )
            }

            SettingsSection("Canvas")
            SwitchRow(
                title = stringResource(R.string.settingsscreen_transparency_checkerboard),
                subtitle = stringResource(R.string.settingsscreen_show_the_checker_pattern_behind),
                checked = settings.checkerboard,
                onChange = viewModel::setCheckerboard,
            )
            SwitchRow(
                title = stringResource(R.string.editorsheets_onion_skin),
                subtitle = stringResource(R.string.settingsscreen_show_previous_and_next_animation_frames),
                checked = settings.onionSkin,
                onChange = viewModel::setOnionSkin,
            )
            SwitchRow(
                title = stringResource(R.string.settingsscreen_symmetry_guides),
                subtitle = stringResource(R.string.settingsscreen_draw_the_active_symmetry_axis_on_the),
                checked = settings.showSymmetryGuides,
                onChange = viewModel::setSymmetryGuides,
            )
            SwitchRow(
                title = stringResource(R.string.settingsscreen_perspective_guides),
                subtitle = stringResource(R.string.settingsscreen_draw_vanishing_point_rays_on_the_canvas),
                checked = settings.showPerspectiveGuides,
                onChange = viewModel::setPerspectiveGuides,
            )
            SwitchRow(
                title = stringResource(R.string.editorsheets_snap_strokes_to_guides),
                subtitle = stringResource(R.string.settingsscreen_strokes_stick_to_the_nearest_guide_line),
                checked = settings.snapToGuides,
                onChange = viewModel::setSnapToGuides,
            )

            SettingsSection("Input")
            SwitchRow(
                title = stringResource(R.string.settingsscreen_stylus_only),
                subtitle = stringResource(R.string.settingsscreen_fingers_pan_and_zoom_only_a_stylus),
                checked = settings.stylusOnly,
                onChange = viewModel::setStylusOnly,
            )
            SwitchRow(
                title = "Haptics",
                subtitle = "Short vibration when QuickShape snaps or the touch-and-hold eyedropper starts",
                checked = settings.haptics,
                onChange = viewModel::setHaptics,
            )

            SettingsSection("Saving")
            SwitchRow(
                title = stringResource(R.string.settingsscreen_autosave),
                subtitle = stringResource(R.string.settingsscreen_keeps_a_recovery_copy_while_you_paint),
                checked = settings.autosaveEnabled,
                onChange = { viewModel.setAutosave(it, settings.autosaveIntervalMs) },
            )
            Column {
                Text(
                    "Autosave every ${settings.autosaveIntervalMs / 1000}s",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = settings.autosaveIntervalMs.toFloat(),
                    onValueChange = { viewModel.setAutosave(settings.autosaveEnabled, it.toLong()) },
                    valueRange = 5_000f..300_000f,
                )
            }

            SettingsSection("New documents")
            Text(stringResource(R.string.settingsscreen_default_canvas_preset), style = MaterialTheme.typography.labelMedium)
            var presetMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { presetMenu = true }) { Text(settings.defaultPresetName) }
                DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                    CanvasOperations.PRESETS.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.label) },
                            onClick = {
                                viewModel.setDefaultPreset(preset.name)
                                presetMenu = false
                            },
                        )
                    }
                }
            }

            SettingsSection("Gallery")
            Text(stringResource(R.string.settingsscreen_sort_by), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GallerySort.entries.forEach { sort ->
                    FilterChip(
                        selected = settings.gallerySort == sort,
                        onClick = { viewModel.setSort(sort) },
                        label = { Text(sort.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            SettingsSection("Privacy")
            PrivacyPolicyEntry()

            SettingsSection("Colour history")
            Text(
                "${settings.recentColors.size} recent colours stored",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = viewModel::clearRecentColors) { Text(stringResource(R.string.color_clear_recent)) }

            Text(
                stringResource(R.string.settingsscreen_artflow_has_no_account_or_developer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
