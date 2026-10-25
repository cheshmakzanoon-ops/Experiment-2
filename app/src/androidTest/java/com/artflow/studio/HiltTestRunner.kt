package com.artflow.studio

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner that swaps the application class for [HiltTestApplication], so tests run
 * against the same Hilt graph the app uses. Registered in `app/build.gradle.kts` via
 * `testInstrumentationRunner`.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        try {
            InstalledApkEvidence.capture(this)
        } catch (error: Exception) {
            Log.w("InstalledApkEvidence", "Could not preserve installed APK evidence", error)
        }
        super.onStart()
    }

    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
