package com.artflow.studio.presentation.ui.screens.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.artflow.studio.R
import com.artflow.studio.domain.model.Project
import java.io.File

/** What the gallery grid shows: stacks first, then loose artworks (or a stack's contents, or search hits). */
internal data class GalleryLayout(
    val stacks: List<Pair<String, List<Project>>>,
    val projects: List<Project>,
) {
    companion object {
        fun of(
            projects: List<Project>,
            openStack: String?,
            searching: Boolean,
        ): GalleryLayout =
            when {
                // Search looks through every artwork, stacked or not.
                searching -> GalleryLayout(emptyList(), projects)
                openStack != null -> GalleryLayout(emptyList(), projects.filter { it.stack == openStack })
                else ->
                    GalleryLayout(
                        projects
                            .filter { it.stack != null }
                            .groupBy { it.stack.orEmpty() }
                            .toList(),
                        projects.filter { it.stack == null },
                    )
            }
    }
}

/** What a stack card's menu does; renaming and merging open a dialog, the rest act at once. */
internal class StackMenu(
    val onRename: () -> Unit,
    val onMerge: () -> Unit,
    val onDissolve: () -> Unit,
    val onShare: () -> Unit,
)

/** A Procreate-style stack: up to four previews of its artworks, its name and how many it holds. */
@Composable
internal fun StackCard(
    name: String,
    members: List<Project>,
    menu: StackMenu,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val stackDescription = stringResource(R.string.gallerylayout_stack_name, name)
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .semantics { contentDescription = stackDescription },
    ) {
        Box {
            Column {
                StackPreviews(members)
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (members.size == 1) "1 artwork" else "${members.size} artworks",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StackMenuButton(name, menu, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Up to four thumbnails of a stack's artworks, in a two-by-two grid. */
@Composable
private fun StackPreviews(members: List<Project>) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.35f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val previews = members.mapNotNull { it.thumbnailPath?.let(::File)?.takeIf(File::exists) }.take(4)
        if (previews.isEmpty()) {
            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.align(Alignment.Center))
        } else {
            Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                previews.chunked(2).forEach { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { file ->
                            AsyncImage(
                                model = file,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The corner button of a stack card; its menu renames, merges, dissolves or shares the stack. */
@Composable
private fun StackMenuButton(
    name: String,
    menu: StackMenu,
    modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }

    fun choose(command: () -> Unit): () -> Unit =
        {
            open = false
            command()
        }
    Box(modifier.padding(4.dp)) {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), CircleShape),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.gallery_stack_commands, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_stack_rename)) }, onClick = choose(menu.onRename))
            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_stack_merge)) }, onClick = choose(menu.onMerge))
            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_stack_dissolve)) }, onClick = choose(menu.onDissolve))
            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_stack_share)) }, onClick = choose(menu.onShare))
        }
    }
}

/** Moves an artwork into an existing or new stack, or back to the main gallery. */
@Composable
internal fun StackDialog(
    title: String,
    current: String?,
    stacks: List<String>,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
) {
    var name by remember(title) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(stacks.filter { it != current }) { stack ->
                        TextButton(onClick = { onMove(stack) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stack, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text(stringResource(R.string.gallerylayout_new_stack)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (current != null) {
                    TextButton(onClick = { onMove(null) }) { Text(stringResource(R.string.gallerylayout_remove_from_stack)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onMove(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.gallerylayout_create_stack)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
