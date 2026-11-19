package com.artflow.studio.presentation.ui.screens.canvas

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.artflow.studio.data.export.importFailureMessage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

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
    val files = remember(context) { CameraCaptureFiles(context.filesDir) }
    val activity =
        remember(context) {
            generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        }
    // Each launcher and each capture owns a separate file. Palette capture can overlap a canvas
    // photo decode, and a completed decoder must never remove the next camera app's destination.
    var pendingFileName by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(files) {
        pendingFileName?.let { name ->
            if (files.protect(name) == null) {
                pendingFileName = null
                onError(context.getString(R.string.cameracapture_photo_could_not_be_read))
            }
        }
        onDispose {
            // A recreated launcher restores the filename with its pending Activity result.
            if (activity?.isChangingConfigurations != true) {
                pendingFileName?.let(files::protect)?.let { discardCameraFile(files, it) }
            }
        }
    }
    LaunchedEffect(files) {
        withContext(Dispatchers.IO) { scavengeCameraFiles(files) }
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val target = pendingFileName?.let(files::protect) ?: return@rememberLauncherForActivityResult
            pendingFileName = null
            if (!taken) {
                discardCameraFile(files, target)
                onCancelled()
                return@rememberLauncherForActivityResult
            }
            val decode =
                scope.launch {
                    try {
                        val image =
                            withContext(Dispatchers.IO) {
                                files.requireReadable(target)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                                BitmapPixelBridge.decodeUri(context.contentResolver, uri)
                            }
                        if (image == null) onError(context.getString(R.string.cameracapture_photo_could_not_be_read)) else onImage(image)
                    } catch (cancelled: CancellationException) {
                        onCancelled()
                        throw cancelled
                    } catch (error: Exception) {
                        val message = importFailureMessage(error, context.getString(R.string.cameracapture_photo_could_not_be_read))
                        Timber.w(error, "Camera photo could not be imported")
                        onError(message)
                    }
                }
            // Completion also runs when composition cancellation prevents the job from starting.
            decode.invokeOnCompletion { discardCameraFile(files, target) }
        }
    return capture@{
        if (pendingFileName != null) return@capture
        var target: File? = null
        var launched = false
        try {
            val file = files.create().also { target = it }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingFileName = file.name
            launcher.launch(uri)
            launched = true
            scope.launch(Dispatchers.IO) { scavengeCameraFiles(files) }
        } catch (missing: ActivityNotFoundException) {
            pendingFileName = null
            target?.let { discardCameraFile(files, it) }
            onError(context.getString(R.string.cameracapture_no_camera_app))
        } catch (cancelled: CancellationException) {
            pendingFileName = null
            target?.let { discardCameraFile(files, it) }
            onCancelled()
            throw cancelled
        } catch (error: Exception) {
            pendingFileName = null
            target?.let { discardCameraFile(files, it) }
            val message = importFailureMessage(error, context.getString(R.string.cameracapture_photo_could_not_be_read))
            Timber.w(error, "Camera capture could not be started")
            onError(message)
        } finally {
            // Fatal launch errors still propagate, but must not pin their unused capture forever.
            if (!launched) {
                if (pendingFileName == target?.name) pendingFileName = null
                target?.let { discardCameraFile(files, it) }
            }
        }
    }
}

private fun discardCameraFile(
    files: CameraCaptureFiles,
    file: File,
) {
    try {
        files.discard(file)
    } catch (failure: IOException) {
        Timber.w(failure, "Could not remove a camera capture")
    } catch (failure: SecurityException) {
        Timber.w(failure, "Could not access a camera capture for cleanup")
    }
}

private fun scavengeCameraFiles(files: CameraCaptureFiles) {
    try {
        files.scavenge()
    } catch (failure: IOException) {
        Timber.w(failure, "Could not clean up old camera captures")
    } catch (failure: SecurityException) {
        Timber.w(failure, "Could not access old camera captures")
    }
}
