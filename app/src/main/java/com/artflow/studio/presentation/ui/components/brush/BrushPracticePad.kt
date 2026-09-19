package com.artflow.studio.presentation.ui.components.brush

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.render.BrushPractice
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/** The same marks are re-rendered when settings change; no paint can escape into the document. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrushPracticePad(
    parameters: BrushParams,
    strokes: List<Stroke>,
    onStrokesChange: (List<Stroke>) -> Unit,
    modifier: Modifier = Modifier,
    ink: Int = BrushPractice.INK,
    onInkChange: ((Int) -> Unit)? = null,
) {
    val latestInk by rememberUpdatedState(ink)
    val latestParameters by rememberUpdatedState(parameters)
    val latestStrokes by rememberUpdatedState(strokes)
    val onChange by rememberUpdatedState(onStrokesChange)
    var active by remember { mutableStateOf<Stroke?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var suppress by remember { mutableStateOf(false) }

    // One render finishes before the latest queued request starts, rather than spawning a worker
    // for every pointer sample. The list and Stroke objects are immutable snapshots.
    LaunchedEffect(Unit) {
        snapshotFlow { latestParameters to (latestStrokes + listOfNotNull(active)).takeLast(BrushPractice.MAX_STROKES) }
            .conflate()
            .collect { (params, paths) ->
                image = withContext(Dispatchers.Default) { BitmapPixelBridge.toBitmap(BrushPractice.render(params, paths)).asImageBitmap() }
            }
    }

    fun point(event: MotionEvent): StrokePoint? {
        if (size.width <= 0 || size.height <= 0) return null
        if (!event.x.isFinite() || !event.y.isFinite()) return null
        return StrokePoint(
            x = (event.x / size.width * BrushPractice.WIDTH).coerceIn(0f, BrushPractice.WIDTH.toFloat()),
            y = (event.y / size.height * BrushPractice.HEIGHT).coerceIn(0f, BrushPractice.HEIGHT.toFloat()),
            pressure = if (event.pressure.isFinite()) event.pressure.coerceIn(0f, 1f) else 1f,
            timestamp = (event.eventTime - event.downTime).coerceAtLeast(0L),
        )
    }

    fun append(event: MotionEvent) {
        val stroke = active ?: return
        val next = point(event) ?: return
        val points = stroke.points
        active = stroke.copy(points = if (points.size < BrushPractice.MAX_POINTS) points + next else points.dropLast(1) + next)
    }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Try your brush", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            onInkChange?.let { change ->
                PracticeInkMenu(ink) {
                    active = null
                    suppress = true
                    change(it)
                }
            }
            TextButton(onClick = {
                active = null
                suppress = true
                onChange(emptyList())
            }) { Text("Clear pad") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val ratio = BrushPractice.WIDTH.toFloat() / BrushPractice.HEIGHT
            val padWidth = minOf(maxWidth, maxHeight * ratio)
            Canvas(
                Modifier
                    .size(padWidth, padWidth / ratio)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(BrushPractice.PAPER))
                    .onSizeChanged {
                        if (size != it) active = null
                        size = it
                    }.testTag("brush-practice-pad")
                    .semantics {
                        contentDescription = "Brush practice pad"
                        stateDescription = "${strokes.size} test strokes; not part of artwork"
                    }.pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                suppress = false
                                point(event)?.let {
                                    active =
                                        Stroke(
                                            id = (latestStrokes.lastOrNull()?.id ?: 0L) + 1L,
                                            points = listOf(it),
                                            brushParams = latestParameters,
                                            layerId = 0L,
                                            color = latestInk,
                                            timestamp = 0L,
                                        )
                                }
                            }
                            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                                active = null
                                suppress = true
                            }
                            MotionEvent.ACTION_MOVE -> if (!suppress) append(event)
                            MotionEvent.ACTION_UP ->
                                if (!suppress) {
                                    append(event)
                                    active?.let { onChange((latestStrokes + it).takeLast(BrushPractice.MAX_STROKES)) }
                                    active = null
                                }
                        }
                        true
                    },
            ) {
                image?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
            }
        }
        Text(
            "Test only · Last 8 strokes · Preview size capped at 48 px",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
