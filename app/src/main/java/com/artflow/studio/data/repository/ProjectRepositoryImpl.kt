package com.artflow.studio.data.repository

import androidx.room.withTransaction
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.data.local.GrainStorage
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

        override suspend fun importPackageFile(file: java.io.File): ProjectRepository.ImportedPackage =
            withContext(Dispatchers.IO) {
                // Open and close in one scope: dispatcher cancellation must not orphan an open ZipFile.
                ArtflowPackage.openFile(file).use { stream ->
                    val document = storage.decodePackagedDocument(stream.readBytes(ArtflowPackage.DOCUMENT))
                    insertImportedPackage(
                        name = stream.name,
                        width = document.width,
                        height = document.height,
                        dpi = document.dpi,
                        layerCount = document.layers.size,
                        stream = stream,
                    )
                }
            }

        private suspend fun insertImportedPackage(
            name: String,
            width: Int,
            height: Int,
            dpi: Int,
            layerCount: Int,
            stream: ArtflowPackage.StreamContents,
        ): ProjectRepository.ImportedPackage {
            var importedId: Long? = null
            var completed = false
            val grains = GrainStorage.ImportTransaction()
            try {
                val outcome =
                    database.withTransaction {
                        val now = System.currentTimeMillis()
                        val project =
                            Project(
                                name = name,
                                filePath = "",
                                thumbnailPath = null,
                                width = width,
                                height = height,
                                dpi = dpi,
                                createdAt = now,
                                modifiedAt = now,
                                layerCount = layerCount,
                            )
                        val newId = insertNewProject(project.toEntity())
                        check(!withContext(Dispatchers.IO) { storage.projectDirectoryExists(newId) }) {
                            "The destination already contains project data"
                        }
                        importedId = newId
                        val result = storage.importProjectStream(newId, stream, grains)
                        projectDao.updateProject(
                            project
                                .copy(
                                    id = newId,
                                    filePath = storage.documentFile(newId).absolutePath,
                                    thumbnailPath = storage.thumbnailPathIfExists(newId),
                                ).toEntity(),
                        )
                        ProjectRepository.ImportedPackage(
                            projectId = newId,
                            missingFonts = result.missingFonts,
                            missingGrains = result.missingGrains,
                        )
                    }
                completed = true
                return outcome
            } finally {
                finishImportedPackage(importedId, completed, grains)
            }
        }

        override suspend fun importPackage(bytes: ByteArray): Long =
            storage.withStagedPackage(bytes) { file -> importPackageFile(file).projectId }

        private suspend fun finishImportedPackage(
            projectId: Long?,
            completed: Boolean,
            grains: GrainStorage.ImportTransaction,
        ) = withContext(NonCancellable + Dispatchers.IO) {
            // Cancellation can arrive after Room commits. Retain assets for a committed row; if
            // the query itself fails, leave them intact rather than guessing and breaking artwork.
            var retain = true
            try {
                retain = completed || (projectId != null && projectDao.getProjectById(projectId) != null)
                if (!retain && projectId != null) storage.deleteProjectFiles(projectId)
            } finally {
                if (retain) grains.commit() else grains.close()
            }
        }

        override suspend fun updateProject(project: Project) {
            projectDao.updateProject(project.toEntity())
        }

        override suspend fun renameProject(
            projectId: Long,
            name: String,
            modifiedAt: Long,
        ) {
            require(projectDao.renameProject(projectId, name, modifiedAt) == 1) { "This artwork no longer exists" }
        }

        override suspend fun updateSavedArtwork(project: Project): Project =
            database.withTransaction {
                val current = requireNotNull(projectDao.getProjectById(project.id)) { "This artwork no longer exists" }
                // The editor may have opened before a rename, favorite change or stack move.
                // Read and patch in one transaction so a save cannot restore those old fields.
                val updated =
                    current.copy(
                        thumbnailPath = project.thumbnailPath,
                        width = project.width,
                        height = project.height,
                        dpi = project.dpi,
                        layerCount = project.layerCount,
                        modifiedAt = maxOf(current.modifiedAt, project.modifiedAt),
                    )
                projectDao.updateProject(updated)
                updated.toDomain()
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

        override suspend fun setStack(
            projectIds: List<Long>,
            stack: String?,
        ) {
            projectDao.setStack(projectIds, stack)
        }

        override suspend fun renameStack(
            from: String,
            to: String?,
        ) {
            projectDao.renameStack(from, to)
        }

        override fun getProjectCount(): Flow<Int> = projectDao.getProjectCount()
    }
