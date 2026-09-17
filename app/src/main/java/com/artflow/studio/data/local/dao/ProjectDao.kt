package com.artflow.studio.data.local.dao

import androidx.room.*
import com.artflow.studio.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Project operations
 */
@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY modifiedAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun getProjectById(projectId: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE isFavorite = 1 ORDER BY modifiedAt DESC")
    fun getFavoriteProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE name LIKE :query ORDER BY modifiedAt DESC")
    fun searchProjects(query: String): Flow<List<ProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProjectById(projectId: Long)

    @Query("UPDATE projects SET isFavorite = :isFavorite WHERE id = :projectId")
    suspend fun toggleFavorite(
        projectId: Long,
        isFavorite: Boolean,
    )

    @Query("SELECT COALESCE(MAX(id), 0) FROM projects")
    suspend fun maximumProjectId(): Long

    @Query("SELECT COUNT(*) FROM projects")
    fun getProjectCount(): Flow<Int>
}
