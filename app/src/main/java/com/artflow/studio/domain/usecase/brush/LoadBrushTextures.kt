package com.artflow.studio.domain.usecase.brush

import com.artflow.studio.domain.model.brush.BrushTexture
import com.artflow.studio.domain.model.brush.DualTextureConfig
import com.artflow.studio.domain.model.brush.TextureLibrary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for loading brush textures from storage
 * Implements Phase 10: Brush Textures & Stamps
 */
@Singleton
class LoadBrushTextures @Inject constructor() {
    
    private val _textureLibrary = MutableStateFlow(TextureLibrary())
    val textureLibrary: Flow<TextureLibrary> = _textureLibrary.asStateFlow()
    
    /**
     * Load all available textures from the texture library
     * Returns the list of loaded textures
     */
    suspend operator fun invoke(): Result<List<BrushTexture>> {
        return try {
            // Load built-in textures
            val builtinTextures = loadBuiltinTextures()
            
            // Load custom textures (user-imported)
            val customTextures = loadCustomTextures()
            
            val allTextures = builtinTextures + customTextures
            
            _textureLibrary.value = TextureLibrary(textures = allTextures)
            
            Result.success(allTextures)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Load built-in textures that ship with the app
     */
    private fun loadBuiltinTextures(): List<BrushTexture> {
        return listOf(
            // Paper textures
            BrushTexture(
                id = "paper_watercolor_rough",
                name = "Watercolor Rough",
                category = BrushTexture.TextureCategory.PAPER,
                filePath = "textures/paper/watercolor_rough.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "paper_canvas_fine",
                name = "Canvas Fine",
                category = BrushTexture.TextureCategory.PAPER,
                filePath = "textures/paper/canvas_fine.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "paper_sketch_smooth",
                name = "Sketch Smooth",
                category = BrushTexture.TextureCategory.PAPER,
                filePath = "textures/paper/sketch_smooth.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "paper_cold_press",
                name = "Cold Press",
                category = BrushTexture.TextureCategory.PAPER,
                filePath = "textures/paper/cold_press.png",
                isSeamless = true,
                isCustom = false
            ),
            
            // Fabric textures
            BrushTexture(
                id = "fabric_denim",
                name = "Denim",
                category = BrushTexture.TextureCategory.FABRIC,
                filePath = "textures/fabric/denim.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "fabric_silk",
                name = "Silk",
                category = BrushTexture.TextureCategory.FABRIC,
                filePath = "textures/fabric/silk.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "fabric_linen",
                name = "Linen",
                category = BrushTexture.TextureCategory.FABRIC,
                filePath = "textures/fabric/linen.png",
                isSeamless = true,
                isCustom = false
            ),
            
            // Natural textures
            BrushTexture(
                id = "natural_wood_grain",
                name = "Wood Grain",
                category = BrushTexture.TextureCategory.NATURAL,
                filePath = "textures/natural/wood_grain.png",
                isSeamless = false,
                isCustom = false
            ),
            BrushTexture(
                id = "natural_stone",
                name = "Stone",
                category = BrushTexture.TextureCategory.NATURAL,
                filePath = "textures/natural/stone.png",
                isSeamless = false,
                isCustom = false
            ),
            BrushTexture(
                id = "natural_leather",
                name = "Leather",
                category = BrushTexture.TextureCategory.NATURAL,
                filePath = "textures/natural/leather.png",
                isSeamless = true,
                isCustom = false
            ),
            
            // Pattern textures
            BrushTexture(
                id = "pattern_dots",
                name = "Dots",
                category = BrushTexture.TextureCategory.PATTERNS,
                filePath = "textures/patterns/dots.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "pattern_stripes",
                name = "Stripes",
                category = BrushTexture.TextureCategory.PATTERNS,
                filePath = "textures/patterns/stripes.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "pattern_checkerboard",
                name = "Checkerboard",
                category = BrushTexture.TextureCategory.PATTERNS,
                filePath = "textures/patterns/checkerboard.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "pattern_honeycomb",
                name = "Honeycomb",
                category = BrushTexture.TextureCategory.PATTERNS,
                filePath = "textures/patterns/honeycomb.png",
                isSeamless = true,
                isCustom = false
            ),
            
            // Grain textures
            BrushTexture(
                id = "grain_film_iso400",
                name = "Film ISO 400",
                category = BrushTexture.TextureCategory.GRAIN,
                filePath = "textures/grain/film_iso400.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "grain_film_iso1600",
                name = "Film ISO 1600",
                category = BrushTexture.TextureCategory.GRAIN,
                filePath = "textures/grain/film_iso1600.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "grain_noise_uniform",
                name = "Uniform Noise",
                category = BrushTexture.TextureCategory.GRAIN,
                filePath = "textures/grain/noise_uniform.png",
                isSeamless = true,
                isCustom = false
            ),
            
            // Halftone textures
            BrushTexture(
                id = "halftone_round_small",
                name = "Round Small",
                category = BrushTexture.TextureCategory.HALFTONE,
                filePath = "textures/halftone/round_small.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "halftone_round_large",
                name = "Round Large",
                category = BrushTexture.TextureCategory.HALFTONE,
                filePath = "textures/halftone/round_large.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "halftone_lines",
                name = "Lines",
                category = BrushTexture.TextureCategory.HALFTONE,
                filePath = "textures/halftone/lines.png",
                isSeamless = true,
                isCustom = false
            ),
            
            // Brushed textures
            BrushTexture(
                id = "brushed_metal_horizontal",
                name = "Metal Horizontal",
                category = BrushTexture.TextureCategory.BRUSHED,
                filePath = "textures/brushed/metal_horizontal.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "brushed_metal_vertical",
                name = "Metal Vertical",
                category = BrushTexture.TextureCategory.BRUSHED,
                filePath = "textures/brushed/metal_vertical.png",
                isSeamless = true,
                isCustom = false
            ),
            BrushTexture(
                id = "brushed_aluminum",
                name = "Aluminum",
                category = BrushTexture.TextureCategory.BRUSHED,
                filePath = "textures/brushed/aluminum.png",
                isSeamless = true,
                isCustom = false
            )
        )
    }
    
    /**
     * Load custom textures from user storage
     */
    private fun loadCustomTextures(): List<BrushTexture> {
        // TODO: Implement file system scanning for user-imported textures
        // For now, return empty list - will be populated when users import textures
        return emptyList()
    }
    
    /**
     * Get a specific texture by ID
     */
    fun getTextureById(textureId: String): BrushTexture? {
        return _textureLibrary.value.textures.find { it.id == textureId }
    }
    
    /**
     * Search textures by query string
     */
    fun searchTextures(query: String): List<BrushTexture> {
        return _textureLibrary.value.searchTextures(query)
    }
    
    /**
     * Get textures by category
     */
    fun getTexturesByCategory(category: BrushTexture.TextureCategory): List<BrushTexture> {
        return _textureLibrary.value.getTexturesByCategory(category)
    }
}
