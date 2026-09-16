package com.artflow.studio

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

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

        Timber.d("ArtFlow Application initialized")
    }
}
