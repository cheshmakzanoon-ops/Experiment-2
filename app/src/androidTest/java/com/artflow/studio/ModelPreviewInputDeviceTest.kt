package com.artflow.studio

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.three.ObjParser
import com.artflow.studio.data.renderer.opengl.ModelRenderer
import com.artflow.studio.presentation.ui.components.canvas.ModelPainter
import com.artflow.studio.presentation.ui.components.canvas.ModelView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** The actual model view must cancel and gate pointer painting when its real upload is unavailable. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ModelPreviewInputDeviceTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Before
    fun setup() {
        hilt.inject()
    }

    @Test
    fun lossCancelsTheStrokeAndRecoveryRequiresAFreshDown() {
        val scenario = ActivityScenario.launch(SelectionCanvasTestActivity::class.java)
        val budget = AtomicInteger(32)
        val mesh = ObjParser.parse(OBJ)
        val painter = RecordingPainter()
        var view: ModelView? = null
        var message: String? = null
        try {
            scenario.onActivity { activity ->
                val model = ModelView(activity, ModelRenderer { budget.get() })
                view = model
                model.painting = true
                model.painter = painter
                model.onPreviewMessage = { message = it }
                supply(model, mesh, Color.RED)
                activity.canvasContainer.addView(model, ViewGroup.LayoutParams(256, 256))
            }
            val model = requireNotNull(view)
            runBlocking(Dispatchers.Main) {
                await { model.isPreviewAvailable && model.width > 0 }
                pointer(model, MotionEvent.ACTION_DOWN)
                assertEquals(1, painter.begins)
                budget.set(0)
                supply(model, mesh, Color.GREEN)
                await { !model.isPreviewAvailable && painter.cancels == 1 && message != null }
                assertTrue(requireNotNull(message).contains("Painting is paused"))
                pointer(model, MotionEvent.ACTION_UP)
                pointer(model, MotionEvent.ACTION_DOWN)
                pointer(model, MotionEvent.ACTION_UP)
                assertEquals(1, painter.begins)
                assertEquals(0, painter.finishes)

                budget.set(32)
                model.requestRender() // Retry the retained frame, including both scalar fallbacks.
                await { model.isPreviewAvailable && message == null }
                pointer(model, MotionEvent.ACTION_UP)
                assertEquals(0, painter.finishes)
                pointer(model, MotionEvent.ACTION_DOWN)
                pointer(model, MotionEvent.ACTION_UP)
                assertEquals(2, painter.begins)
                assertEquals(1, painter.finishes)

                model.onPause()
                assertFalse(model.isPreviewAvailable)
                model.onResume()
                await { model.isPreviewAvailable }
            }
        } finally {
            scenario.onActivity { view?.release() }
            scenario.close()
        }
    }

    private fun supply(
        view: ModelView,
        mesh: com.artflow.studio.core.three.Mesh,
        color: Int,
    ) {
        val bitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try {
            assertTrue(view.setFrame(mesh, bitmap, emptyMap()))
            assertFalse("The view must copy, not take the composition's published bitmap", bitmap.isRecycled)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) delay(10)
        assertTrue("The model preview transition did not complete", condition())
    }

    private fun pointer(
        view: ModelView,
        action: Int,
    ) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private class RecordingPainter : ModelPainter {
        var begins = 0
        var cancels = 0
        var finishes = 0

        override fun begin(
            u: Float,
            v: Float,
            pressure: Float,
        ) {
            begins++
        }

        override fun move(
            u: Float,
            v: Float,
            pressure: Float,
        ) = Unit

        override fun end() = Unit

        override fun cancel() {
            cancels++
        }

        override fun finish() {
            finishes++
        }
    }

    private companion object {
        const val OBJ = "v -1 -1 0\nv 1 -1 0\nv 1 1 0\nv -1 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nf 1/1 2/2 3/3 4/4\n"
    }
}
