package com.artflow.studio.data.local.dao

import androidx.room.*
import com.artflow.studio.data.local.entity.BrushEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Brush operations
 */
@Dao
interface BrushDao {
    @Query("SELECT * FROM brushes ORDER BY category, name")
    fun getAllBrushes(): Flow<List<BrushEntity>>

    @Query("SELECT * FROM brushes WHERE id = :brushId")
    suspend fun getBrushById(brushId: Long): BrushEntity?

    @Query("SELECT * FROM brushes WHERE category = :category ORDER BY name")
    fun getBrushesByCategory(category: String): Flow<List<BrushEntity>>

    @Query("SELECT DISTINCT category FROM brushes ORDER BY category")
    fun getAllCategories(): Flow<List<String>>

    @Query("SELECT * FROM brushes WHERE isDefault = 1 ORDER BY category, name")
    fun getDefaultBrushes(): Flow<List<BrushEntity>>

    @Query("SELECT * FROM brushes WHERE isDefault = 0 ORDER BY createdAt DESC")
    fun getCustomBrushes(): Flow<List<BrushEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBrush(brush: BrushEntity): Long

    @Update
    suspend fun updateBrush(brush: BrushEntity)

    @Delete
    suspend fun deleteBrush(brush: BrushEntity)

    @Query("DELETE FROM brushes WHERE id = :brushId")
    suspend fun deleteBrushById(brushId: Long)
}
