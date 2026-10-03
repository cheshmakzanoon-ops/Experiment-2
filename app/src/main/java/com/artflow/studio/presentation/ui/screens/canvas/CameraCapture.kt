package com.artflow.studio.presentation.ui.screens.canvas

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.renderer.BitmapPixelBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Actions > Add > Take a photo: the camera app writes into a private file this app shares only for
 * the capture, and the picture arrives as pixels for a new layer.
 */
@Composable
fun rememberCameraCapture(
    onImage: (PixelBuffer) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = remember { File(File(context.filesDir, "camera").apply { mkdirs() }, "capture.jpg") }
    val uri = remember { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            if (!taken) return@rememberLauncherForActivityResult
            scope.launch {
                val image =
                    withContext(Dispatchers.IO) {
                        runCatching { BitmapPixelBridge.decodeUri(context.contentResolver, uri) }.getOrNull().also { target.delete() }
                    }
                if (image == null) onError("The photo could not be read") else onImage(image)
            }
        }
    return {
        try {
            launcher.launch(uri)
        } catch (missing: ActivityNotFoundException) {
            onError("No camera app is available on this device")
        }
    }
}
