package com.artflow.studio.di

import android.content.Context
import androidx.room.Room
import com.artflow.studio.data.local.database.ArtFlowDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing database-related dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideArtFlowDatabase(
        @ApplicationContext context: Context
    ): ArtFlowDatabase {
        return Room.databaseBuilder(
            context,
            ArtFlowDatabase::class.java,
            "artflow_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideProjectDao(database: ArtFlowDatabase) = database.projectDao()

    @Provides
    @Singleton
    fun provideBrushDao(database: ArtFlowDatabase) = database.brushDao()

    @Provides
    @Singleton
    fun provideSettingsDao(database: ArtFlowDatabase) = database.settingsDao()
}
