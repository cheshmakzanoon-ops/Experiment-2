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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.core.color.ColorHarmony
import com.artflow.studio.core.color.Palette

private const val CUSTOM = "Custom"

/** The palettes grouped by kind. The user's own can be renamed, extended, copied, made the default and deleted. */
@Composable
fun PaletteManager(
    palettes: List<Palette>,
    currentColor: Int,
    onColorSelected: (Int) -> Unit,
    actions: PaletteEdits,
) {
    // Null while closed; a null palette id means a new palette, without reserving a stored identity.
    var naming by remember { mutableStateOf<Pair<Long?, String>?>(null) }
    val grouped = remember(palettes) { palettes.groupBy { it.category } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { naming = null to "" }) { Text(stringResource(R.string.palettes_new)) }
        grouped.forEach { (category, entries) ->
            SectionTitle(category)
            entries.forEach { palette ->
                key(palette.category, palette.id, palette.name) {
                    PaletteBlock(palette, currentColor, onColorSelected, actions) { naming = palette.id to palette.name }
                }
            }
        }
    }
    naming?.let { (id, initial) ->
        PaletteNameDialog(initial, onDismiss = { naming = null }) { name ->
            if (id == null) actions.onCreate(name) else actions.onRename(id, name)
            naming = null
        }
    }
}

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
    actions: PaletteEdits,
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
            val isDefault = palette.name == actions.defaultName
            IconButton(onClick = { actions.onToggleDefault(palette.name) }) {
                Icon(
                    if (isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isDefault) "${palette.name} is the default palette" else "Set ${palette.name} as default",
                )
            }
            IconButton(onClick = { actions.onShare(palette) }) {
                Icon(Icons.Default.Share, contentDescription = "Share ${palette.name}")
            }
            PaletteMenu(palette, editable, actions, onRename)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(palette.colors) { index, entry ->
                SwatchTile(
                    entry,
                    ColorHarmony.distance(entry, currentColor) < NEAR,
                    editable,
                    onColorSelected,
                    palette,
                    index,
                    currentColor,
                    actions,
                )
            }
            if (editable) {
                item {
                    IconButton(onClick = { actions.onAddColor(palette.id, currentColor) }, modifier = Modifier.size(SWATCH)) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.palettes_add_current_colour, palette.name))
                    }
                }
            }
        }
    }
}

/** Built-in palettes can be copied; rename and deletion apply only to custom palettes. */
@Composable
private fun PaletteMenu(
    palette: Palette,
    editable: Boolean,
    actions: PaletteEdits,
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
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = choose { actions.onRemove(palette.id) })
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
    currentColor: Int,
    actions: PaletteEdits,
) {
    // Keep the actual displayed selection until the command is chosen. A flow emission while
    // the menu is open must not silently redirect an indexed edit to another swatch.
    var selected by remember { mutableStateOf<Pair<Palette, Int>?>(null) }
    val replaceLabel = stringResource(R.string.palettes_replace_swatch)
    val removeLabel = stringResource(R.string.palettes_remove_swatch)
    val description = stringResource(R.string.palettes_swatch, ColorHarmony.toHex(color))
    Box {
        Box(
            modifier =
                Modifier
                    .size(if (near) SWATCH_NEAR else SWATCH)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .semantics {
                        contentDescription = description
                        if (editable) {
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(replaceLabel) {
                                        actions.onReplaceColor(palette, index, currentColor)
                                        true
                                    },
                                    CustomAccessibilityAction(removeLabel) {
                                        actions.onRemoveColor(palette, index)
                                        true
                                    },
                                )
                        }
                    }.combinedClickable(
                        onClick = { onColorSelected(color) },
                        onLongClick = if (editable) ({ selected = palette to index }) else null,
                        onLongClickLabel = if (editable) "Edit swatch" else null,
                    ),
        )
        DropdownMenu(expanded = selected != null, onDismissRequest = { selected = null }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.palettes_replace_swatch)) },
                onClick = {
                    selected?.let { (displayed, position) -> actions.onReplaceColor(displayed, position, currentColor) }
                    selected = null
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.palettes_remove_swatch)) },
                onClick = {
                    selected?.let { (displayed, position) -> actions.onRemoveColor(displayed, position) }
                    selected = null
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
                label = { Text(stringResource(R.string.palettes_name)) },
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
