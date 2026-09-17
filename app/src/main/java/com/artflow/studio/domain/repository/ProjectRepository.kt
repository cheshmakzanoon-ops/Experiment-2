package com.artflow.studio.domain.repository

import com.artflow.studio.domain.model.Project
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for Project operations
 * Defines the contract for project data access
 */
interface ProjectRepository {
    /**
     * Get all projects ordered by modification date
     */
    fun getAllProjects(): Flow<List<Project>>

    /**
     * Get a specific project by ID
     */
    suspend fun getProjectById(projectId: Long): Project?

    /**
     * Get favorite projects only
     */
    fun getFavoriteProjects(): Flow<List<Project>>

    /**
     * Search projects by name
     */
    fun searchProjects(query: String): Flow<List<Project>>

    /**
     * Insert or update a project
     * Returns the project ID
     */
    suspend fun saveProject(project: Project): Long

    /** Copies the saved artwork and recovery snapshot before exposing the new gallery entry. */
    suspend fun duplicateProject(projectId: Long): Long

    /**
     * Update an existing project
     */
    suspend fun updateProject(project: Project)

    /**
     * Delete a project
     */
    suspend fun deleteProject(project: Project)

    /**
     * Delete a project by ID
     */
    suspend fun deleteProjectById(projectId: Long)

    /**
     * Toggle favorite status for a project
     */
    suspend fun toggleFavorite(
        projectId: Long,
        isFavorite: Boolean,
    )

    /**
     * Get total count of projects
     */
    fun getProjectCount(): Flow<Int>
}
