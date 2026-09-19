package com.artflow.studio

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** UTP pulls this argument's directory BEFORE uninstalling test packages and their private files. */
object InstrumentedEvidence {
    fun directory(): File {
        val injected = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = if (!injected.isNullOrBlank()) {
            File(injected)
        } else {
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "test-evidence")
        }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create instrumented evidence directory" }
        return directory
    }
}
