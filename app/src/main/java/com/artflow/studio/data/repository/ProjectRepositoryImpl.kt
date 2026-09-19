package com.artflow.studio.data.repository

import androidx.room.withTransaction
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.dao.ProjectDao
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.local.entity.ProjectEntity
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

        override suspend fun saveProject(project: Project): Long {
            require(project.id >= 0) { "Invalid project identifier" }
            require(CanvasOperations.isSizeSafe(project.width, project.height)) { "This canvas is too large or has invalid dimensions" }
            require(project.dpi in CanvasOperations.MIN_DPI..CanvasOperations.MAX_DPI) { "Invalid canvas DPI" }
            return database.withTransaction {
                if (project.id == 0L) {
                    // A restored database may lag behind artwork files. Never reuse their IDs.
                    insertNewProject(project.toEntity())
                } else {
                    requireNotNull(projectDao.getProjectById(project.id)) { "This artwork no longer exists" }
                    projectDao.updateProject(project.toEntity())
                    project.id
                }
            }
        }

        /** Called in the database transaction shared by creation and duplication. */
        private suspend fun insertNewProject(project: ProjectEntity): Long {
            val highestStoredId = withContext(Dispatchers.IO) { storage.maximumStoredProjectId() }
            check(highestStoredId < Long.MAX_VALUE) { "Project identifiers are exhausted" }
            // Let AUTOINCREMENT preserve its high-water mark even after every row was deleted.
            val reservedId = projectDao.insertProject(project.copy(id = 0))
            if (reservedId > highestStoredId) return reservedId
            // The provisional row is invisible outside this transaction. No artwork files are touched.
            projectDao.deleteProjectById(reservedId)
            return projectDao.insertProject(project.copy(id = highestStoredId + 1))
        }

        override suspend fun duplicateProject(projectId: Long): Long {
            var copiedId: Long? = null
            var completed = false
            try {
                val id =
                    database.withTransaction {
                        val source = requireNotNull(projectDao.getProjectById(projectId)) { "This artwork no longer exists" }
                        val now = System.currentTimeMillis()
                        val copy =
                            source.copy(
                                id = 0,
                                name = "${source.name} copy",
                                filePath = "",
                                thumbnailPath = null,
                                createdAt = now,
                                modifiedAt = now,
                            )
                        val destinationId = insertNewProject(copy)
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
