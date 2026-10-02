package com.artflow.studio

import android.app.Application
import com.artflow.studio.data.local.GrainStorage
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import kotlin.concurrent.thread

/**
 * Application class for ArtFlow - initializes app-wide dependencies
 */
@HiltAndroidApp
class ArtFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Imported brush grains are small; load them off the main thread before the first stroke.
        thread(name = "grain-loader", isDaemon = true) { GrainStorage.loadAll(GrainStorage.directory(filesDir)) }

        Timber.d("ArtFlow Application initialized")
    }
}
