package com.artflow.studio

import android.app.Application
import android.content.ComponentCallbacks2
import com.artflow.studio.data.local.BrushAssetStartup
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Application class for ArtFlow - initializes app-wide dependencies
 */
@HiltAndroidApp
class ArtFlowApplication : Application() {
    @Inject lateinit var brushAssetStartup: BrushAssetStartup

    @Inject lateinit var canvasRepository: Lazy<CanvasRepository>

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // MainActivity observes this process-owned IO work before enabling dependent UI.
        brushAssetStartup.start()

        Timber.d("ArtFlow Application initialized")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            canvasRepository.get().trimMemory()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        canvasRepository.get().trimMemory()
    }
}
