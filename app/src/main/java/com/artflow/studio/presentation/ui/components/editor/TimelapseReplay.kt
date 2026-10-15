package com.artflow.studio.presentation.ui.components.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen playback of the recorded drawing process, with play/pause and scrubbing. */
@Composable
fun TimelapseReplay(
    frames: List<File>,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var index by remember(frames) { mutableIntStateOf(0) }
    var loadedIndex by remember(frames) { mutableIntStateOf(-1) }
    var playing by remember(frames) { mutableStateOf(frames.size > 1) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(frames, index) {
        val file = frames.getOrNull(index) ?: return@LaunchedEffect
        image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
        loadedIndex = index
    }
    LaunchedEffect(playing, frames) {
        while (playing && frames.isNotEmpty()) {
            delay(REPLAY_FRAME_MS)
            // Advance only after the shown frame is decoded: a slow decode lowers the pace instead of
            // cancelling each decode before it finishes.
            while (loadedIndex != index) delay(REPLAY_POLL_MS)
            if (index >= frames.lastIndex) playing = false else index++
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Time-lapse Replay", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onExport) { Text("Export MP4") }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close replay", tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val current = image
                if (frames.isEmpty()) {
                    Text("Nothing recorded yet. Every edit is recorded automatically.", color = Color.White)
                } else if (current != null) {
                    Image(
                        bitmap = current,
                        contentDescription = "Time-lapse frame ${index + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (frames.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (!playing && index >= frames.lastIndex) index = 0
                        playing = !playing
                    }) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause replay" else "Play replay",
                            tint = Color.White,
                        )
                    }
                    Slider(
                        value = index.toFloat(),
                        onValueChange = {
                            playing = false
                            index = it.toInt().coerceIn(0, frames.lastIndex)
                        },
                        valueRange = 0f..frames.lastIndex.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Text("${index + 1}/${frames.size}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 30 fps: the same hold as a full-length time-lapse MP4, so the replay shows what the export contains. */
private const val REPLAY_FRAME_MS = 33L
private const val REPLAY_POLL_MS = 4L
