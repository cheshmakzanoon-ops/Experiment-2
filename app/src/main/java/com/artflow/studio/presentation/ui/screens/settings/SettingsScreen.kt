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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            Text("Theme", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            Text("Accent", style = MaterialTheme.typography.labelMedium)
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
                title = "High contrast",
                subtitle = "Stronger outlines and clearer text contrast",
                checked = settings.highContrast,
                onChange = viewModel::setHighContrast,
            )
            SwitchRow(
                title = "Reduce motion",
                subtitle = "Shortens panel and canvas animations",
                checked = settings.reduceMotion,
                onChange = viewModel::setReduceMotion,
            )
            SwitchRow(
                title = "Larger touch targets",
                subtitle = "Bigger toolbar buttons for easier tapping",
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
                title = "Transparency checkerboard",
                subtitle = "Show the checker pattern behind transparent pixels",
                checked = settings.checkerboard,
                onChange = viewModel::setCheckerboard,
            )
            SwitchRow(
                title = "Onion skin",
                subtitle = "Show previous and next animation frames as ghosts",
                checked = settings.onionSkin,
                onChange = viewModel::setOnionSkin,
            )
            SwitchRow(
                title = "Symmetry guides",
                subtitle = "Draw the active symmetry axis on the canvas",
                checked = settings.showSymmetryGuides,
                onChange = viewModel::setSymmetryGuides,
            )
            SwitchRow(
                title = "Perspective guides",
                subtitle = "Draw vanishing-point rays on the canvas",
                checked = settings.showPerspectiveGuides,
                onChange = viewModel::setPerspectiveGuides,
            )
            SwitchRow(
                title = "Snap strokes to guides",
                subtitle = "Strokes stick to the nearest guide line",
                checked = settings.snapToGuides,
                onChange = viewModel::setSnapToGuides,
            )

            SettingsSection("Input")
            SwitchRow(
                title = "Stylus only",
                subtitle = "Fingers pan and zoom; only a stylus paints (recommended for palm rejection)",
                checked = settings.stylusOnly,
                onChange = viewModel::setStylusOnly,
            )
            SwitchRow(
                title = "Haptics",
                subtitle = "Short vibration when a tool commits",
                checked = settings.haptics,
                onChange = viewModel::setHaptics,
            )

            SettingsSection("Saving")
            SwitchRow(
                title = "Autosave",
                subtitle = "Keeps a recovery copy while you paint",
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
            Text("Default canvas preset", style = MaterialTheme.typography.labelMedium)
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
            Text("Sort by", style = MaterialTheme.typography.labelMedium)
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
            OutlinedButton(onClick = viewModel::clearRecentColors) { Text("Clear recent colours") }

            Text(
                "ArtFlow has no account or developer uploads. Android backup and exports follow your choices and device settings.",
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
