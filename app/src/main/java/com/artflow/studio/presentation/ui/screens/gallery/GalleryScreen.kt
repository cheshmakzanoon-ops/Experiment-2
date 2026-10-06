@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.screens.gallery

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.ModelPackage
import com.artflow.studio.core.three.ObjParser
import com.artflow.studio.core.three.TextureAtlas
import com.artflow.studio.data.export.PendingImports
import com.artflow.studio.data.export.ProcreateImport
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.GallerySort
import com.artflow.studio.presentation.ui.viewmodel.MainUiState
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    viewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val query by viewModel.query.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var searchVisible by remember { mutableStateOf(false) }
    var openStack by rememberSaveable { mutableStateOf<String?>(null) }
    var stackTarget by remember { mutableStateOf<Project?>(null) }
    var preview by remember { mutableStateOf<Pair<List<Project>, Int>?>(null) }
    // Select mode: null while browsing, otherwise the chosen artworks.
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var batch by remember { mutableStateOf<GalleryBatch?>(null) }
    val drag = rememberGalleryDrag()
    val onDrop: (Long, GalleryDrop) -> Unit = { id, target -> dropArtwork(viewModel, uiState, id, target) }
    BackHandler(enabled = selection != null) { selection = null }
    BackHandler(enabled = openStack != null && selection == null) { openStack = null }
    LaunchedEffect(uiState, openStack) {
        val stack = openStack ?: return@LaunchedEffect
        val projects = (uiState as? MainUiState.Success)?.projects ?: return@LaunchedEffect
        // Leave a stack once its last artwork has moved out.
        if (projects.none { it.stack == stack }) openStack = null
    }
    val context = LocalContext.current
    val photoImport = rememberPhotoImport(viewModel, snackbarHostState, onNavigateToCanvas)
    val psdImport = rememberPsdImport(viewModel, snackbarHostState, onNavigateToCanvas)
    val procreateImport = rememberProcreateImport(viewModel, snackbarHostState, onNavigateToCanvas)
    val packageImport = rememberPackageImport(viewModel, snackbarHostState, onNavigateToCanvas)
    val modelImport = rememberModelImport(viewModel, snackbarHostState, onNavigateToCanvas)

    LaunchedEffect(Unit) {
        viewModel.messageFlow.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            val chosen = selection
            if (chosen != null) {
                SelectionTopBar(
                    chosen.size,
                    SelectionActions(
                        onStack = { batch = GalleryBatch.STACK },
                        onDuplicate = {
                            selectedProjects(uiState, chosen).forEach(viewModel::duplicate)
                            selection = null
                        },
                        onDelete = { batch = GalleryBatch.DELETE },
                        onDone = { selection = null },
                    ),
                )
            } else {
                TopAppBar(
                    title = {
                        if (searchVisible) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = viewModel::setQuery,
                                placeholder = { Text("Search artworks") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Column {
                                Text(openStack ?: "ArtFlow")
                                Text(
                                    text = viewModel.storageSummary(),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (openStack != null) {
                            IconButton(onClick = { openStack = null }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back to gallery")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchVisible = !searchVisible }) {
                            Icon(
                                imageVector = if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search",
                            )
                        }
                        SortMenu(settings.gallerySort, viewModel::setSort)
                        TextButton(onClick = { selection = emptySet() }) { Text("Select") }
                        ImportMenu(ImportChoices(photoImport, psdImport, procreateImport, packageImport, modelImport))
                        IconButton(onClick = onOpenHelp) {
                            Icon(Icons.Default.HelpOutline, contentDescription = "Help")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewProjectDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New artwork") },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when (val state = uiState) {
                is MainUiState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                is MainUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                }

                is MainUiState.Success -> {
                    if (state.projects.isEmpty()) {
                        EmptyGallery(query.isNotEmpty())
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 168.dp),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val layout = GalleryLayout.of(state.projects, openStack, searching = query.isNotEmpty())
                            items(layout.stacks, key = { "stack:${it.first}" }) { (name, members) ->
                                StackCard(name, members, Modifier.stackDropTarget(drag, name)) { openStack = name }
                            }
                            items(layout.projects, key = { it.id }) { project ->
                                val chosen = selection
                                ProjectCard(
                                    project = project,
                                    onClick = {
                                        if (chosen == null) {
                                            onNavigateToCanvas(project.id)
                                        } else {
                                            selection = if (project.id in chosen) chosen - project.id else chosen + project.id
                                        }
                                    },
                                    selected = chosen?.let { project.id in it },
                                    onFavorite = { viewModel.toggleFavorite(project) },
                                    onRename = { renameTarget = project },
                                    onDuplicate = { viewModel.duplicate(project) },
                                    onDelete = { deleteTarget = project },
                                    onStack = { stackTarget = project },
                                    onPreview = { preview = layout.projects to layout.projects.indexOf(project) },
                                    onShare = { scope.launch { shareProject(viewModel, project, context, snackbarHostState) } },
                                    modifier = if (chosen == null) Modifier.draggableArtwork(drag, project.id, onDrop) else Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewProjectDialog) {
        NewProjectDialog(
            defaultPresetName = settings.defaultPresetName,
            savedPresets = settings.savedCanvasPresets,
            presetEdits = PresetEdits(viewModel::saveCanvasPreset, viewModel::deleteCanvasPreset),
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name, preset, width, height, dpi, wideColor ->
                showNewProjectDialog = false
                val stack = openStack
                viewModel.createProject(name, preset, width, height, dpi) { id ->
                    if (wideColor) PendingImports.putProfile(id, ColorProfile.DISPLAY_P3)
                    // New artworks made inside a stack belong to it, as in Procreate.
                    if (stack != null) viewModel.moveToStack(id, stack)
                    scope.launch { onNavigateToCanvas(id) }
                }
            },
        )
    }

    preview?.let { (projects, index) ->
        GalleryPreview(projects, index, viewModel::previewImage) { preview = null }
    }

    batch?.let { action ->
        BatchDialog(action, selectedProjects(uiState, selection.orEmpty()), existingStacks(uiState), viewModel) { done ->
            batch = null
            if (done) selection = null
        }
    }
    stackTarget?.let { project ->
        StackDialog("Stack ${project.name}", project.stack, existingStacks(uiState), onDismiss = { stackTarget = null }) { stack ->
            viewModel.moveToStack(project.id, stack)
            stackTarget = null
        }
    }
    renameTarget?.let { project ->
        RenameDialog(project, onDismiss = { renameTarget = null }) { name ->
            viewModel.rename(project, name)
            renameTarget = null
        }
    }
    deleteTarget?.let { project ->
        DeleteDialog(project, onDismiss = { deleteTarget = null }) {
            viewModel.delete(project)
            deleteTarget = null
        }
    }
}

/** Opens a layered Photoshop document as a new artwork of the same size, keeping its layers. */
@Composable
private fun rememberPsdImport(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateToCanvas: (Long) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val opened =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)?.use { it.readBytes() })
                            val document = requireNotNull(PsdCodec.read(bytes))
                            bytes to document
                        }.getOrNull()
                    }
                if (opened == null) {
                    snackbarHostState.showSnackbar("That file is not a Photoshop document ArtFlow can read")
                    return@launch
                }
                val (bytes, document) = opened
                viewModel.createProject("Imported PSD", null, document.width, document.height, document.dpi) { id ->
                    PendingImports.putPsd(id, bytes)
                    scope.launch { onNavigateToCanvas(id) }
                }
            }
        }
    return { runCatching { launcher.launch(arrayOf("image/vnd.adobe.photoshop", "application/octet-stream")) } }
}

/**
 * Opens a Procreate document as a new artwork of the same size, with its layers, groups, blend
 * modes, masks and background colour.
 */
@Composable
private fun rememberProcreateImport(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateToCanvas: (Long) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val opened =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            requireNotNull(context.contentResolver.openInputStream(uri)) { "That file could not be opened" }.use { input ->
                                ProcreateImport.open(input, context.cacheDir)
                            }
                        }
                    }
                val document =
                    opened.getOrElse { failure ->
                        // Our own checks explain themselves; anything else means a damaged or unusual file.
                        val explained = failure is IllegalArgumentException && failure !is NumberFormatException
                        snackbarHostState.showSnackbar(
                            failure.message?.takeIf { explained } ?: "That file is not a Procreate document ArtFlow can read",
                        )
                        return@launch
                    }
                val size = document.document
                if (!CanvasOperations.isSizeSafe(size.width, size.height)) {
                    document.close()
                    snackbarHostState.showSnackbar("This Procreate canvas is too large for this device")
                    return@launch
                }
                val name = size.name?.takeIf { it.isNotBlank() } ?: "Imported Procreate"
                val dpi = size.dpi.coerceIn(CanvasOperations.MIN_DPI, CanvasOperations.MAX_DPI)
                viewModel.createProject(name, null, size.width, size.height, dpi) { id ->
                    PendingImports.putProcreate(id, document)
                    scope.launch { onNavigateToCanvas(id) }
                }
            }
        }
    return { runCatching { launcher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) } }
}

/** What the gallery's Import menu can bring in, each as a new artwork. */
private class ImportChoices(
    val photo: () -> Unit,
    val psd: () -> Unit,
    val procreate: () -> Unit,
    val artwork: () -> Unit,
    val model: () -> Unit,
)

/** Opens an OBJ model as a new artwork that is its texture, for 3D painting. */
@Composable
private fun rememberModelImport(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateToCanvas: (Long) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                // An OBJ file, or a zip with the OBJ, its materials and textures.
                val read =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes =
                                requireNotNull(
                                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() },
                                )
                            val contents = ModelPackage.read(bytes)
                            ObjParser.parse(contents.objText)
                            // Several textured materials share one artwork, each in its own cell.
                            val decoded =
                                contents.textures
                                    .mapNotNull { (name, image) ->
                                        BitmapPixelBridge.fromEncodedBytes(image)?.let {
                                            name to
                                                it
                                        }
                                    }.toMap()
                            TextureAtlas.pack(contents.objText, decoded, MAX_MODEL_TEXTURE)?.takeIf { decoded.isNotEmpty() }?.let {
                                it.objText to
                                    it.image
                            }
                                ?: (contents.objText to contents.texture?.let(BitmapPixelBridge::fromEncodedBytes)?.let(::fittedTexture))
                        }
                    }
                read.onFailure { failure ->
                    // Our own checks explain themselves; anything else means a damaged or unusual file.
                    val explained =
                        (failure is IllegalArgumentException || failure is IllegalStateException) && failure !is NumberFormatException
                    snackbarHostState.showSnackbar(failure.message?.takeIf { explained } ?: "That 3D model could not be read")
                }
                val (model, texture) = read.getOrNull() ?: return@launch
                val width = texture?.width ?: MODEL_TEXTURE
                val height = texture?.height ?: MODEL_TEXTURE
                viewModel.createProject("3D model", null, width, height, MODEL_DPI) { id ->
                    PendingImports.putModel(id, model)
                    // The model's own texture becomes the first layer to paint over.
                    texture?.let { PendingImports.put(id, it) }
                    scope.launch { onNavigateToCanvas(id) }
                }
            }
        }
    return { runCatching { launcher.launch(arrayOf("*/*")) } }
}

private const val MODEL_TEXTURE = 2048
private const val MAX_MODEL_TEXTURE = 4096

/** A model texture scaled down, if needed, so its longest side fits the canvas limit. */
private fun fittedTexture(texture: PixelBuffer): PixelBuffer {
    val scale = MAX_MODEL_TEXTURE.toFloat() / maxOf(texture.width, texture.height)
    if (scale >= 1f) return texture
    return texture.scaled((texture.width * scale).toInt().coerceAtLeast(1), (texture.height * scale).toInt().coerceAtLeast(1))
}

private const val MODEL_DPI = 132

/** Opens an `.artflow` file as a new artwork with all its layers, frames and settings. */
@Composable
private fun rememberPackageImport(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateToCanvas: (Long) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val bytes =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                input.readBytes().also { require(it.size <= MAX_PACKAGE_BYTES) }
                            }
                        }.getOrNull()
                    }
                if (bytes == null) {
                    snackbarHostState.showSnackbar("That file could not be read, or it is too large")
                    return@launch
                }
                viewModel.importPackage(bytes) { id -> scope.launch { onNavigateToCanvas(id) } }
            }
        }
    return { runCatching { launcher.launch(arrayOf("application/octet-stream", "application/zip")) } }
}

private const val MAX_PACKAGE_BYTES = 512 * 1024 * 1024

/** The gallery's Import menu: a photo or a layered Photoshop file, each as a new artwork. */
@Composable
private fun ImportMenu(choices: ImportChoices) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Import as a new artwork")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Photo") }, onClick = {
                open = false
                choices.photo()
            })
            DropdownMenuItem(text = { Text("Photoshop file (PSD)") }, onClick = {
                open = false
                choices.psd()
            })
            DropdownMenuItem(text = { Text("Procreate document (.procreate)") }, onClick = {
                open = false
                choices.procreate()
            })
            DropdownMenuItem(text = { Text("ArtFlow artwork (.artflow)") }, onClick = {
                open = false
                choices.artwork()
            })
            DropdownMenuItem(text = { Text("3D model (OBJ, glTF, USDZ)") }, onClick = {
                open = false
                choices.model()
            })
        }
    }
}

/** Opens a photo from the device as a new canvas of the same size. */
@Composable
private fun rememberPhotoImport(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateToCanvas: (Long) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val image =
                    withContext(Dispatchers.IO) {
                        runCatching { BitmapPixelBridge.decodeUri(context.contentResolver, uri) }.getOrNull()
                    }
                if (image == null) {
                    snackbarHostState.showSnackbar("That image could not be opened")
                    return@launch
                }
                viewModel.createProject("Imported photo", null, image.width, image.height, 72) { id ->
                    PendingImports.put(id, image)
                    scope.launch { onNavigateToCanvas(id) }
                }
            }
        }
    return { runCatching { launcher.launch("image/*") } }
}

@Composable
private fun SortMenu(
    current: GallerySort,
    onSort: (GallerySort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.Sort, contentDescription = "Sort") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GallerySort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.displayName) },
                    onClick = {
                        onSort(sort)
                        open = false
                    },
                    leadingIcon = { if (current == sort) Icon(Icons.Default.Check, contentDescription = null) },
                )
            }
        }
    }
}

private suspend fun shareProject(
    viewModel: MainViewModel,
    project: Project,
    context: android.content.Context,
    snackbar: SnackbarHostState,
) {
    val file = viewModel.shareablePng(project)
    if (file == null) {
        snackbar.showSnackbar("Open the artwork once so it can be shared")
    } else {
        runCatching { sharePng(context, file) }
    }
}

/** Drag-to-stack: onto a stack moves the artwork in; onto another artwork makes a new stack of both. */
private fun dropArtwork(
    viewModel: MainViewModel,
    state: MainUiState,
    projectId: Long,
    target: GalleryDrop,
) {
    when (target) {
        is GalleryDrop.OnStack -> {
            viewModel.moveToStack(projectId, target.name)
        }

        is GalleryDrop.OnProject -> {
            val other = (state as? MainUiState.Success)?.projects?.firstOrNull { it.id == target.projectId } ?: return
            val name = other.stack ?: newStackName(existingStacks(state))
            viewModel.moveToStack(other.id, name)
            viewModel.moveToStack(projectId, name)
        }
    }
}

private fun selectedProjects(
    state: MainUiState,
    ids: Set<Long>,
): List<Project> = (state as? MainUiState.Success)?.projects.orEmpty().filter { it.id in ids }

private fun existingStacks(state: MainUiState): List<String> =
    (state as? MainUiState.Success)
        ?.projects
        ?.mapNotNull { it.stack }
        ?.distinct()
        ?.sorted()
        .orEmpty()

@Composable
private fun RenameDialog(
    project: Project,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var text by remember(project.id) { mutableStateOf(project.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename artwork") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(text) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteDialog(
    project: Project,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${project.name}?") },
        text = { Text("The artwork and its layers are removed from this device. This cannot be undone.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyGallery(searching: Boolean) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (searching) Icons.Default.SearchOff else Icons.Default.Palette,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (searching) "No artworks match that search" else "No artworks yet",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text =
                if (searching) {
                    "Try a different name."
                } else {
                    "Tap New artwork to start a canvas. Everything is stored on this device."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onDelete: () -> Unit,
    onStack: () -> Unit,
    onPreview: () -> Unit,
    onShare: () -> Unit,
    selected: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    var menuVisible by remember { mutableStateOf(false) }
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.35f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val thumbnail = project.thumbnailPath
                if (thumbnail != null && File(thumbnail).exists()) {
                    AsyncImage(
                        model = File(thumbnail),
                        contentDescription = project.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${project.width}×${project.height}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (project.isFavorite) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Favourite",
                        tint = Color(0xFFF2C037),
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp),
                    )
                }
                selected?.let { SelectionMark(it, Modifier.align(Alignment.TopEnd)) }
            }

            Row(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${project.layerCount} layers · ${formatDate(project.modifiedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Box {
                    IconButton(onClick = { menuVisible = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Project actions")
                    }
                    DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                        DropdownMenuItem(
                            text = { Text(if (project.isFavorite) "Remove favourite" else "Add favourite") },
                            onClick = {
                                onFavorite()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                onRename()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = {
                                onDuplicate()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Preview") },
                            onClick = {
                                onPreview()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = {
                                onShare()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (project.stack == null) "Move to stack…" else "Stack…") },
                            onClick = {
                                onStack()
                                menuVisible = false
                            },
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                onDelete()
                                menuVisible = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Saving the custom size as a named canvas preset, and deleting a saved one. */
private class PresetEdits(
    val onSave: (CanvasOperations.Preset) -> Unit,
    val onDelete: (String) -> Unit,
)

@Composable
private fun NewProjectDialog(
    defaultPresetName: String,
    savedPresets: List<CanvasOperations.Preset>,
    presetEdits: PresetEdits,
    onDismiss: () -> Unit,
    onCreate: (String, CanvasOperations.Preset?, Int, Int, Int, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedPreset by remember {
        mutableStateOf(CanvasOperations.presetByName(defaultPresetName, savedPresets) ?: CanvasOperations.PRESETS.first())
    }
    var customWidth by remember { mutableStateOf("2048") }
    var customHeight by remember { mutableStateOf("2048") }
    var customDpi by remember { mutableStateOf("132") }
    var useCustom by remember { mutableStateOf(false) }
    var wideColor by remember { mutableStateOf(false) }

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
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !useCustom,
                        onClick = { useCustom = false },
                        label = { Text("Preset") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = useCustom,
                        onClick = { useCustom = true },
                        label = { Text("Custom") },
                    )
                }
                if (useCustom) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customWidth,
                            onValueChange = { customWidth = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Width") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = customHeight,
                            onValueChange = { customHeight = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Height") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = customDpi,
                        onValueChange = { customDpi = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Dpi") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val custom =
                        CanvasOperations.Preset(
                            CanvasOperations.presetName(name).ifEmpty { "${customWidth}×$customHeight" },
                            customWidth.toIntOrNull() ?: 0,
                            customHeight.toIntOrNull() ?: 0,
                            customDpi.toIntOrNull() ?: 0,
                        )
                    TextButton(
                        onClick = {
                            presetEdits.onSave(custom)
                            selectedPreset = custom
                            useCustom = false
                        },
                        enabled = CanvasOperations.isValidPreset(custom),
                    ) { Text("Save as preset \"${custom.name}\"") }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.heightIn(max = 220.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(savedPresets + CanvasOperations.PRESETS) { preset ->
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = { selectedPreset = preset },
                                label = {
                                    Text(
                                        preset.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                    Text(
                        "${selectedPreset.width}×${selectedPreset.height} px · ${selectedPreset.dpi} dpi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selectedPreset in savedPresets) {
                        TextButton(onClick = {
                            presetEdits.onDelete(selectedPreset.name)
                            selectedPreset = CanvasOperations.PRESETS.first()
                        }) { Text("Delete preset \"${selectedPreset.name}\"") }
                    }
                }
                val width = if (useCustom) customWidth.toIntOrNull() ?: 2048 else selectedPreset.width
                val height = if (useCustom) customHeight.toIntOrNull() ?: 2048 else selectedPreset.height
                if (!CanvasOperations.isSizeSafe(width, height)) {
                    Text(
                        text = CanvasOperations.sizeWarning(width, height) ?: "That canvas is too large",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Display P3 colour profile", modifier = Modifier.weight(1f))
                    Switch(checked = wideColor, onCheckedChange = { wideColor = it })
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
                    customDpi.toIntOrNull() ?: 132,
                    wideColor,
                )
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
