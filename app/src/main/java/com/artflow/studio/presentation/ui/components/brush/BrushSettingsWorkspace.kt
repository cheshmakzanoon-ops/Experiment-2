package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.presentation.ui.theme.LocalArtFlowFlags

/** Persistent navigation belongs to the dialog, outside layout-mode and tab-specific subtrees. */
@Composable
fun BrushSettingsWorkspace(
    parameters: BrushParams,
    onChange: (BrushParams) -> Unit,
    attribute: BrushAttribute,
    onAttributeChange: (BrushAttribute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val touchSize = if (LocalArtFlowFlags.current.largeTouchTargets) 56.dp else 48.dp
    BoxWithConstraints(modifier) {
        if (maxWidth >= 480.dp) {
            Row(Modifier.fillMaxSize().testTag("brush-attribute-sidebar")) {
                LazyColumn(Modifier.width(148.dp).fillMaxHeight().selectableGroup()) {
                    items(BrushAttribute.entries, key = { it.name }) { item ->
                        Surface(
                            color =
                                if (attribute == item) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                        ) {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = touchSize)
                                        .selectable(attribute == item, role = Role.Tab) { onAttributeChange(item) }
                                        .padding(12.dp),
                            )
                        }
                    }
                }
                VerticalDivider(Modifier.fillMaxHeight())
                AttributePanel(parameters, onChange, attribute, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .selectableGroup()
                        .padding(horizontal = 12.dp)
                        .testTag("brush-attribute-strip"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BrushAttribute.entries.forEach { item ->
                        FilterChip(
                            selected = attribute == item,
                            onClick = { onAttributeChange(item) },
                            label = { Text(item.label) },
                            modifier = Modifier.heightIn(min = touchSize),
                        )
                    }
                }
                AttributePanel(parameters, onChange, attribute, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AttributePanel(
    parameters: BrushParams,
    onChange: (BrushParams) -> Unit,
    attribute: BrushAttribute,
    modifier: Modifier,
) {
    // A previously deep scroll cannot make a newly selected, short section appear blank.
    key(attribute) {
        AdvancedBrushSettingsPanel(parameters, onChange, modifier, attribute)
    }
}
