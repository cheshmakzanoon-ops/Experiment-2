package com.artflow.studio.presentation.ui.components.color

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.core.color.ColorHarmony
import com.artflow.studio.core.color.Palette

/** Everything the Palettes tab can do with palettes and their swatches. */
data class PaletteActions(
    val onCreate: (String) -> Unit,
    val onRename: (Long, String) -> Unit,
    val onDuplicate: (Palette) -> Unit,
    val onSetDefault: (Long) -> Unit,
    val onDelete: (Long) -> Unit,
    val onAddSwatch: (Long) -> Unit,
    val onReplaceSwatch: (Long, Int) -> Unit,
    val onRemoveSwatch: (Long, Int) -> Unit,
    /** Shares a palette as a Procreate `.swatches` file; null hides the command. */
    val onShare: ((Palette) -> Unit)? = null,
)

private const val CUSTOM = "Custom"

/** The palettes grouped by kind. The user's own can be renamed, extended, copied, made the default and deleted. */
@Composable
fun PaletteManager(
    palettes: List<Palette>,
    currentColor: Int,
    onColorSelected: (Int) -> Unit,
    actions: PaletteActions,
) {
    // Null while closed; a palette id when renaming, and -1 when creating a new one.
    var naming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val grouped = remember(palettes) { palettes.groupBy { it.category } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { naming = NEW_PALETTE to "" }) { Text(stringResource(R.string.palettes_new)) }
        grouped.forEach { (category, entries) ->
            SectionTitle(category)
            entries.forEach { palette ->
                PaletteBlock(palette, currentColor, onColorSelected, actions) { naming = palette.id to palette.name }
            }
        }
    }
    naming?.let { (id, initial) ->
        PaletteNameDialog(initial, onDismiss = { naming = null }) { name ->
            if (id == NEW_PALETTE) actions.onCreate(name) else actions.onRename(id, name)
            naming = null
        }
    }
}

private const val NEW_PALETTE = -1L

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PaletteBlock(
    palette: Palette,
    currentColor: Int,
    onColorSelected: (Int) -> Unit,
    actions: PaletteActions,
    onRename: () -> Unit,
) {
    val editable = palette.category == CUSTOM
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(palette.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            PaletteMenu(palette, editable, actions, onRename)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(palette.colors) { index, entry ->
                SwatchTile(entry, ColorHarmony.distance(entry, currentColor) < NEAR, editable, onColorSelected, palette, index, actions)
            }
            if (editable) {
                item {
                    IconButton(onClick = { actions.onAddSwatch(palette.id) }, modifier = Modifier.size(SWATCH)) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.palettes_add_current_colour, palette.name))
                    }
                }
            }
        }
    }
}

/** The command menu of one palette: the user's own palettes get all of it, the built-in ones only copy and share. */
@Composable
private fun PaletteMenu(
    palette: Palette,
    editable: Boolean,
    actions: PaletteActions,
    onRename: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    fun choose(command: () -> Unit): () -> Unit =
        {
            open = false
            command()
        }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.palettes_commands, palette.name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (editable) {
                DropdownMenuItem(text = { Text(stringResource(R.string.palettes_rename)) }, onClick = choose(onRename))
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = choose { actions.onDuplicate(palette) })
            if (editable) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.palettes_set_default)) },
                    onClick = choose { actions.onSetDefault(palette.id) },
                )
            }
            actions.onShare?.let { share ->
                DropdownMenuItem(text = { Text(stringResource(R.string.export_share)) }, onClick = choose { share(palette) })
            }
            if (editable) {
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = choose { actions.onDelete(palette.id) })
            }
        }
    }
}

/** One swatch: tap to use the colour; in the user's own palettes, hold for replace and remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwatchTile(
    color: Int,
    near: Boolean,
    editable: Boolean,
    onColorSelected: (Int) -> Unit,
    palette: Palette,
    index: Int,
    actions: PaletteActions,
) {
    var menu by remember { mutableStateOf(false) }
    val description = stringResource(R.string.palettes_swatch, ColorHarmony.toHex(color))
    Box {
        Box(
            modifier =
                Modifier
                    .size(if (near) SWATCH_NEAR else SWATCH)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .semantics { contentDescription = description }
                    .combinedClickable(onClick = { onColorSelected(color) }, onLongClick = { if (editable) menu = true }),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.palettes_replace_swatch)) },
                onClick = {
                    menu = false
                    actions.onReplaceSwatch(palette.id, index)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.palettes_remove_swatch)) },
                onClick = {
                    menu = false
                    actions.onRemoveSwatch(palette.id, index)
                },
            )
        }
    }
}

@Composable
private fun PaletteNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onName: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val ready = text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.palettes_name)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_NAME) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ready) onName(text) }),
            )
        },
        confirmButton = { TextButton(onClick = { onName(text) }, enabled = ready) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val MAX_NAME = 40
private const val NEAR = 24f
private val SWATCH = 32.dp
private val SWATCH_NEAR = 40.dp
