@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.screens.gallery

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.artflow.studio.R
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.GallerySort
import com.artflow.studio.presentation.ui.IncomingFiles
import com.artflow.studio.presentation.ui.viewmodel.BrushLibraryViewModel
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
    viewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val query by viewModel.query.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var openStack by rememberSaveable { mutableStateOf<String?>(null) }
    // Select mode: null while browsing, otherwise the chosen artworks.
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    val dialogs = remember { GalleryDialogs() }
    val drag = rememberGalleryDrag()
    val hint = MaterialTheme.colorScheme.primary
    val gridState = rememberLazyGridState()
    AutoScrollWhileDragging(drag, gridState)
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
    val brushLibrary: BrushLibraryViewModel = hiltViewModel()
    val imports =
        remember(context, viewModel, onNavigateToCanvas) {
            GalleryImports(
                context = context,
                scope = scope,
                viewModel = viewModel,
                say = { snackbarHostState.showSnackbar(it) },
                addBrushes = brushLibrary::importWhenReady,
                openArtwork = onNavigateToCanvas,
            )
        }
    val photoImport = rememberPhotoPicker(imports::photo)
    val psdImport = rememberDocumentPicker(arrayOf("image/vnd.adobe.photoshop", "application/octet-stream"), imports::psd)
    val procreateImport = rememberDocumentPicker(arrayOf("application/octet-stream", "application/zip", "*/*"), imports::procreate)
    val packageImport = rememberDocumentPicker(arrayOf("application/octet-stream", "application/zip"), imports::artwork)
    val modelImport = rememberDocumentPicker(arrayOf("*/*"), imports::model)
    // Files other apps open or share with ArtFlow are imported here, as the pickers' files are.
    LaunchedEffect(imports) { IncomingFiles.files.collect { imports.open(it) } }

    LaunchedEffect(Unit) {
        viewModel.messageFlow.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            val chosen = selection
            if (chosen != null) {
                val picked = selectedProjects(uiState, chosen)
                SelectionTopBar(
                    chosen.size,
                    SelectionActions(
                        onStack = { dialogs.batch = GalleryBatch.STACK },
                        onDuplicate = {
                            picked.forEach(viewModel::duplicate)
                            selection = null
                        },
                        onDelete = { dialogs.batch = GalleryBatch.DELETE },
                        onDone = { selection = null },
                        onSelectAll = { selection = shownProjects(uiState, openStack, query).mapTo(HashSet()) { it.id } },
                        onShare = { scope.launch { shareProjects(viewModel, picked, context, snackbarHostState) } },
                        onFavorite = { viewModel.setFavorite(picked, !picked.all(Project::isFavorite)) },
                        allFavourite = picked.isNotEmpty() && picked.all(Project::isFavorite),
                    ),
                )
            } else {
                TopAppBar(
                    title = {
                        if (searchVisible) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = viewModel::setQuery,
                                placeholder = { Text(stringResource(R.string.galleryscreen_search_artworks)) },
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
                                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.galleryscreen_back_to_gallery))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchVisible = !searchVisible }) {
                            Icon(
                                imageVector = if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = stringResource(R.string.galleryscreen_search),
                            )
                        }
                        SortMenu(settings.gallerySort, viewModel::setSort)
                        TextButton(onClick = { selection = emptySet() }) { Text(stringResource(R.string.layer_select)) }
                        ImportMenu(ImportChoices(photoImport, psdImport, procreateImport, packageImport, modelImport))
                        IconButton(onClick = onOpenHelp) {
                            Icon(Icons.Default.HelpOutline, contentDescription = stringResource(R.string.galleryscreen_help))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewProjectDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.galleryscreen_new_artwork)) },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .onGloballyPositioned { drag.viewport = it.boundsInRoot() },
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
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 168.dp),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val layout = GalleryLayout.of(state.projects, openStack, searching = query.isNotEmpty())
                            items(layout.stacks, key = { "stack:${it.first}" }) { (name, members) ->
                                TrackStack(drag, name)
                                val menu =
                                    StackMenu(
                                        onRename = { dialogs.stackAction = StackAction(name, StackCommand.RENAME) },
                                        onMerge = { dialogs.stackAction = StackAction(name, StackCommand.MERGE) },
                                        onDissolve = { viewModel.dissolveStack(name) },
                                        onShare = { scope.launch { shareProjects(viewModel, members, context, snackbarHostState) } },
                                    )
                                StackCard(name, members, menu, Modifier.stackDropTarget(drag, name, hint)) { openStack = name }
                            }
                            items(layout.projects, key = { it.id }) { project ->
                                val chosen = selection
                                TrackCard(drag, project.id)
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
                                    onRename = { dialogs.rename = project },
                                    onDuplicate = { viewModel.duplicate(project) },
                                    onDelete = { dialogs.delete = project },
                                    onStack = { dialogs.stack = project },
                                    onPreview = { dialogs.preview = layout.projects to layout.projects.indexOf(project) },
                                    onShare = { scope.launch { shareProjects(viewModel, listOf(project), context, snackbarHostState) } },
                                    modifier = if (chosen == null) Modifier.draggableArtwork(drag, project.id, hint, onDrop) else Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewProjectDialog) {
        NewArtworkHost(viewModel, settings, openStack, onDismiss = { showNewProjectDialog = false }) { id ->
            scope.launch { onNavigateToCanvas(id) }
        }
    }

    GalleryDialogHost(dialogs, uiState, selection, viewModel) { selection = null }
}

/** What the gallery's Import menu can bring in, each as a new artwork. */
private class ImportChoices(
    val photo: () -> Unit,
    val psd: () -> Unit,
    val procreate: () -> Unit,
    val artwork: () -> Unit,
    val model: () -> Unit,
)

/** The gallery's Import menu: a photo or a layered Photoshop file, each as a new artwork. */
@Composable
private fun ImportMenu(choices: ImportChoices) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.galleryscreen_import_as_a_new_artwork))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.galleryscreen_photo)) }, onClick = {
                open = false
                choices.photo()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.galleryscreen_photoshop_file_psd)) }, onClick = {
                open = false
                choices.psd()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.galleryscreen_procreate_document_procreate)) }, onClick = {
                open = false
                choices.procreate()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.galleryscreen_artflow_artwork_artflow)) }, onClick = {
                open = false
                choices.artwork()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.galleryscreen_3d_model_obj_gltf_usdz)) }, onClick = {
                open = false
                choices.model()
            })
        }
    }
}

/** A document picker that hands the chosen file to [import]; returns the function that opens it. */
@Composable
private fun rememberDocumentPicker(
    types: Array<String>,
    import: (Uri) -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) import(uri) }
    return { runCatching { launcher.launch(types) } }
}

/** The photo picker, which lists the device's photo apps first. */
@Composable
private fun rememberPhotoPicker(import: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) import(uri) }
    return { runCatching { launcher.launch("image/*") } }
}

@Composable
private fun SortMenu(
    current: GallerySort,
    onSort: (GallerySort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.Sort, contentDescription = stringResource(R.string.galleryscreen_sort)) }
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

/**
 * Drag in the gallery: onto a stack moves the artwork in; onto another artwork makes a new stack of both; beside an artwork puts it
 * there, which keeps the gallery in that order.
 */
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

        is GalleryDrop.Beside -> viewModel.reorder(projectId, target.projectId, target.after)

        is GalleryDrop.OnProject -> {
            val other = (state as? MainUiState.Success)?.projects?.firstOrNull { it.id == target.projectId } ?: return
            val name = other.stack ?: newStackName(existingStacks(state))
            viewModel.moveToStack(other.id, name)
            viewModel.moveToStack(projectId, name)
        }
    }
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
                        contentDescription = stringResource(R.string.galleryscreen_favourite),
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
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.galleryscreen_project_actions))
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
                            text = { Text(stringResource(R.string.layer_rename)) },
                            onClick = {
                                onRename()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.duplicate)) },
                            onClick = {
                                onDuplicate()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.galleryscreen_preview)) },
                            onClick = {
                                onPreview()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_share)) },
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
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
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
