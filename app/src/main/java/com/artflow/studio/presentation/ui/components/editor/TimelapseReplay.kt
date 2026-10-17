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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.artflow.studio.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen playback of the recorded drawing process, with play/pause, speed and scrubbing. */
@Composable
fun TimelapseReplay(
    frames: List<File>,
    /** Exports the recording; true for full length, false for the 30-second version. */
    onExport: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var index by remember(frames) { mutableIntStateOf(0) }
    var playing by remember(frames) { mutableStateOf(frames.size > 1) }
    var speed by remember { mutableFloatStateOf(1f) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val frameDelay = ((REPLAY_SECONDS * 1000L / frames.size.coerceAtLeast(1)).coerceIn(16L, 250L) / speed).toLong().coerceAtLeast(8L)

    LaunchedEffect(frames, index) {
        val file = frames.getOrNull(index) ?: return@LaunchedEffect
        image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
    }
    LaunchedEffect(playing, frames) {
        while (playing && frames.isNotEmpty()) {
            delay(frameDelay)
            if (index >= frames.lastIndex) playing = false else index++
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.timelapsereplay_time_lapse_replay),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onExport(false) }) { Text(stringResource(R.string.timelapsereplay_export_30)) }
                TextButton(onClick = { onExport(true) }) { Text(stringResource(R.string.timelapsereplay_export_full)) }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.timelapsereplay_close_replay),
                        tint = Color.White,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val current = image
                if (frames.isEmpty()) {
                    Text(stringResource(R.string.timelapsereplay_nothing_recorded_yet_every_edit_is), color = Color.White)
                } else if (current != null) {
                    Image(
                        bitmap = current,
                        contentDescription = stringResource(R.string.timelapsereplay_time_lapse_frame, index + 1),
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
                    TextButton(onClick = { speed = REPLAY_SPEEDS[(REPLAY_SPEEDS.indexOf(speed) + 1) % REPLAY_SPEEDS.size] }) {
                        Text(stringResource(R.string.timelapsereplay_speed, REPLAY_SPEED_LABELS.getValue(speed)), color = Color.White)
                    }
                }
            }
        }
    }
}

private const val REPLAY_SECONDS = 20

/** Replay speeds the speed button cycles through, and how each is written. */
private val REPLAY_SPEEDS = listOf(0.5f, 1f, 2f, 4f)
private val REPLAY_SPEED_LABELS = mapOf(0.5f to "0.5", 1f to "1", 2f to "2", 4f to "4")
