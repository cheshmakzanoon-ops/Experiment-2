package com.artflow.studio.presentation.ui.screens.canvas

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.presentation.ui.components.brush.AdvancedBrushSettingsPanel
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel

/**
 * Canvas screen for drawing and painting
 * Integrates OpenGL-accelerated canvas with Jetpack Compose UI
 */
@Composable
fun CanvasScreen(
    projectId: Long,
    onNavigateBack: () -> Unit,
    viewModel: CanvasViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val brushParams by viewModel.brushParams.collectAsState()
    
    var showBrushSettings by remember { mutableStateOf(false) }

    // Initialize canvas on first composition
    LaunchedEffect(Unit) {
        // Create a 1920x1080 canvas at 72 DPI (standard HD)
        viewModel.createNewCanvas(width = 1920, height = 1080, dpi = 72)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Project #$projectId") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        viewModel.saveCanvas(projectId)
                    }) {
                        Icon(
                            androidx.compose.material.icons.Icons.Default.Save,
                            contentDescription = "Save"
                        )
                    }
                    IconButton(onClick = { showBrushSettings = !showBrushSettings }) {
                        Icon(
                            androidx.compose.material.icons.Icons.Default.Settings,
                            contentDescription = "Brush Settings"
                        )
                    }
                }
            )
        },
        bottomBar = {
            // Brush controls
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Brush size slider
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Size: ${brushParams.size.toInt()}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Slider(
                            value = brushParams.size,
                            onValueChange = { viewModel.updateBrushSize(it) },
                            valueRange = 1f..100f
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    // Opacity slider
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Opacity: ${(brushParams.opacity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Slider(
                            value = brushParams.opacity,
                            onValueChange = { viewModel.updateBrushOpacity(it) },
                            valueRange = 0.1f..1f
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.LightGray)
        ) {
            // OpenGL Canvas View
            when (val state = uiState) {
                is CanvasUiState.Initializing,
                is CanvasUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                
                is CanvasUiState.Ready -> {
                    AndroidView(
                        factory = { context ->
                            ArtFlowCanvasView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                initializeCanvas(1920, 1080, 72)
                                setBrushParams(brushParams)
                            }
                        },
                        update = { view ->
                            view.setBrushParams(brushParams)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                is CanvasUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Error: ${state.message}",
                                color = Color.Red,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { 
                                viewModel.createNewCanvas(1920, 1080, 72)
                            }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            // Brush settings dialog - Now with Advanced Brush Settings Panel
            if (showBrushSettings) {
                AdvancedBrushSettingsDialog(
                    brushParams = brushParams,
                    onBrushParamsChanged = { viewModel.updateBrushParams(it) },
                    onDismiss = { showBrushSettings = false }
                )
            }
        }
    }
}

/**
 * Brush settings dialog with advanced brush configuration panel
 */
@Composable
private fun AdvancedBrushSettingsDialog(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Advanced Brush Settings")
                Badge {
                    Text("Phase 9")
                }
            }
        },
        text = {
            AdvancedBrushSettingsPanel(
                brushParams = brushParams,
                onBrushParamsChanged = onBrushParamsChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                // Reset to default brush params
                onBrushParamsChanged(BrushParams())
            }) {
                Text("Reset to Default")
            }
        }
    )
}
