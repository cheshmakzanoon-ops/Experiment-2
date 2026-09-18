package com.artflow.studio

import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Reads the process page-size contract; x86_64 images emulate 16 KB rather than an ARM kernel. */
@RunWith(AndroidJUnit4::class)
class RuntimeEnvironmentTest {
    @Test
    fun processPageSizeMatchesRequestedTestEnvironment() {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        assertTrue(
            "sysconf must return a positive power of two",
            pageSize > 0 && (pageSize and (pageSize - 1)) == 0L,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "test-evidence")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create runtime evidence directory" }
        val evidence =
            "sdk=${Build.VERSION.SDK_INT}\nabis=${Build.SUPPORTED_ABIS.joinToString()}\n" +
                "pageSize=$pageSize\nfingerprint=${Build.FINGERPRINT}\n"
        File(directory, "runtime-environment.txt").writeText(evidence)
        // UTP may uninstall the test/target packages at completion. Its per-test logcat
        // survives that cleanup, unlike an app-owned external-files directory.
        Log.i("ArtFlowRuntime", evidence)
        val expected = InstrumentationRegistry.getArguments().getString("expectedPageSize")
        if (expected != null) {
            assertEquals(
                "Wrong process page size: this job does not cover the requested environment",
                expected.toLong(),
                pageSize,
            )
        }
    }
}
