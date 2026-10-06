package com.artflow.studio.presentation.ui.components.canvas

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.renderer.BitmapPixelBridge
import timber.log.Timber

/**
 * Procreate's Project Canvas: with [enabled] and a second screen attached (HDMI, a cast or a
 * wireless display), the artwork alone fills that screen, without the interface. [onDisplay]
 * reports the attached screen's longest side, or null when nothing is projected, so the caller
 * only prepares [image] while it is shown.
 */
@Composable
fun CanvasProjection(
    enabled: Boolean,
    image: PixelBuffer?,
    onDisplay: (Int?) -> Unit,
) {
    val context = LocalContext.current
    var display by remember { mutableStateOf<Display?>(null) }
    DisposableEffect(context, enabled) {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        fun pick() {
            display = if (enabled) manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull() else null
        }
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = pick()

                override fun onDisplayRemoved(displayId: Int) = pick()

                override fun onDisplayChanged(displayId: Int) = Unit
            }
        pick()
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose {
            manager.unregisterDisplayListener(listener)
            display = null
        }
    }

    val target = display
    var screen by remember { mutableStateOf<ArtworkScreen?>(null) }
    DisposableEffect(target) {
        val shown =
            target?.let {
                runCatching { ArtworkScreen(context, it).apply { show() } }
                    .onFailure { error -> Timber.w(error, "Could not project the canvas") }
                    .getOrNull()
            }
        screen = shown
        onDisplay(if (shown != null && target != null) maxOf(target.mode.physicalWidth, target.mode.physicalHeight) else null)
        onDispose {
            shown?.dismiss()
            screen = null
            onDisplay(null)
        }
    }
    LaunchedEffect(screen, image) {
        val current = screen ?: return@LaunchedEffect
        image?.let { current.show(BitmapPixelBridge.toBitmap(it)) }
    }
}

/** The second screen: the artwork centred on black, scaled to fit. */
private class ArtworkScreen(
    context: Context,
    display: Display,
) : Presentation(context, display) {
    private lateinit var view: ImageView
    private var shown: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view =
            ImageView(context).apply {
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Artwork"
            }
        setContentView(view)
    }

    fun show(bitmap: Bitmap) {
        if (!::view.isInitialized) return
        view.setImageBitmap(bitmap)
        shown?.recycle()
        shown = bitmap
    }

    override fun onStop() {
        super.onStop()
        shown?.recycle()
        shown = null
    }
}
