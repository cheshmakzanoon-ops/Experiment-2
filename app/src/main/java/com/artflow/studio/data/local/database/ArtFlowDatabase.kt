package com.artflow.studio.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        SettingsEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ArtFlowDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    abstract fun brushDao(): BrushDao

    abstract fun settingsDao(): SettingsDao

    companion object {
        const val DATABASE_NAME = "artflow_database"

        /** Version 2 adds gallery stacks. */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE projects ADD COLUMN stack TEXT")
                }
            }
    }
}
