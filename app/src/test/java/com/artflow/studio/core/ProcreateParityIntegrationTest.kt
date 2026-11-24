package com.artflow.studio.core

import com.artflow.studio.core.canvas.KeyChord
import com.artflow.studio.core.canvas.KeyboardShortcuts
import com.artflow.studio.core.canvas.ShortcutAction
import com.artflow.studio.core.color.IccColorManager
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.core.three.GltfPbrExport
import com.artflow.studio.core.three.ModelLighting
import com.artflow.studio.core.three.PbrLighting
import com.artflow.studio.core.three.Vec3
import com.artflow.studio.data.renderer.gles3.Gles3Capabilities
import com.artflow.studio.data.renderer.gles3.Gles3RenderBackend
import com.artflow.studio.data.renderer.gpu.RenderBackendFactory
import com.artflow.studio.data.renderer.gpu.RenderBackendType
import com.artflow.studio.domain.model.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcreateParityIntegrationTest {
    @Test
    fun renderBackendFactoryCreatesGles3WithFloat16WhenSupported() {
        val caps = Gles3Capabilities(glMajorVersion = 3, glMinorVersion = 2, supportsHalfFloatColorBuffer = true, maxTextureSize = 4096)
        val backend = RenderBackendFactory.createBackend(RenderBackendType.GLES3, gles3Capabilities = caps)
        assertTrue(backend is Gles3RenderBackend)
        backend.initialize(32, 32)
        assertTrue((backend as Gles3RenderBackend).isUsingFloat16Pipeline)
        backend.release()
    }

    @Test
    fun modelGlbExportFormatAndPbrLightingIblWorkEndToEnd() {
        assertEquals("glb", ExportFormat.MODEL_GLB.extension)
        val base = PixelBuffer(16, 16).apply { fill(0xFFCC6633.toInt()) }
        val glb = GltfPbrExport.exportGlb("StudioModel", base)
        assertTrue("Expected valid GLB magic header", glb.size > 20 && glb[0] == 'g'.code.toByte() && glb[1] == 'l'.code.toByte())

        PbrLighting.IblEnvironment.entries.forEach { env ->
            val lighting = ModelLighting(iblEnvironment = env)
            val shaded = lighting.shadePbr(0xFFCC6633.toInt(), Vec3(0f, 0f, 1f), Vec3(0f, 0f, 1f))
            assertTrue("Expected opaque shaded pixel for $env", (shaded ushr 24) == 255)
        }
    }

    @Test
    fun customKeyboardShortcutsDecodeAndResolveOverrides() {
        val encoded = KeyboardShortcuts.encodeChord(KeyChord(key = "k", primary = true, shift = true))
        val settings = AppSettings(customShortcuts = mapOf(ShortcutAction.UNDO.name to encoded))
        val overrides = settings.decodedShortcutOverrides()
        assertEquals(
            ShortcutAction.UNDO,
            KeyboardShortcuts.actionFor(KeyChord(key = "k", primary = true, shift = true), overrides),
        )
    }

    @Test
    fun iccColorManagerAndCurvedTextMetadataContractsHold() {
        val vividCyan = 0xFF00FFFF.toInt()
        val cmyk = IccColorManager.srgbToCmyk(vividCyan)
        assertTrue(cmyk.totalInk <= IccColorManager.WorkingSpace.CMYK_SWOP_V2.maxTotalInk + 0.01f)
        assertTrue(IccColorManager.isOutOfCmykGamut(vividCyan))

        val curved =
            TextLayerContent(
                text = "ArtFlow Studio",
                style = TextLayout.TextStyle(),
                color = 0xFF000000.toInt(),
                x = 100f,
                y = 100f,
                curveAmount = 0.5f,
                wordSpacing = 8f,
            ).transform(deltaRotation = 15f, scaleMultiplierX = 1.2f, scaleMultiplierY = 1.2f)
        assertTrue(curved.hasTransform())
        assertEquals(0.5f, curved.curveAmount, 1e-4f)
        assertEquals(8f, curved.wordSpacing, 1e-4f)
        assertEquals(15f, curved.rotationDegrees, 1e-4f)
    }
}
