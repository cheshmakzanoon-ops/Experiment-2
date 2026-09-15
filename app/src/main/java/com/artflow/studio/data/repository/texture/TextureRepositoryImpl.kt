package com.artflow.studio.data.repository.texture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.artflow.studio.domain.model.texture.BrushStamp
import com.artflow.studio.domain.model.texture.BrushTexture
import com.artflow.studio.domain.repository.texture.TextureRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of TextureRepository
 * Handles texture and stamp storage, loading, and caching
 * Implements Phase 10: Brush Textures & Stamps
 */
@Singleton
class TextureRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : TextureRepository {
    
    // In-memory cache for quick access
    private val textureCache = mutableMapOf<String, BrushTexture>()
    private val stampCache = mutableMapOf<String, BrushStamp>()
    
    // State flows for reactive updates
    private val _textures = MutableStateFlow<List<BrushTexture>>(emptyList())
    private val _stamps = MutableStateFlow<List<BrushStamp>>(emptyList())
    
    override fun getAllTextures(): Flow<List<BrushTexture>> = _textures.asStateFlow()
    
    override fun getTexturesByCategory(category: BrushTexture.TextureCategory): Flow<List<BrushTexture>> {
        return _textures.asStateFlow().map { textures ->
            textures.filter { it.category == category }
        }
    }
    
    override suspend fun getTextureById(id: String): BrushTexture? {
        return textureCache[id] ?: loadTextureFromStorage(id)
    }
    
    override suspend fun loadTextureBitmap(textureId: String): Result<BrushTexture> {
        return withContext(Dispatchers.IO) {
            try {
                val texture = textureCache[textureId] ?: loadTextureFromStorage(textureId)
                    ?: return@withContext Result.failure(Exception("Texture not found"))
                
                if (texture.bitmap != null && !texture.bitmap.isRecycled) {
                    return@withContext Result.success(texture)
                }
                
                // Load bitmap from storage
                val file = File(context.filesDir, "textures/$textureId.png")
                if (!file.exists()) {
                    // Try to load from assets for built-in textures
                    val inputStream = context.assets.open("textures/builtin/${texture.name.lowercase()}.png")
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                        .copy(Bitmap.Config.ARGB_8888, true)
                    inputStream.close()
                    
                    val updatedTexture = texture.copyWithBitmap(bitmap)
                    textureCache[textureId] = updatedTexture
                    _textures.value = textureCache.values.toList()
                    
                    Result.success(updatedTexture)
                } else {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        ?.copy(Bitmap.Config.ARGB_8888, true)
                        ?: return@withContext Result.failure(Exception("Failed to decode bitmap"))
                    
                    val updatedTexture = texture.copyWithBitmap(bitmap)
                    textureCache[textureId] = updatedTexture
                    _textures.value = textureCache.values.toList()
                    
                    Result.success(updatedTexture)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun addCustomTexture(texture: BrushTexture): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val id = texture.id.ifEmpty { UUID.randomUUID().toString() }
                val updatedTexture = texture.copy(id = id, isCustom = true)
                
                // Save bitmap if present
                texture.bitmap?.let { bitmap ->
                    val dir = File(context.filesDir, "textures/custom").apply { mkdirs() }
                    val file = File(dir, "$id.png")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                
                textureCache[id] = updatedTexture
                _textures.value = textureCache.values.toList()
                
                Result.success(id)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun deleteTexture(textureId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Free bitmap memory
                textureCache[textureId]?.bitmap?.recycle()
                textureCache.remove(textureId)
                
                // Delete file
                val file = File(context.filesDir, "textures/$textureId.png")
                if (file.exists()) {
                    file.delete()
                }
                
                _textures.value = textureCache.values.toList()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun updateTexture(texture: BrushTexture): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                textureCache[texture.id] = texture
                _textures.value = textureCache.values.toList()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override fun getAllStamps(): Flow<List<BrushStamp>> = _stamps.asStateFlow()
    
    override fun getStampsByCategory(category: BrushStamp.StampCategory): Flow<List<BrushStamp>> {
        return _stamps.asStateFlow().map { stamps ->
            stamps.filter { it.category == category }
        }
    }
    
    override suspend fun getStampById(id: String): BrushStamp? {
        return stampCache[id]
    }
    
    override suspend fun addCustomStamp(stamp: BrushStamp): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val id = stamp.id.ifEmpty { UUID.randomUUID().toString() }
                val updatedStamp = stamp.copy(id = id, isCustom = true)
                
                stampCache[id] = updatedStamp
                _stamps.value = stampCache.values.toList()
                
                Result.success(id)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun deleteStamp(stampId: String): Result<Unit> {
        return withContext(Dispatchers.Default) {
            try {
                stampCache.remove(stampId)
                _stamps.value = stampCache.values.toList()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun generateProceduralStamp(
        shapeType: BrushStamp.ShapeType,
        parameters: Map<String, Float>,
        complexity: Int
    ): Result<BrushStamp> {
        return withContext(Dispatchers.Default) {
            try {
                val id = UUID.randomUUID().toString()
                val shapeData = BrushStamp.StampShapeData(
                    shapeType = shapeType,
                    parameters = parameters,
                    complexity = complexity
                )
                
                val stamp = BrushStamp(
                    id = id,
                    name = "${shapeType.name}_procedural",
                    category = BrushStamp.StampCategory.CUSTOM,
                    shapeData = shapeData,
                    isProcedural = true
                )
                
                stampCache[id] = stamp
                _stamps.value = stampCache.values.toList()
                
                Result.success(stamp)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun importTextureFromPath(filePath: String, name: String): Result<BrushTexture> {
        return withContext(Dispatchers.IO) {
            try {
                val sourceFile = File(filePath)
                if (!sourceFile.exists()) {
                    return@withContext Result.failure(Exception("Source file not found"))
                }
                
                val bitmap = BitmapFactory.decodeFile(filePath)
                    ?.copy(Bitmap.Config.ARGB_8888, true)
                    ?: return@withContext Result.failure(Exception("Failed to decode image"))
                
                val id = UUID.randomUUID().toString()
                val texture = BrushTexture(
                    id = id,
                    name = name,
                    category = BrushTexture.TextureCategory.CUSTOM,
                    bitmap = bitmap,
                    width = bitmap.width,
                    height = bitmap.height,
                    isCustom = true
                )
                
                // Save to app storage
                val dir = File(context.filesDir, "textures/custom").apply { mkdirs() }
                val destFile = File(dir, "$id.png")
                FileOutputStream(destFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                
                textureCache[id] = texture
                _textures.value = textureCache.values.toList()
                
                Result.success(texture)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun exportTextureToPath(textureId: String, filePath: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val texture = textureCache[textureId] ?: loadTextureFromStorage(textureId)
                    ?: return@withContext Result.failure(Exception("Texture not found"))
                
                val bitmap = texture.bitmap
                    ?: return@withContext Result.failure(Exception("Texture bitmap not loaded"))
                
                val destFile = File(filePath)
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    override suspend fun clearCache() {
        withContext(Dispatchers.Default) {
            textureCache.values.forEach { it.bitmap?.recycle() }
            textureCache.clear()
            _textures.value = emptyList()
        }
    }
    
    override suspend fun getMemoryUsage(): Long {
        return withContext(Dispatchers.Default) {
            textureCache.values.sumOf { it.getMemorySize().toLong() }
        }
    }
    
    /**
     * Load texture metadata from storage
     */
    private suspend fun loadTextureFromStorage(id: String): BrushTexture? {
        return withContext(Dispatchers.IO) {
            // Check custom textures first
            val customFile = File(context.filesDir, "textures/custom/$id.png")
            if (customFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(customFile.absolutePath)
                    ?.copy(Bitmap.Config.ARGB_8888, true)
                return@withContext BrushTexture(
                    id = id,
                    name = id,
                    category = BrushTexture.TextureCategory.CUSTOM,
                    bitmap = bitmap,
                    width = bitmap?.width ?: 512,
                    height = bitmap?.height ?: 512,
                    isCustom = true
                )
            }
            
            // Check builtin textures
            val builtinFile = File(context.filesDir, "textures/builtin/$id.png")
            if (builtinFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(builtinFile.absolutePath)
                    ?.copy(Bitmap.Config.ARGB_8888, true)
                return@withContext BrushTexture(
                    id = id,
                    name = id,
                    category = BrushTexture.TextureCategory.PAPER,
                    bitmap = bitmap,
                    width = bitmap?.width ?: 512,
                    height = bitmap?.height ?: 512,
                    isCustom = false
                )
            }
            
            null
        }
    }
    
    /**
     * Initialize with built-in textures
     */
    suspend fun initializeBuiltInTextures() {
        withContext(Dispatchers.Default) {
            val builtInTextures = listOf(
                BrushTexture("watercolor_paper", "Watercolor Paper", BrushTexture.TextureCategory.PAPER),
                BrushTexture("canvas_grain", "Canvas Grain", BrushTexture.TextureCategory.PAPER),
                BrushTexture("pencil_grain", "Pencil Grain", BrushTexture.TextureCategory.GRAIN),
                BrushTexture("charcoal_grain", "Charcoal Grain", BrushTexture.TextureCategory.GRAIN),
                BrushTexture("rough_paper", "Rough Paper", BrushTexture.TextureCategory.PAPER),
                BrushTexture("smooth_paper", "Smooth Paper", BrushTexture.TextureCategory.PAPER)
            )
            
            builtInTextures.forEach { textureCache[it.id] = it }
            _textures.value = textureCache.values.toList()
        }
    }
}
