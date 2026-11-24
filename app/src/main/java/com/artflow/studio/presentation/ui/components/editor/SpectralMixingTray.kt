package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.color.SpectralMixingPad
import com.artflow.studio.data.renderer.BitmapPixelBridge
import kotlin.math.roundToInt

/**
 * Floating draggable Spectral Pigment Mixing Tray.
 *
 * Lets artists smear and blend pigments using Kubelka-Munk spectral physics directly on screen,
 * load wells from the active brush color, or pick any blended hue back onto the active brush.
 */
@Composable
fun SpectralMixingTray(
    activeBrushColor: Int,
    onPickColor: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pad = remember { SpectralMixingPad() }
    var revision by remember { mutableIntStateOf(0) }
    var pickMode by remember { mutableStateOf(false) }
    var selectedColor by remember { mutableIntStateOf(activeBrushColor) }
    var windowOffset by remember { mutableStateOf(Offset(24f, 120f)) }
    var padSizePx by remember { mutableStateOf(IntSize(1, 1)) }
    var lastPoint by remember { mutableStateOf<Offset?>(null) }

    val imageBitmap =
        remember(revision) {
            BitmapPixelBridge.toBitmap(pad.buffer).asImageBitmap()
        }

    Surface(
        modifier =
            modifier
                .offset { IntOffset(windowOffset.x.roundToInt(), windowOffset.y.roundToInt()) }
                .width(260.dp),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                windowOffset += dragAmount
                            }
                        },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Spectral Mixing Tray",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { pickMode = !pickMode },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Colorize,
                            contentDescription = if (pickMode) "Switch to mix mode" else "Pick mixed color",
                            tint = if (pickMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            pad.clear()
                            revision++
                        },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Wipe mixing tray",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close mixing tray",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // 8 classic pigment wells + active brush color swatch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pad.wells.forEachIndexed { index, wellColor ->
                    val isSelected = selectedColor == wellColor
                    Box(
                        modifier =
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(wellColor))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color =
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                    shape = CircleShape,
                                ).clickable {
                                    selectedColor = pad.selectWell(index)
                                    pickMode = false
                                    onPickColor(selectedColor)
                                },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Interactive smearable Kubelka-Munk mixing surface
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .onSizeChanged { padSizePx = it }
                        .pointerInput(pickMode, selectedColor) {
                            detectTapGestures { pos ->
                                val px = (pos.x / padSizePx.width.coerceAtLeast(1)) * pad.width
                                val py = (pos.y / padSizePx.height.coerceAtLeast(1)) * pad.height
                                if (pickMode) {
                                    val sampled = pad.sampleAt(px, py)
                                    selectedColor = sampled
                                    onPickColor(sampled)
                                } else {
                                    pad.deposit(px, py, selectedColor)
                                    revision++
                                }
                            }
                        }.pointerInput(pickMode, selectedColor) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    lastPoint = start
                                    val px = (start.x / padSizePx.width.coerceAtLeast(1)) * pad.width
                                    val py = (start.y / padSizePx.height.coerceAtLeast(1)) * pad.height
                                    if (pickMode) {
                                        val sampled = pad.sampleAt(px, py)
                                        selectedColor = sampled
                                        onPickColor(sampled)
                                    } else {
                                        pad.deposit(px, py, selectedColor)
                                        revision++
                                    }
                                },
                                onDragEnd = { lastPoint = null },
                                onDragCancel = { lastPoint = null },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val pos = change.position
                                    val prev = lastPoint ?: pos
                                    lastPoint = pos
                                    val fx = (prev.x / padSizePx.width.coerceAtLeast(1)) * pad.width
                                    val fy = (prev.y / padSizePx.height.coerceAtLeast(1)) * pad.height
                                    val tx = (pos.x / padSizePx.width.coerceAtLeast(1)) * pad.width
                                    val ty = (pos.y / padSizePx.height.coerceAtLeast(1)) * pad.height
                                    if (pickMode) {
                                        val sampled = pad.sampleAt(tx, ty)
                                        selectedColor = sampled
                                        onPickColor(sampled)
                                    } else {
                                        selectedColor = pad.smear(fx, fy, tx, ty, selectedColor)
                                        onPickColor(selectedColor)
                                        revision++
                                    }
                                },
                            )
                        },
            ) {
                drawImage(
                    image = imageBitmap,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (pickMode) "Tap/drag pad to sample" else "Drag to mix pigments",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(selectedColor))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable {
                                selectedColor = activeBrushColor
                                pickMode = false
                            },
                )
            }
        }
    }
}
