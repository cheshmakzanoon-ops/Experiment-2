package com.artflow.studio.data.export

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class ImportFailureMessageTest {
    @Test
    fun actionableSizeAndMemoryRefusalsReachTheUser() {
        assertEquals(
            "Photo exceeds the image limit",
            importFailureMessage(IllegalArgumentException("Photo exceeds the image limit"), "Fallback"),
        )
        assertEquals(
            "Not enough memory to import this photo",
            importFailureMessage(IllegalStateException("Not enough memory to import this photo"), "Fallback"),
        )
    }

    @Test
    fun providerFailuresAndUnexplainedParserErrorsUseTheReadableFallback() {
        for (failure in listOf(
            IOException("Private provider path"),
            NumberFormatException("For input string"),
            IllegalStateException(" "),
        )) {
            assertEquals("Could not read this photo", importFailureMessage(failure, "Could not read this photo"))
        }
    }

    @Test
    fun cancellationAndFatalRuntimeErrorsAreNeverConvertedIntoUiSuccess() {
        for (failure in listOf(CancellationException("Cancelled"), OutOfMemoryError("VM exhausted"), AssertionError("Broken invariant"))) {
            assertSame(failure, runCatching { importFailureMessage(failure, "Fallback") }.exceptionOrNull())
        }
    }
}
