package com.artflow.studio.di

import android.content.Context
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.dao.ProjectDao
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.repository.ProjectRepositoryImpl
import com.artflow.studio.domain.repository.ProjectRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing repository implementations
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    @Singleton
    fun provideProjectStorage(
        @ApplicationContext context: Context,
    ): ProjectStorage = ProjectStorage(context)

    @Provides
    @Singleton
    fun provideProjectRepository(
        projectDao: ProjectDao,
        storage: ProjectStorage,
        database: ArtFlowDatabase,
    ): ProjectRepository = ProjectRepositoryImpl(projectDao, storage, database)
}
