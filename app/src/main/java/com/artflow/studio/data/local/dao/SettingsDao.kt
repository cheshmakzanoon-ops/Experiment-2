package com.artflow.studio.data.local.dao

import androidx.room.*
import com.artflow.studio.data.local.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Settings operations
 */
@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings ORDER BY category, key")
    fun getAllSettings(): Flow<List<SettingsEntity>>

    @Query("SELECT * FROM settings WHERE key = :key")
    suspend fun getSettingByKey(key: String): SettingsEntity?

    @Query("SELECT * FROM settings WHERE category = :category")
    fun getSettingsByCategory(category: String): Flow<List<SettingsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: SettingsEntity)

    @Update
    suspend fun updateSetting(setting: SettingsEntity)

    @Delete
    suspend fun deleteSetting(setting: SettingsEntity)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun deleteSettingByKey(key: String)

    @Query("UPDATE settings SET value = :value, lastModified = :timestamp WHERE key = :key")
    suspend fun updateSettingValue(
        key: String,
        value: String,
        timestamp: Long = System.currentTimeMillis(),
    )
}
