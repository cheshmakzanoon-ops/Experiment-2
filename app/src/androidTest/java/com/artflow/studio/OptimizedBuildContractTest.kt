package com.artflow.studio

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs in both inventories; the qualification lane must explicitly request benchmark. */
@RunWith(AndroidJUnit4::class)
class OptimizedBuildContractTest {
    @Test
    fun installedApplicationMatchesTheRequestedBuildTypeAndDebuggability() {
        val arguments = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = arguments.getString("expectedBuildType") ?: "debug"
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val evidence =
            JSONObject()
                .put("schemaVersion", 1)
                .put("attemptToken", arguments.getString("artflowApkEvidenceToken").orEmpty())
                .put("packageName", context.packageName)
                .put("buildType", BuildConfig.BUILD_TYPE)
                .put("debuggable", debuggable)
        File(TestEvidence.directory(), "qualification-build.json").writeText(evidence.toString(2))
        assertTrue("Only debug and optimized benchmark qualification are supported", expected in setOf("debug", "benchmark"))
        assertEquals("com.artflow.studio", context.packageName)
        assertEquals("Instrumentation targeted the wrong application variant", expected, BuildConfig.BUILD_TYPE)
        assertEquals("The installed manifest must match the requested variant", expected == "debug", debuggable)
    }
}
