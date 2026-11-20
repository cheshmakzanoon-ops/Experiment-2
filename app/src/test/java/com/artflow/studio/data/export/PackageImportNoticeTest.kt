package com.artflow.studio.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageImportNoticeTest {
    @Test
    fun missingDependenciesAreReportedOnceWithBoundedReadableNames() {
        val id = Long.MIN_VALUE
        PendingImports.putDependencyNotice(id, listOf("Script", "Script", "Display", "Mono", "Other"), listOf("a", "a", "b"))
        val notice = requireNotNull(PendingImports.takeNotice(id))
        assertTrue(notice.contains("Fonts need attention:"))
        assertTrue(notice.contains("Script, Display, Mono and 1 more"))
        assertTrue(notice.contains("2 brush texture(s) are missing"))
        assertNull(PendingImports.takeNotice(id))
    }

    @Test
    fun differentAndUnverifiedFontsRemainExplicitInTheImportNotice() {
        val id = Long.MIN_VALUE + 2
        PendingImports.putDependencyNotice(id, listOf("Different font: Script", "Unverified font: Display"), emptyList())
        val notice = requireNotNull(PendingImports.takeNotice(id))
        assertTrue(notice.contains("Different font: Script"))
        assertTrue(notice.contains("Unverified font: Display"))
        assertTrue(notice.contains("before editing those text layers"))
        assertNull(PendingImports.takeNotice(id))
    }

    @Test
    fun aCompletePackageDoesNotCreateAnEmptyNoticeOrDiscardAnExistingNotice() {
        val id = Long.MIN_VALUE + 1
        PendingImports.putDependencyNotice(id, emptyList(), emptyList())
        assertNull(PendingImports.takeNotice(id))
        PendingImports.putNotice(id, "Existing warning")
        PendingImports.putDependencyNotice(id, emptyList(), emptyList())
        assertEquals("Existing warning", PendingImports.takeNotice(id))
    }
}
