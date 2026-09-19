package com.artflow.studio

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
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

    /** Whole-screen capture works for dialogs on API 26 too; node bounds use the same screen coordinates. */
    fun centreRowColors(description: String): List<Int>? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bounds = findBounds(automation.rootInActiveWindow, description) ?: return null
        if (bounds.isEmpty) return null
        val image = automation.takeScreenshot() ?: return null
        try {
            val left = bounds.left + bounds.width() / 3
            val right = bounds.left + bounds.width() * 2 / 3
            val y = bounds.centerY()
            if (left < 0 || right >= image.width) return null
            if (y !in 0 until image.height || left >= right) return null
            return (left..right).map { image.getPixel(it, y) }
        } finally {
            image.recycle()
        }
    }

    private fun findBounds(
        node: AccessibilityNodeInfo?,
        description: String,
    ): Rect? {
        if (node == null) return null
        try {
            if (node.contentDescription?.toString() == description) {
                return Rect().also { node.getBoundsInScreen(it) }
            }
            for (index in 0 until node.childCount) {
                findBounds(node.getChild(index), description)?.let { return it }
            }
            return null
        } finally {
            node.recycle()
        }
    }
}
