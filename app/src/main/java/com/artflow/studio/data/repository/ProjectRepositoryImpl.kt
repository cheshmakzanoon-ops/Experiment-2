package com.artflow.studio.data.repository

import com.artflow.studio.data.local.dao.ProjectDao
import com.artflow.studio.data.local.entity.toDomain
import com.artflow.studio.data.local.entity.toEntity
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of ProjectRepository using local database
 */
@Singleton
class ProjectRepositoryImpl
    @Inject
    constructor(
        private val projectDao: ProjectDao,
    ) : ProjectRepository {
        override fun getAllProjects(): Flow<List<Project>> =
            projectDao.getAllProjects().map { entities ->
                entities.map { it.toDomain() }
            }

        override suspend fun getProjectById(projectId: Long): Project? = projectDao.getProjectById(projectId)?.toDomain()

        override fun getFavoriteProjects(): Flow<List<Project>> =
            projectDao.getFavoriteProjects().map { entities ->
                entities.map { it.toDomain() }
            }

        override fun searchProjects(query: String): Flow<List<Project>> =
            projectDao.searchProjects(query).map { entities ->
                entities.map { it.toDomain() }
            }

        override suspend fun saveProject(project: Project): Long = projectDao.insertProject(project.toEntity())

        override suspend fun updateProject(project: Project) {
            projectDao.updateProject(project.toEntity())
        }

        override suspend fun deleteProject(project: Project) {
            projectDao.deleteProject(project.toEntity())
        }

        override suspend fun deleteProjectById(projectId: Long) {
            projectDao.deleteProjectById(projectId)
        }

        override suspend fun toggleFavorite(
            projectId: Long,
            isFavorite: Boolean,
        ) {
            projectDao.toggleFavorite(projectId, isFavorite)
        }

        override fun getProjectCount(): Flow<Int> = projectDao.getProjectCount()
    }
