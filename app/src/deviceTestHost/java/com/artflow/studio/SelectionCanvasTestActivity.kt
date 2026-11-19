package com.artflow.studio

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Test-only non-Compose host for device tests that exercise injected Android Views directly.
 *
 * Included in debug and benchmark, and excluded from the publisher's release application.
 * The production Compose activity is intentionally absent here. Selection tests still use Hilt,
 * a real window, the production GLSurfaceView canvas, the production repository and real MotionEvents.
 */
@AndroidEntryPoint
class SelectionCanvasTestActivity : ComponentActivity() {
    lateinit var canvasContainer: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        canvasContainer = FrameLayout(this)
        setContentView(
            canvasContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}
