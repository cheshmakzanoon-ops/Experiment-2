package com.artflow.studio.di

import com.artflow.studio.data.local.dao.ProjectDao
import com.artflow.studio.data.repository.ProjectRepositoryImpl
import com.artflow.studio.domain.repository.ProjectRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
    fun provideProjectRepository(
        projectDao: ProjectDao
    ): ProjectRepository {
        return ProjectRepositoryImpl(projectDao)
    }
}
