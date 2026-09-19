package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StudioBrushes

/** Draft settings stay local. Dismiss/Back never publishes a half-edited brush to the canvas. */
@Composable
fun BrushStudioDialog(
    initial: BrushParams,
    onApply: (BrushParams) -> Unit,
    onDismiss: () -> Unit,
) {
    val original = remember { initial }
    var draft by remember { mutableStateOf(original) }
    var tab by remember { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(12.dp).widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 760.dp).fillMaxHeight(0.94f),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 2.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Brush studio", style = MaterialTheme.typography.titleLarge)
                        Text(
                            StudioBrushes.presets.firstOrNull { it.parameters == draft }?.name ?: "Custom settings",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Cancel brush changes") }
                }
                TabRow(selectedTabIndex = tab) {
                    listOf("Library", "Settings", "Drawing pad").forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, maxLines = 1) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> StudioBrushLibrary(draft, { draft = it }, Modifier.fillMaxSize())
                        1 -> AdvancedBrushSettingsPanel(draft, { draft = it }, Modifier.fillMaxSize())
                        else -> BrushPracticePad(draft, strokes, { strokes = it }, Modifier.fillMaxSize())
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { draft = original }, enabled = draft != original) { Text("Restore initial") }
                    Button(onClick = {
                        applying = true
                        onApply(draft)
                        onDismiss()
                    }, enabled = !applying) { Text("Use brush") }
                }
            }
        }
    }
}

@Composable
fun StudioBrushLibrary(
    current: BrushParams,
    onSelect: (BrushParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    val matches = remember(query, category) { StudioBrushes.search(query, category) }
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(100) },
            label = { Text("Search brushes") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear brush search") }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioBrushes.categories.forEach { title ->
                FilterChip(selected = category == title, onClick = { category = title }, label = { Text(title) })
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (matches.isEmpty()) {
                item {
                    Text("No matching brushes. Clear the search or choose another category.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(matches, key = { it.id }) { preset ->
                val selected = current == preset.parameters
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border =
                        BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { onSelect(preset.parameters) },
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(preset.name, style = MaterialTheme.typography.titleSmall)
                        BrushSample(preset.parameters, Modifier.fillMaxWidth().height(42.dp))
                        Text(
                            preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
