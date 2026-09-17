package com.artflow.studio.data.repository

import androidx.room.withTransaction
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.dao.ProjectDao
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.local.entity.toDomain
import com.artflow.studio.data.local.entity.toEntity
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.repository.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
        private val storage: ProjectStorage,
        private val database: ArtFlowDatabase,
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

        override suspend fun duplicateProject(projectId: Long): Long {
            var copiedId: Long? = null
            var completed = false
            try {
                val id =
                    database.withTransaction {
                        val source = requireNotNull(projectDao.getProjectById(projectId)) { "This artwork no longer exists" }
                        val now = System.currentTimeMillis()
                        val highestId =
                            maxOf(projectDao.maximumProjectId(), withContext(Dispatchers.IO) { storage.maximumStoredProjectId() })
                        check(highestId < Long.MAX_VALUE) { "Project identifiers are exhausted" }
                        val copy =
                            source.copy(
                                id = highestId + 1,
                                name = "${source.name} copy",
                                filePath = "",
                                thumbnailPath = null,
                                createdAt = now,
                                modifiedAt = now,
                            )
                        val destinationId = projectDao.insertProject(copy)
                        // Never remove a pre-existing directory if copying refuses to overwrite it.
                        check(!withContext(Dispatchers.IO) { storage.projectDirectoryExists(destinationId) }) {
                            "The destination already contains project data"
                        }
                        copiedId = destinationId
                        val document = storage.copyProject(projectId, destinationId)
                        projectDao.updateProject(
                            copy.copy(
                                id = destinationId,
                                filePath = if (document != null) storage.documentFile(destinationId).absolutePath else "",
                                thumbnailPath = storage.thumbnailPathIfExists(destinationId),
                                width = document?.width ?: source.width,
                                height = document?.height ?: source.height,
                                dpi = document?.dpi ?: source.dpi,
                            ),
                        )
                        destinationId
                    }
                completed = true
                return id
            } finally {
                if (!completed) {
                    copiedId?.let { id ->
                        withContext(NonCancellable + Dispatchers.IO) {
                            // Cancellation can arrive after Room commits but before withTransaction
                            // resumes. Never delete the files of a row that did commit successfully.
                            if (projectDao.getProjectById(id) == null) storage.deleteProjectFiles(id)
                        }
                    }
                }
            }
        }

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
