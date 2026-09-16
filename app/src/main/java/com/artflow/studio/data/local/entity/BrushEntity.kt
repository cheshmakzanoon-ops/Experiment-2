package com.artflow.studio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a custom brush preset
 */
@Entity(tableName = "brushes")
data class BrushEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val category: String,
    val shape: String,
    val texture: String?,
    val size: Float,
    val opacity: Float,
    val spacing: Float,
    val scatter: Float,
    val rotation: Float,
    val count: Int,
    val pressureSizeCurve: String?,
    val pressureOpacityCurve: String?,
    val colorDynamics: String?,
    val isDefault: Boolean = false,
    val thumbnailPath: String?,
    val createdAt: Long = System.currentTimeMillis(),
)
