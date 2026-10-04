package com.artflow.studio.core.three

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjExportTest {
    @Test fun theModelUsesOnlyThePaintedMaterial() {
        val exported = ObjExport.withMaterial("mtllib old.mtl\nv 0 0 0\nusemtl wood\nf 1 1 1")
        val lines = exported.lines()
        assertEquals("mtllib model.mtl", lines[0])
        assertEquals("usemtl artwork", lines[1])
        assertFalse(exported.contains("old.mtl") || exported.contains("wood"))
        assertTrue(exported.contains("v 0 0 0") && exported.contains("f 1 1 1"))
        assertTrue(ObjExport.material().contains("map_Kd texture.png"))
    }
}
