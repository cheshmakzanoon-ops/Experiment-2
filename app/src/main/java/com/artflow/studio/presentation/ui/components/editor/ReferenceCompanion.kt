package com.artflow.studio.presentation.ui.components.editor

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.artflow.studio.core.canvas.ReferenceViewport
import com.artflow.studio.presentation.ui.theme.LocalArtFlowFlags
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

sealed interface ReferenceImageState {
    data object Empty : ReferenceImageState

    data object Loading : ReferenceImageState

    data object Failed : ReferenceImageState

    data class Ready(
        val bitmap: Bitmap,
    ) : ReferenceImageState
}

/** Decode only a picker-granted content URI. References are not layers, exports or network requests. */
@Composable
fun ReferenceCompanion(
    projectId: Long,
    uri: String?,
    onImport: () -> Unit,
    onClose: () -> Unit,
    onColorPicked: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state = key(projectId, uri) {
        val imageState by produceState<ReferenceImageState>(ReferenceImageState.Empty, context) {
            val source = uri?.let(Uri::parse) ?: return@produceState
            if (source.scheme != "content") {
                value = ReferenceImageState.Failed
                return@produceState
            }
            value = ReferenceImageState.Loading
            val request =
                ImageRequest.Builder(context)
                    .data(source)
                    .size(1024, 1024)
                    .scale(Scale.FIT)
                    .precision(Precision.EXACT)
                    .allowHardware(false)
                    .bitmapConfig(Bitmap.Config.ARGB_8888)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build()
            val result = context.imageLoader.execute(request)
            ensureActive()
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            value =
                if (bitmap != null && bitmap.width <= 1024 && bitmap.height <= 1024) {
                    ReferenceImageState.Ready(bitmap)
                } else {
                    ReferenceImageState.Failed
                }
        }
        imageState
    }
    ReferenceWindow(state, onImport, onClose, onColorPicked, modifier)
}

/** Window layout is bounded to the available canvas, including after rotation or focus changes. */
@Composable
fun ReferenceWindow(
    state: ReferenceImageState,
    onImport: () -> Unit,
    onClose: () -> Unit,
    onColorPicked: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var horizontalPosition by rememberSaveable { mutableFloatStateOf(1f) }
    var verticalPosition by rememberSaveable { mutableFloatStateOf(0f) }
    val touchSize = if (LocalArtFlowFlags.current.largeTouchTargets) 56.dp else 48.dp
    BoxWithConstraints(modifier.fillMaxSize().padding(8.dp)) {
        val windowWidth = minOf(300.dp, maxWidth)
        val windowHeight = minOf(320.dp, maxHeight)
        val maxX = with(density) { (maxWidth - windowWidth).toPx() }
        val maxY = with(density) { (maxHeight - windowHeight).toPx() }
        Surface(
            modifier =
                Modifier
                    .offset { IntOffset((horizontalPosition * maxX).roundToInt(), (verticalPosition * maxY).roundToInt()) }
                    .size(windowWidth, windowHeight)
                    .testTag("reference-window"),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
        ) {
            Column {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(maxX, maxY) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    if (maxX > 0f) horizontalPosition = (horizontalPosition + drag.x / maxX).coerceIn(0f, 1f)
                                    if (maxY > 0f) verticalPosition = (verticalPosition + drag.y / maxY).coerceIn(0f, 1f)
                                }
                            },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Reference",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 12.dp).testTag("reference-handle"),
                    )
                    IconButton(onClick = onImport, modifier = Modifier.size(touchSize)) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Import reference image")
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(touchSize)) {
                        Icon(Icons.Default.Close, contentDescription = "Close reference")
                    }
                }
                when (state) {
                    is ReferenceImageState.Ready -> ReferenceImageViewer(state.bitmap, onColorPicked, Modifier.weight(1f))
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            if (state == ReferenceImageState.Loading) {
                                CircularProgressIndicator(Modifier.size(32.dp))
                            } else {
                                Text(
                                    if (state == ReferenceImageState.Failed) {
                                        "This image could not be opened. Choose a readable image again."
                                    } else {
                                        "Keep a photo beside your artwork. It will not appear in exports."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = onImport) { Text("Choose image") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceImageViewer(
    bitmap: Bitmap,
    onColorPicked: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val onPick by rememberUpdatedState(onColorPicked)
    var viewSize by remember { mutableStateOf(IntSize(1, 1)) }
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var panX by remember(bitmap) { mutableFloatStateOf(0f) }
    var panY by remember(bitmap) { mutableFloatStateOf(0f) }
    var picking by remember(bitmap) { mutableStateOf(false) }
    var sample by remember(bitmap) { mutableStateOf<Int?>(null) }
    val touchSize = if (LocalArtFlowFlags.current.largeTouchTargets) 56.dp else 48.dp

    fun viewport() =
        ReferenceViewport(
            bitmap.width,
            bitmap.height,
            viewSize.width.coerceAtLeast(1).toFloat(),
            viewSize.height.coerceAtLeast(1).toFloat(),
            zoom,
            panX,
            panY,
        )

    fun update(value: ReferenceViewport) {
        zoom = value.zoom
        panX = value.panX
        panY = value.panY
    }

    fun pick(point: Offset) {
        val pixel = viewport().pixelAt(point.x, point.y) ?: return
        val color = bitmap.getPixel(pixel.first, pixel.second)
        sample = color
        onPick(color)
    }

    Column(modifier) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .onSizeChanged { viewSize = it }
                    .testTag("reference-image")
                    .semantics {
                        contentDescription = "Reference image"
                        stateDescription = "${bitmap.width} × ${bitmap.height} pixel preview"
                        customActions =
                            listOf(
                                CustomAccessibilityAction("Sample centre colour") {
                                    pick(Offset(viewSize.width / 2f, viewSize.height / 2f))
                                    true
                                },
                            )
                    }
                    .pointerInput(bitmap) {
                        detectTapGestures(
                            onTap = { if (picking) pick(it) },
                            onLongPress = { pick(it) },
                        )
                    }
                    .pointerInput(bitmap) {
                        detectTransformGestures { centroid, pan, factor, _ ->
                            update(viewport().zoomBy(factor, centroid.x, centroid.y).panBy(pan.x, pan.y))
                        }
                    },
        ) {
            val mapping = viewport()
            withTransform({
                translate(mapping.left, mapping.top)
                scale(mapping.scale, mapping.scale, pivot = Offset.Zero)
            }) {
                drawImage(image)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = { update(viewport().zoomBy(0.8f)) }, enabled = zoom > 1f, modifier = Modifier.size(touchSize)) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom reference out")
            }
            IconButton(
                onClick = { update(viewport().zoomBy(1.25f)) },
                enabled = zoom < ReferenceViewport.MAX_ZOOM,
                modifier = Modifier.size(touchSize),
            ) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom reference in")
            }
            IconButton(onClick = { update(viewport().fit()) }, modifier = Modifier.size(touchSize)) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = "Fit reference")
            }
            IconToggleButton(checked = picking, onCheckedChange = { picking = it }, modifier = Modifier.size(touchSize)) {
                Icon(Icons.Default.Colorize, contentDescription = "Pick reference colour")
            }
        }
        Text(
            text = sample?.let { String.format("#%08X", it) } ?: if (picking) "Tap image to sample" else "Pinch to zoom · Drag to pan",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
