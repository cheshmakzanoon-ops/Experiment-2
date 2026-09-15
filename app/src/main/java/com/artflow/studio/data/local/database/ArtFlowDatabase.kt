package com.artflow.studio.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.artflow.studio.data.local.dao.BrushDao
import com.artflow.studio.data.local.dao.ProjectDao
import com.artflow.studio.data.local.dao.SettingsDao
import com.artflow.studio.data.local.entity.BrushEntity
import com.artflow.studio.data.local.entity.ProjectEntity
import com.artflow.studio.data.local.entity.SettingsEntity

/**
 * Main Room database for ArtFlow application
 * Contains all DAOs and entities
 */
@Database(
    entities = [
        ProjectEntity::class,
        BrushEntity::class,
        SettingsEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class ArtFlowDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    
    abstract fun brushDao(): BrushDao
    
    abstract fun settingsDao(): SettingsDao

    companion object {
        const val DATABASE_NAME = "artflow_database"
    }
}
