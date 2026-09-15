package com.artflow.studio.domain.model

/**
 * Domain model representing an art project
 */
data class Project(
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
