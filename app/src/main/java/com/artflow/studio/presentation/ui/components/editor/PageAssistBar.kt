package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** What Page Assist's page strip can do. Pages are the document's frames. */
data class PageAssistActions(
    val onSelect: (Int) -> Unit,
    val onAdd: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: (Int) -> Unit,
    val onMove: (from: Int, to: Int) -> Unit,
    val onClose: () -> Unit,
)

/**
 * Page Assist: a strip of page thumbnails along the bottom of the canvas. Tapping a page shows it
 * on the canvas; the buttons add, duplicate, delete and reorder pages. A PDF of all frames exports
 * one page per page.
 */
@Composable
fun PageAssistBar(
    pageCount: Int,
    activePage: Int,
    thumbnails: List<ImageBitmap?>,
    actions: PageAssistActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(activePage) { if (activePage in 0 until pageCount) listState.animateScrollToItem(activePage) }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Page ${activePage + 1} of $pageCount", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { actions.onMove(activePage, activePage - 1) }, enabled = activePage > 0) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Move page earlier")
                }
                IconButton(onClick = { actions.onMove(activePage, activePage + 1) }, enabled = activePage < pageCount - 1) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Move page later")
                }
                IconButton(onClick = actions.onDuplicate) { Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate page") }
                IconButton(onClick = { actions.onDelete(activePage) }, enabled = pageCount > 1) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete page")
                }
                IconButton(onClick = actions.onClose) { Icon(Icons.Default.Close, contentDescription = "Close Page Assist") }
            }
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(List(pageCount) { it }) { index, _ ->
                    PageCard(index, index == activePage, thumbnails.getOrNull(index)) { actions.onSelect(index) }
                }
                item {
                    OutlinedCard(onClick = actions.onAdd, modifier = Modifier.size(PAGE_WIDTH, PAGE_HEIGHT)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = "Add page")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageCard(
    index: Int,
    active: Boolean,
    thumbnail: ImageBitmap?,
    onClick: () -> Unit,
) {
    val outline = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = "Page ${index + 1}"
                    selected = active
                },
    ) {
        Box(
            Modifier
                .size(PAGE_WIDTH, PAGE_HEIGHT - 18.dp)
                .border(BorderStroke(if (active) 2.dp else 1.dp, outline), RoundedCornerShape(6.dp))
                .padding(3.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            thumbnail?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
    }
}

private val PAGE_WIDTH = 64.dp
private val PAGE_HEIGHT = 84.dp
