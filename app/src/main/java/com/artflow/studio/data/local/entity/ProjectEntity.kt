package com.artflow.studio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.artflow.studio.domain.model.Project

/**
 * Entity representing an art project in the database
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val filePath: String,
    val thumbnailPath: String?,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val createdAt: Long,
    val modifiedAt: Long,
    val layerCount: Int = 1,
    val isFavorite: Boolean = false,
)

/**
 * Extension function to convert ProjectEntity to domain Project model
 */
fun ProjectEntity.toDomain(): Project =
    Project(
        id = this.id,
        name = this.name,
        filePath = this.filePath,
        thumbnailPath = this.thumbnailPath,
        width = this.width,
        height = this.height,
        dpi = this.dpi,
        createdAt = this.createdAt,
        modifiedAt = this.modifiedAt,
        layerCount = this.layerCount,
        isFavorite = this.isFavorite,
    )

/**
 * Extension function to convert domain Project model to ProjectEntity
 */
fun Project.toEntity(): ProjectEntity =
    ProjectEntity(
        id = this.id,
        name = this.name,
        filePath = this.filePath,
        thumbnailPath = this.thumbnailPath,
        width = this.width,
        height = this.height,
        dpi = this.dpi,
        createdAt = this.createdAt,
        modifiedAt = this.modifiedAt,
        layerCount = this.layerCount,
        isFavorite = this.isFavorite,
    )
