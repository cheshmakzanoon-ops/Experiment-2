package com.artflow.studio

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** UTP collects this directory before uninstalling the app; app-owned external files are too late. */
object TestEvidence {
    fun directory(): File {
        val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root =
            configured?.let(::File)
                ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        return File(root, "test-evidence").apply { check(isDirectory || mkdirs()) }
    }

    fun screenshot(name: String) {
        require(name.matches(Regex("[a-z0-9-]+\\.png")))
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory(), name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            image.recycle()
        }
    }
}
