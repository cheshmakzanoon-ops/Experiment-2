package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.render.BrushPractice

/** Practice colours never change the editor's ink or recolour already recorded test strokes. */
@Composable
fun PracticeInkMenu(
    color: Int,
    onColorChange: (Int) -> Unit,
) {
    val choices =
        listOf(
            "Clay" to BrushPractice.INK,
            "Ocean" to 0xFF2F5E93.toInt(),
            "Ochre" to 0xFFB88428.toInt(),
            "Graphite" to 0xFF202A35.toInt(),
        )
    var expanded by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { it.second == color }?.first ?: "Custom"
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier =
                Modifier.semantics {
                    contentDescription = "Practice ink"
                    stateDescription = current
                },
        ) {
            InkSwatch(color)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (name, value) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { InkSwatch(value) },
                    trailingIcon = { if (color == value) Icon(Icons.Default.Check, contentDescription = "Selected") },
                    onClick = {
                        onColorChange(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun InkSwatch(color: Int) {
    Box(
        Modifier
            .size(22.dp)
            .background(Color(color), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}
