package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcreateExportFormatTest {
    @Test
    fun theProcreateFormatIsOfferedForStillsNextToArtflowAndWritesItsLayers() {
        val formats = ExportFormat.stillFormats()
        assertEquals(ExportFormat.ARTFLOW, formats[formats.indexOf(ExportFormat.PROCREATE) - 1])
        assertEquals("procreate", ExportFormat.PROCREATE.extension)
        assertTrue(ExportFormat.PROCREATE.needsLayers)
        assertEquals(ExportFormat.PROCREATE, ExportFormat.byName("PROCREATE"))
        assertTrue(!ExportFormat.PROCREATE.requiresAnimation && !ExportFormat.PROCREATE.supportsGallery)
    }

    @Test
    fun theFileNameTakesTheProcreateExtension() {
        val options = ExportOptions(format = ExportFormat.PROCREATE, fileName = "Sketch")
        assertEquals("Sketch.procreate", ExportNaming.fileName("Ignored", options))
    }
}
