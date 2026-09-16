@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.screens.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.GallerySort
import com.artflow.studio.presentation.ui.viewmodel.MainUiState
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The gallery: every saved artwork, with thumbnails, search, sorting and per-project actions.
 */
@Composable
fun GalleryScreen(
    onNavigateToCanvas: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val query by viewModel.query.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messageFlow.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (searchVisible) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text("Search artworks") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column {
                            Text("ArtFlow")
                            Text(
                                text = viewModel.storageSummary(),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { searchVisible = !searchVisible }) {
                        Icon(
                            imageVector = if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            GallerySort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.displayName) },
                                    onClick = { viewModel.setSort(sort); sortMenu = false },
                                    leadingIcon = {
                                        if (settings.gallerySort == sort) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = onOpenHelp) {
                        Icon(Icons.Default.HelpOutline, contentDescription = "Help")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewProjectDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New artwork") }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is MainUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is MainUiState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
                is MainUiState.Success -> if (state.projects.isEmpty()) {
                    EmptyGallery(query.isNotEmpty())
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 168.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.projects, key = { it.id }) { project ->
                            ProjectCard(
                                project = project,
                                onClick = { onNavigateToCanvas(project.id) },
                                onFavorite = { viewModel.toggleFavorite(project) },
                                onRename = { renameTarget = project },
                                onDuplicate = { viewModel.duplicate(project) },
                                onDelete = { deleteTarget = project }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewProjectDialog) {
        NewProjectDialog(
            defaultPresetName = settings.defaultPresetName,
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name, preset, width, height, dpi ->
                showNewProjectDialog = false
                viewModel.createProject(name, preset, width, height, dpi) { id ->
                    scope.launch { onNavigateToCanvas(id) }
                }
            }
        )
    }

    renameTarget?.let { project ->
        var text by remember(project.id) { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename artwork") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(project, text)
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${project.name}?") },
            text = { Text("The artwork and its layers are removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(project)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EmptyGallery(searching: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (searching) Icons.Default.SearchOff else Icons.Default.Palette,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (searching) "No artworks match that search" else "No artworks yet",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (searching) {
                "Try a different name."
            } else {
                "Tap New artwork to start a canvas. Everything is stored on this device."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menuVisible by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val thumbnail = project.thumbnailPath
                if (thumbnail != null && File(thumbnail).exists()) {
                    AsyncImage(
                        model = File(thumbnail),
                        contentDescription = project.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${project.width}×${project.height}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (project.isFavorite) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Favourite",
                        tint = Color(0xFFF2C037),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${project.layerCount} layers · ${formatDate(project.modifiedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Box {
                    IconButton(onClick = { menuVisible = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Project actions")
                    }
                    DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                        DropdownMenuItem(
                            text = { Text(if (project.isFavorite) "Remove favourite" else "Add favourite") },
                            onClick = { onFavorite(); menuVisible = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { onRename(); menuVisible = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = { onDuplicate(); menuVisible = false }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { onDelete(); menuVisible = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewProjectDialog(
    defaultPresetName: String,
    onDismiss: () -> Unit,
    onCreate: (String, CanvasOperations.Preset?, Int, Int, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedPreset by remember {
        mutableStateOf(CanvasOperations.presetByName(defaultPresetName) ?: CanvasOperations.PRESETS.first())
    }
    var customWidth by remember { mutableStateOf("2048") }
    var customHeight by remember { mutableStateOf("2048") }
    var customDpi by remember { mutableStateOf("132") }
    var useCustom by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New artwork") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !useCustom,
                        onClick = { useCustom = false },
                        label = { Text("Preset") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = useCustom,
                        onClick = { useCustom = true },
                        label = { Text("Custom") }
                    )
                }
                if (useCustom) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customWidth,
                            onValueChange = { customWidth = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Width") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customHeight,
                            onValueChange = { customHeight = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Height") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = customDpi,
                        onValueChange = { customDpi = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Dpi") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.heightIn(max = 220.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(CanvasOperations.PRESETS) { preset ->
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = { selectedPreset = preset },
                                label = {
                                    Text(
                                        preset.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }
                    Text(
                        "${selectedPreset.width}×${selectedPreset.height} px · ${selectedPreset.dpi} dpi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val width = if (useCustom) customWidth.toIntOrNull() ?: 2048 else selectedPreset.width
                val height = if (useCustom) customHeight.toIntOrNull() ?: 2048 else selectedPreset.height
                if (!CanvasOperations.isSizeSafe(width, height)) {
                    Text(
                        text = CanvasOperations.sizeWarning(width, height) ?: "That canvas is too large",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(
                    name.ifBlank { "Untitled artwork" },
                    if (useCustom) null else selectedPreset,
                    customWidth.toIntOrNull() ?: 2048,
                    customHeight.toIntOrNull() ?: 2048,
                    customDpi.toIntOrNull() ?: 132
                )
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

/** Small square used for the "storage" line; kept here for future use by the project details sheet. */
@Composable
internal fun ThumbnailPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
