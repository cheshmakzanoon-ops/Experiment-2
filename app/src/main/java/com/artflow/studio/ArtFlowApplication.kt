package com.artflow.studio

import android.app.Application
import com.artflow.studio.data.local.BrushAssetStartup
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Application class for ArtFlow - initializes app-wide dependencies
 */
@HiltAndroidApp
class ArtFlowApplication : Application() {
    @Inject lateinit var brushAssetStartup: BrushAssetStartup

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
}
