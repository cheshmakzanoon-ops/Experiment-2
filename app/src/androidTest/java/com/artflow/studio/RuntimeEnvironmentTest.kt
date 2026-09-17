package com.artflow.studio

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the running kernel, not an emulator label or a host-shell assumption. */
@RunWith(AndroidJUnit4::class)
class RuntimeEnvironmentTest {
    @Test
    fun kernelPageSizeMatchesRequestedTestEnvironment() {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        assertTrue(
            "sysconf must return a positive power of two",
            pageSize > 0 && (pageSize and (pageSize - 1)) == 0L,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "test-evidence")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create runtime evidence directory" }
        File(directory, "runtime-environment.txt").writeText(
            "sdk=${Build.VERSION.SDK_INT}\nabis=${Build.SUPPORTED_ABIS.joinToString()}\npageSize=$pageSize\n",
        )
        val expected = InstrumentationRegistry.getArguments().getString("expectedPageSize")
        if (expected != null) {
            assertEquals(
                "Wrong kernel page size: this job does not cover the requested environment",
                expected.toLong(),
                pageSize,
            )
        }
    }
}
