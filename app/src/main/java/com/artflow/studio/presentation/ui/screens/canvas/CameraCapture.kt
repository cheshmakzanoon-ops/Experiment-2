package com.artflow.studio.presentation.ui.screens.canvas

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.artflow.studio.R
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.renderer.BitmapPixelBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Actions > Add > Take a photo: the camera app writes into a private file this app shares only for
 * the capture, and the picture arrives as pixels for a new layer.
 */
@Composable
@Suppress("TooGenericExceptionCaught") // Device camera/provider failures become UI errors; cancellation and fatal errors propagate.
fun rememberCameraCapture(
    onImage: (PixelBuffer) -> Unit,
    onError: (String) -> Unit,
    onCancelled: () -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val directory = remember(context) { File(context.filesDir, "camera") }
    val activity =
        remember(context) {
            generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        }
    // Each launcher and each capture owns a separate file. Palette capture can overlap a canvas
    // photo decode, and a completed decoder must never remove the next camera app's destination.
    var pendingFileName by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(directory) {
        onDispose {
            // A recreated launcher restores the filename with its pending Activity result.
            if (activity?.isChangingConfigurations != true) pendingFileName?.let { File(directory, it).delete() }
        }
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val target = pendingFileName?.let { File(directory, it) } ?: return@rememberLauncherForActivityResult
            pendingFileName = null
            if (!taken) {
                target.delete()
                onCancelled()
                return@rememberLauncherForActivityResult
            }
            val decode =
                scope.launch {
                    try {
                        val image =
                            withContext(Dispatchers.IO) {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                                BitmapPixelBridge.decodeUri(context.contentResolver, uri)
                            }
                        if (image == null) onError(context.getString(R.string.cameracapture_photo_could_not_be_read)) else onImage(image)
                    } catch (cancelled: CancellationException) {
                        onCancelled()
                        throw cancelled
                    } catch (error: Exception) {
                        onError(context.getString(R.string.cameracapture_photo_could_not_be_read))
                    }
                }
            // Completion also runs when composition cancellation prevents the job from starting.
            decode.invokeOnCompletion { target.delete() }
        }
    return capture@{
        if (pendingFileName != null) return@capture
        var target: File? = null
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create a camera capture directory" }
            val file = File.createTempFile("capture-", ".jpg", directory).also { target = it }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingFileName = file.name
            launcher.launch(uri)
        } catch (missing: ActivityNotFoundException) {
            pendingFileName = null
            target?.delete()
            onError(context.getString(R.string.cameracapture_no_camera_app))
        } catch (error: Exception) {
            pendingFileName = null
            target?.delete()
            onError(context.getString(R.string.cameracapture_photo_could_not_be_read))
        }
    }
}
