package com.artflow.studio.domain.usecase.brush

import com.artflow.studio.domain.model.brush.BrushParams
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for UpdateAdvancedBrushParams use case
 * Tests Phase 9: Advanced Brush Parameters implementation
 */
class UpdateAdvancedBrushParamsTest {

    private lateinit var updateAdvancedBrushParams: UpdateAdvancedBrushParams

    @Before
    fun setup() {
        // Note: CanvasRepository is not actually used in the current implementation
        // In a real scenario, we would mock it here
        updateAdvancedBrushParams = UpdateAdvancedBrushParams(
            object : com.artflow.studio.domain.repository.canvas.CanvasRepository {
                override suspend fun createCanvas(width: Int, height: Int, dpi: Int): Long = 1L
                override suspend fun loadCanvas(projectId: Long) = null
                override suspend fun saveCanvas(projectId: Long): String? = null
                override fun undo(): Boolean = false
                override fun redo(): Boolean = false
                override val canUndo: Boolean get() = false
                override val canRedo: Boolean get() = false
                override fun beginStroke(x: Float, y: Float, pressure: Float, brushParams: BrushParams, layerId: Long): Long = 1L
                override fun continueStroke(strokeId: Long, x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float) {}
                override fun endStroke(strokeId: Long) {}
                override suspend fun renderStroke(stroke: com.artflow.studio.domain.model.brush.Stroke) {}
                override suspend fun getCanvasBitmap(): ByteArray? = null
                override suspend fun clearCanvas(color: Int) {}
                override fun setBackgroundColor(color: Int) {}
                override fun getCanvasSize() = com.artflow.studio.domain.repository.canvas.CanvasSize(100, 100, 72)
                override fun observeCanvasInvalidation() =
                    kotlinx.coroutines.flow.emptyFlow<com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent>()
                override fun dispose() {}
                override suspend fun addLayer(name: String?, index: Int?, opacity: Float) = 
                    com.artflow.studio.domain.model.layer.Layer(1, "Layer 1", 0)
                override suspend fun removeLayer(layerId: Long): Boolean = true
                override suspend fun reorderLayer(layerId: Long, newIndex: Int): Boolean = true
                override suspend fun duplicateLayer(layerId: Long): Long? = 2L
                override suspend fun mergeLayers(sourceLayerId: Long, targetLayerId: Long): Boolean = true
                override suspend fun mergeVisibleLayers(keepOriginals: Boolean): Long? = 1L
                override suspend fun mergeLayerDown(layerId: Long): Boolean = true
                override suspend fun setLayerVisibility(layerId: Long, isVisible: Boolean?): Boolean = true
                override suspend fun setLayerOpacity(layerId: Long, opacity: Float): Boolean = true
                override suspend fun setLayerName(layerId: Long, newName: String): Boolean = true
                override suspend fun setLayerLock(layerId: Long, isLocked: Boolean): Boolean = true
                override suspend fun setLayerBlendMode(layerId: Long, blendMode: com.artflow.studio.domain.model.layer.BlendMode): Boolean = true
                override suspend fun setLayerAlphaLock(layerId: Long, isLocked: Boolean?): Boolean = true
                override suspend fun setLayerClippingMask(layerId: Long, isClipping: Boolean?): Boolean = true
                override fun getAllLayers(): List<com.artflow.studio.domain.model.layer.Layer> = emptyList()
                override fun getActiveLayer(): com.artflow.studio.domain.model.layer.Layer? = null
                override fun setActiveLayer(layerId: Long): Boolean = true
            }
        )
    }

    @Test
    fun `test update SIZE parameter`() {
        val initialParams = BrushParams(size = 20f)
        val result = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.SIZE, 50f)

        assertTrue(result.success)
        assertNotNull(result.updatedParams)
        assertEquals(50f, result.updatedParams?.size)
    }

    @Test
    fun `test update OPACITY parameter`() {
        val initialParams = BrushParams(opacity = 1.0f)
        val result = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.OPACITY, 0.5f)

        assertTrue(result.success)
        assertEquals(0.5f, result.updatedParams?.opacity)
    }

    @Test
    fun `test update multiple parameters at once`() {
        val initialParams = BrushParams()
        val updates = mapOf(
            UpdateAdvancedBrushParams.ParamType.SIZE to 30f,
            UpdateAdvancedBrushParams.ParamType.OPACITY to 0.8f,
            UpdateAdvancedBrushParams.ParamType.SCATTER to 0.3f
        )

        val result = kotlinx.coroutines.runBlocking {
            updateAdvancedBrushParams.updateMultiple(initialParams, updates)
        }

        assertTrue(result.success)
        assertEquals(30f, result.updatedParams?.size)
        assertEquals(0.8f, result.updatedParams?.opacity)
        assertEquals(0.3f, result.updatedParams?.scatter)
    }

    @Test
    fun `test apply pencil_soft preset`() {
        val initialParams = BrushParams()
        val result = kotlinx.coroutines.runBlocking {
            updateAdvancedBrushParams.applyPreset(initialParams, "pencil_soft")
        }

        assertTrue(result.success)
        assertEquals(15f, result.updatedParams?.size)
        assertEquals(0.8f, result.updatedParams?.opacity)
        assertEquals(0.05f, result.updatedParams?.spacing)
        assertEquals(0.7f, result.updatedParams?.pressureToSize)
        assertEquals(BrushParams.PressureCurve.EASE_IN, result.updatedParams?.pressureCurve)
    }

    @Test
    fun `test apply airbrush preset`() {
        val initialParams = BrushParams()
        val result = kotlinx.coroutines.runBlocking {
            updateAdvancedBrushParams.applyPreset(initialParams, "airbrush")
        }

        assertTrue(result.success)
        assertEquals(40f, result.updatedParams?.size)
        assertEquals(0.15f, result.updatedParams?.opacity)
        assertEquals(0.02f, result.updatedParams?.spacing)
        assertEquals(3, result.updatedParams?.count)
        assertEquals(BrushParams.PressureCurve.EASE_OUT, result.updatedParams?.pressureCurve)
    }

    @Test
    fun `test apply watercolor preset with color dynamics`() {
        val initialParams = BrushParams()
        val result = kotlinx.coroutines.runBlocking {
            updateAdvancedBrushParams.applyPreset(initialParams, "watercolor")
        }

        assertTrue(result.success)
        assertEquals(0.1f, result.updatedParams?.hueJitter)
        assertEquals(0.15f, result.updatedParams?.saturationJitter)
        assertEquals(0.2f, result.updatedParams?.brightnessJitter)
        assertEquals(true, result.updatedParams?.colorPressure)
        assertEquals(0.8f, result.updatedParams?.wetMix)
    }

    @Test
    fun `test apply oil_paint preset with tilt influence`() {
        val initialParams = BrushParams()
        val result = kotlinx.coroutines.runBlocking {
            updateAdvancedBrushParams.applyPreset(initialParams, "oil_paint")
        }

        assertTrue(result.success)
        assertEquals(0.5f, result.updatedParams?.tiltInfluence)
        assertEquals(true, result.updatedParams?.tiltToRotation)
        assertEquals("canvas_grain", result.updatedParams?.textureId)
        assertEquals(2.0f, result.updatedParams?.textureScale)
    }

    @Test
    fun `test apply splatter preset with high scatter`() {
        val initialParams = BrushParams()
        val result = kotlinx.coroutines.runBlocking {
            updateAdvancedBrushParams.applyPreset(initialParams, "splatter")
        }

        assertTrue(result.success)
        assertEquals(0.8f, result.updatedParams?.scatter)
        assertEquals(5, result.updatedParams?.count)
        assertEquals(0.2f, result.updatedParams?.hueJitter)
        assertEquals(0f, result.updatedParams?.smoothing)
    }

    @Test
    fun `test apply non-existent preset returns error`() {
        val initialParams = BrushParams()
        val result = kotlinx.coroutines.runBlocking {
            updateAdvancedBrushParams.applyPreset(initialParams, "nonexistent_preset")
        }

        assertFalse(result.success)
        assertNull(result.updatedParams)
        assertTrue(result.errorMessage?.contains("not found") == true)
    }

    @Test
    fun `test validateParameter with valid values`() {
        assertTrue(updateAdvancedBrushParams.validateParameter(UpdateAdvancedBrushParams.ParamType.SIZE, 100f))
        assertTrue(updateAdvancedBrushParams.validateParameter(UpdateAdvancedBrushParams.ParamType.OPACITY, 0.5f))
        assertTrue(updateAdvancedBrushParams.validateParameter(UpdateAdvancedBrushParams.ParamType.SCATTER, 0.3f))
        assertTrue(updateAdvancedBrushParams.validateParameter(UpdateAdvancedBrushParams.ParamType.COUNT, 5))
        assertTrue(updateAdvancedBrushParams.validateParameter(UpdateAdvancedBrushParams.ParamType.PRESSURE_CURVE, BrushParams.PressureCurve.LINEAR))
        assertTrue(updateAdvancedBrushParams.validateParameter(UpdateAdvancedBrushParams.ParamType.COLOR_PRESSURE, false))
    }

    @Test
    fun `test validateParameter with invalid values`() {
        assertFalse(updateAdvancedBrushParams.validateParameter(UpdateAdvancedBrushParams.ParamType.SIZE, 600f)) // Too large
        assertFalse(updateAdvancedBrushParams.validateParameter(UpdateAdvancedBrushParams.ParamType.OPACITY, 1.5f)) // > 1.0
        assertFalse(updateAdvancedBrushParams.validateParameter(UpdateAdvancedBrushParams.ParamType.SCATTER, -0.1f)) // < 0.0
        assertFalse(updateAdvancedBrushParams.validateParameter(UpdateAdvancedBrushParams.ParamType.COUNT, 0)) // < 1
        assertFalse(updateAdvancedBrushParams.validateParameter(UpdateAdvancedBrushParams.ParamType.COLOR_PRESSURE, "true")) // Wrong type
    }

    @Test
    fun `test getDefaultValue returns correct types`() {
        assertEquals(20f, updateAdvancedBrushParams.getDefaultValue(UpdateAdvancedBrushParams.ParamType.SIZE))
        assertEquals(1.0f, updateAdvancedBrushParams.getDefaultValue(UpdateAdvancedBrushParams.ParamType.OPACITY))
        assertEquals(0.0f, updateAdvancedBrushParams.getDefaultValue(UpdateAdvancedBrushParams.ParamType.SCATTER))
        assertEquals(1, updateAdvancedBrushParams.getDefaultValue(UpdateAdvancedBrushParams.ParamType.COUNT))
        assertEquals(BrushParams.PressureCurve.LINEAR, updateAdvancedBrushParams.getDefaultValue(UpdateAdvancedBrushParams.ParamType.PRESSURE_CURVE))
        assertEquals(false, updateAdvancedBrushParams.getDefaultValue(UpdateAdvancedBrushParams.ParamType.COLOR_PRESSURE))
        assertNull(updateAdvancedBrushParams.getDefaultValue(UpdateAdvancedBrushParams.ParamType.TEXTURE_ID))
    }

    @Test
    fun `test update TAPER_START and TAPER_END parameters`() {
        val initialParams = BrushParams(taperStart = 0f, taperEnd = 0f)
        
        val resultStart = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.TAPER_START, 0.5f)
        assertTrue(resultStart.success)
        assertEquals(0.5f, resultStart.updatedParams?.taperStart)
        
        val resultEnd = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.TAPER_END, 0.7f)
        assertTrue(resultEnd.success)
        assertEquals(0.7f, resultEnd.updatedParams?.taperEnd)
    }

    @Test
    fun `test update velocity-based parameters`() {
        val initialParams = BrushParams()
        
        val resultVelocityToSize = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.VELOCITY_TO_SIZE, 0.4f)
        assertTrue(resultVelocityToSize.success)
        assertEquals(0.4f, resultVelocityToSize.updatedParams?.velocityToSize)
        
        val resultVelocityToOpacity = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.VELOCITY_TO_OPACITY, 0.3f)
        assertTrue(resultVelocityToOpacity.success)
        assertEquals(0.3f, resultVelocityToOpacity.updatedParams?.velocityToOpacity)
        
        val resultVelocityToHue = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.VELOCITY_TO_HUE, 0.5f)
        assertTrue(resultVelocityToHue.success)
        assertEquals(0.5f, resultVelocityToHue.updatedParams?.velocityToHue)
    }

    @Test
    fun `test update texture parameters`() {
        val initialParams = BrushParams()
        
        val resultTextureId = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.TEXTURE_ID, "custom_texture")
        assertTrue(resultTextureId.success)
        assertEquals("custom_texture", resultTextureId.updatedParams?.textureId)
        
        val resultTextureScale = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.TEXTURE_SCALE, 2.5f)
        assertTrue(resultTextureScale.success)
        assertEquals(2.5f, resultTextureScale.updatedParams?.textureScale)
        
        val resultTextureRotation = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.TEXTURE_ROTATION, 45f)
        assertTrue(resultTextureRotation.success)
        assertEquals(45f, resultTextureRotation.updatedParams?.textureRotation)
        
        val resultBlendTexture = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.BLEND_TEXTURE, true)
        assertTrue(resultBlendTexture.success)
        assertEquals(true, resultBlendTexture.updatedParams?.blendTexture)
    }

    @Test
    fun `test update tilt influence parameters`() {
        val initialParams = BrushParams()
        
        val resultTiltInfluence = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.TILT_INFLUENCE, 0.8f)
        assertTrue(resultTiltInfluence.success)
        assertEquals(0.8f, resultTiltInfluence.updatedParams?.tiltInfluence)
        
        val resultTiltToRotation = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.TILT_TO_ROTATION, true)
        assertTrue(resultTiltToRotation.success)
        assertEquals(true, resultTiltToRotation.updatedParams?.tiltToRotation)
    }

    @Test
    fun `test update FLOW and WET_MIX parameters`() {
        val initialParams = BrushParams(flow = 1.0f, wetMix = 0f)
        
        val resultFlow = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.FLOW, 0.6f)
        assertTrue(resultFlow.success)
        assertEquals(0.6f, resultFlow.updatedParams?.flow)
        
        val resultWetMix = updateAdvancedBrushParams(initialParams, UpdateAdvancedBrushParams.ParamType.WET_MIX, 0.9f)
        assertTrue(resultWetMix.success)
        assertEquals(0.9f, resultWetMix.updatedParams?.wetMix)
    }

    @Test
    fun `test charcoal preset has rough texture`() {
        val initialParams = BrushParams()
        val result = kotlinx.coroutines.runBlocking {
            updateAdvancedBrushParams.applyPreset(initialParams, "charcoal")
        }

        assertTrue(result.success)
        assertEquals(0.3f, result.updatedParams?.scatter)
        assertEquals(2, result.updatedParams?.count)
        assertEquals(0.3f, result.updatedParams?.brightnessJitter)
        assertEquals("charcoal_grain", result.updatedParams?.textureId)
    }

    @Test
    fun `test ink_pen preset has minimal pressure effect`() {
        val initialParams = BrushParams()
        val result = kotlinx.coroutines.runBlocking {
            updateAdvancedBrushParams.applyPreset(initialParams, "ink_pen")
        }

        assertTrue(result.success)
        assertEquals(0.2f, result.updatedParams?.pressureToSize)
        assertEquals(0f, result.updatedParams?.pressureToOpacity)
        assertEquals(BrushParams.PressureCurve.LINEAR, result.updatedParams?.pressureCurve)
    }
}
