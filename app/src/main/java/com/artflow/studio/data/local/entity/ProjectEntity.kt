package com.artflow.studio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    
    val isFavorite: Boolean = false
)
